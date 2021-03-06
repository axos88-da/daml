// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.on.memory

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.api.util.TimeProvider
import com.daml.caching.Cache
import com.daml.dec.DirectExecutionContext
import com.daml.ledger.api.health.{HealthStatus, Healthy}
import com.daml.ledger.participant.state.kvutils.DamlKvutils.{DamlStateKey, DamlStateValue}
import com.daml.ledger.participant.state.kvutils.api._
import com.daml.ledger.participant.state.kvutils.export.LedgerDataExporter
import com.daml.ledger.participant.state.kvutils.{Envelope, KeyValueCommitting, Raw}
import com.daml.ledger.participant.state.v1.{LedgerId, Offset, ParticipantId, SubmissionResult}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.ledger.validator.batch.{
  BatchedSubmissionValidator,
  BatchedSubmissionValidatorFactory,
  BatchedValidatingCommitter,
  ConflictDetection,
}
import com.daml.ledger.validator.caching.{CachingStateReader, ImmutablesOnlyCacheUpdatePolicy}
import com.daml.ledger.validator.preexecution._
import com.daml.ledger.validator.reading.{DamlLedgerStateReader, LedgerStateReader}
import com.daml.ledger.validator.{StateKeySerializationStrategy, ValidateAndCommit}
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.Metrics
import com.daml.platform.akkastreams.dispatcher.Dispatcher

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

final class InMemoryLedgerReaderWriter private[memory] (
    override val ledgerId: LedgerId,
    override val participantId: ParticipantId,
    dispatcher: Dispatcher[Index],
    state: InMemoryState,
    validateAndCommit: ValidateAndCommit,
    metrics: Metrics,
) extends LedgerReader
    with LedgerWriter {
  override def commit(
      correlationId: String,
      envelope: Raw.Value,
      metadata: CommitMetadata,
  ): Future[SubmissionResult] =
    validateAndCommit(correlationId, envelope, participantId)
      .andThen { case Success(SubmissionResult.Acknowledged) =>
        dispatcher.signalNewHead(state.newHeadSinceLastWrite())
      }(DirectExecutionContext)

  override def events(startExclusive: Option[Offset]): Source[LedgerRecord, NotUsed] =
    reader.events(startExclusive)

  override def currentHealth(): HealthStatus = Healthy

  private val reader = new InMemoryLedgerReader(ledgerId, dispatcher, state, metrics)
}

object InMemoryLedgerReaderWriter {
  val DefaultTimeProvider: TimeProvider = TimeProvider.UTC

  type StateValueCache = Cache[DamlStateKey, DamlStateValue]

  final class BatchingOwner(
      ledgerId: LedgerId,
      batchingLedgerWriterConfig: BatchingLedgerWriterConfig,
      participantId: ParticipantId,
      metrics: Metrics,
      timeProvider: TimeProvider = DefaultTimeProvider,
      stateValueCache: StateValueCache = Cache.none,
      dispatcher: Dispatcher[Index],
      state: InMemoryState,
      engine: Engine,
  )(implicit materializer: Materializer)
      extends ResourceOwner[KeyValueLedger] {
    override def acquire()(implicit context: ResourceContext): Resource[KeyValueLedger] =
      for {
        ledgerDataExporter <- LedgerDataExporter.Owner.acquire()
        keyValueCommitting = createKeyValueCommitting(metrics, timeProvider, engine)
        committer = createBatchedCommitter(
          keyValueCommitting,
          batchingLedgerWriterConfig,
          state,
          metrics,
          timeProvider,
          stateValueCache,
          ledgerDataExporter,
        )
        readerWriter = new InMemoryLedgerReaderWriter(
          ledgerId,
          participantId,
          dispatcher,
          state,
          committer,
          metrics,
        )
        // We need to generate batched submissions for the validator in order to improve throughput.
        // Hence, we have a BatchingLedgerWriter collect and forward batched submissions to the
        // in-memory committer.
        ledgerWriter <- newLoggingContext { implicit loggingContext =>
          ResourceOwner
            .forCloseable(() => BatchingLedgerWriter(batchingLedgerWriterConfig, readerWriter))
            .acquire()
        }
      } yield createKeyValueLedger(readerWriter, ledgerWriter)
  }

  final class SingleParticipantBatchingOwner(
      ledgerId: LedgerId,
      batchingLedgerWriterConfig: BatchingLedgerWriterConfig,
      participantId: ParticipantId,
      timeProvider: TimeProvider = DefaultTimeProvider,
      stateValueCache: StateValueCache = Cache.none,
      metrics: Metrics,
      engine: Engine,
  )(implicit materializer: Materializer)
      extends ResourceOwner[KeyValueLedger] {

    override def acquire()(implicit context: ResourceContext): Resource[KeyValueLedger] = {
      val state = InMemoryState.empty
      for {
        dispatcher <- dispatcherOwner.acquire()
        readerWriter <- new BatchingOwner(
          ledgerId,
          batchingLedgerWriterConfig,
          participantId,
          metrics,
          timeProvider,
          stateValueCache,
          dispatcher,
          state,
          engine,
        ).acquire()
      } yield readerWriter
    }
  }

  final class PreExecutingOwner(
      ledgerId: LedgerId,
      participantId: ParticipantId,
      keySerializationStrategy: StateKeySerializationStrategy,
      metrics: Metrics,
      timeProvider: TimeProvider = DefaultTimeProvider,
      stateValueCache: StateValueCache = Cache.none,
      dispatcher: Dispatcher[Index],
      state: InMemoryState,
      engine: Engine,
  )(implicit materializer: Materializer)
      extends ResourceOwner[KeyValueLedger] {
    override def acquire()(implicit context: ResourceContext): Resource[KeyValueLedger] = {
      val keyValueCommitting =
        createKeyValueCommitting(metrics, timeProvider, engine)

      val committer = createPreExecutingCommitter(
        keyValueCommitting,
        keySerializationStrategy,
        state,
        metrics,
        timeProvider,
        stateValueCache,
      )

      val readerWriter = new InMemoryLedgerReaderWriter(
        ledgerId,
        participantId,
        dispatcher,
        state,
        committer,
        metrics,
      )

      Resource.successful(createKeyValueLedger(readerWriter, readerWriter))
    }
  }

  private def createBatchedCommitter(
      keyValueCommitting: KeyValueCommitting,
      batchingLedgerWriterConfig: BatchingLedgerWriterConfig,
      state: InMemoryState,
      metrics: Metrics,
      timeProvider: TimeProvider,
      stateValueCache: StateValueCache,
      ledgerDataExporter: LedgerDataExporter,
  )(implicit materializer: Materializer): ValidateAndCommit = {
    val validator = BatchedSubmissionValidator[Index](
      BatchedSubmissionValidatorFactory.defaultParametersFor(
        batchingLedgerWriterConfig.enableBatching
      ),
      keyValueCommitting,
      new ConflictDetection(metrics),
      metrics,
      ledgerDataExporter,
    )
    val committer = BatchedValidatingCommitter[Index](
      () => timeProvider.getCurrentTime,
      validator,
      stateValueCache,
    )
    locally {
      implicit val executionContext: ExecutionContext = materializer.executionContext

      def validateAndCommit(
          correlationId: String,
          submissionEnvelope: Raw.Value,
          submittingParticipantId: ParticipantId,
      ) =
        new InMemoryLedgerStateAccess(state, metrics).inTransaction { ledgerStateOperations =>
          committer.commit(
            correlationId,
            submissionEnvelope,
            submittingParticipantId,
            ledgerStateOperations,
          )
        }

      validateAndCommit
    }
  }

  private def createPreExecutingCommitter(
      keyValueCommitting: KeyValueCommitting,
      keySerializationStrategy: StateKeySerializationStrategy,
      state: InMemoryState,
      metrics: Metrics,
      timeProvider: TimeProvider,
      stateValueCache: StateValueCache,
  )(implicit materializer: Materializer): ValidateAndCommit = {
    val committer = new PreExecutingValidatingCommitter[
      Option[DamlStateValue],
      RawPreExecutingCommitStrategy.ReadSet,
      RawKeyValuePairsWithLogEntry,
    ](
      transformStateReader = transformStateReader(keySerializationStrategy, stateValueCache),
      validator = new PreExecutingSubmissionValidator(
        keyValueCommitting,
        new RawPreExecutingCommitStrategy(keySerializationStrategy),
        metrics,
      ),
      postExecutionConflictDetector = new EqualityBasedPostExecutionConflictDetector(),
      postExecutionFinalizer = new RawPostExecutionFinalizer(
        now = () => timeProvider.getCurrentTime
      ),
    )
    locally {
      implicit val executionContext: ExecutionContext = materializer.executionContext

      def validateAndCommit(
          correlationId: String,
          submissionEnvelope: Raw.Value,
          submittingParticipantId: ParticipantId,
      ) =
        committer.commit(
          correlationId,
          submissionEnvelope,
          submittingParticipantId,
          new InMemoryLedgerStateAccess(state, metrics),
        )

      validateAndCommit
    }
  }

  private def transformStateReader(
      keySerializationStrategy: StateKeySerializationStrategy,
      cache: Cache[DamlStateKey, DamlStateValue],
  )(stateReader: LedgerStateReader): DamlLedgerStateReader = {
    CachingStateReader(
      cache,
      ImmutablesOnlyCacheUpdatePolicy,
      stateReader
        .contramapKeys(keySerializationStrategy.serializeStateKey)
        .mapValues(value =>
          value.map(
            Envelope
              .openStateValue(_)
              .getOrElse(sys.error("Opening enveloped DamlStateValue failed"))
          )
        ),
    )
  }

  private def createKeyValueCommitting(
      metrics: Metrics,
      timeProvider: TimeProvider,
      engine: Engine,
  ): KeyValueCommitting =
    new KeyValueCommitting(engine, metrics, inStaticTimeMode = needStaticTimeModeFor(timeProvider))

  private def needStaticTimeModeFor(timeProvider: TimeProvider): Boolean =
    timeProvider != TimeProvider.UTC
}
