// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.script.dump

import java.nio.file.Files
import java.util.UUID

import com.daml.bazeltools.BazelRunfiles
import com.daml.lf.language.Ast.Package
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.PackageId
import com.daml.lf.engine.script.{GrpcLedgerClient, Participants, Runner, ScriptTimeMode}
import com.daml.ledger.api.domain
import com.daml.ledger.api.refinements.ApiTypes.ApplicationId
import com.daml.ledger.api.testing.utils.{AkkaBeforeAndAfterAll, SuiteResourceManagementAroundEach}
import com.daml.ledger.api.v1.command_service.SubmitAndWaitRequest
import com.daml.ledger.api.v1.commands._
import com.daml.ledger.api.v1.{value => api}
import com.daml.ledger.client.LedgerClient
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement,
}
import com.daml.lf.archive.{Dar, DarReader, Decode}
import com.daml.platform.sandbox.services.TestCommands
import com.daml.platform.sandboxnext.SandboxNextFixture
import scalaz.syntax.tag._
import scalaz.syntax.traverse._

import scala.sys.process._
import org.scalatest._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

final class IT
    extends AsyncFreeSpec
    with Matchers
    with AkkaBeforeAndAfterAll
    with SuiteResourceManagementAroundEach
    with SandboxNextFixture
    with TestCommands {
  private val appId = domain.ApplicationId("script-dump")
  private val clientConfiguration = LedgerClientConfiguration(
    applicationId = appId.unwrap,
    ledgerIdRequirement = LedgerIdRequirement.none,
    commandClient = CommandClientConfiguration.default,
    sslContext = None,
    token = None,
  )
  // TODO cleanup
  private val tmpDir = Files.createTempDirectory("script_dump")
  private val damlc = BazelRunfiles.requiredResource("compiler/damlc/damlc")
  private val damlScriptLib = BazelRunfiles.requiredResource("daml-script/daml/daml-script.dar")
  private def iouId(s: String) =
    api.Identifier(packageId, moduleName = "Iou", s)

  "Generated dump for IOU transfer compiles" in {
    for {
      client <- LedgerClient(channel, clientConfiguration)
      p1 <- client.partyManagementClient.allocateParty(None, None).map(_.party)
      p2 <- client.partyManagementClient.allocateParty(None, None).map(_.party)
      t0 <- client.commandServiceClient.submitAndWaitForTransaction(
        SubmitAndWaitRequest(
          Some(
            Commands(
              ledgerId = client.ledgerId.unwrap,
              applicationId = appId.unwrap,
              commandId = UUID.randomUUID().toString(),
              party = p1,
              commands = Seq(
                Command().withCreate(
                  CreateCommand(
                    templateId = Some(iouId("Iou")),
                    createArguments = Some(
                      api.Record(
                        fields = Seq(
                          api.RecordField("issuer", Some(api.Value().withParty(p1))),
                          api.RecordField("owner", Some(api.Value().withParty(p1))),
                          api.RecordField("currency", Some(api.Value().withText("USD"))),
                          api.RecordField("amount", Some(api.Value().withNumeric("100"))),
                          api.RecordField("observers", Some(api.Value().withList(api.List()))),
                        )
                      )
                    ),
                  )
                )
              ),
            )
          )
        )
      )
      cid0 = t0.getTransaction.events(0).getCreated.contractId
      t1 <- client.commandServiceClient.submitAndWaitForTransaction(
        SubmitAndWaitRequest(
          Some(
            Commands(
              ledgerId = client.ledgerId.unwrap,
              applicationId = appId.unwrap,
              commandId = UUID.randomUUID().toString(),
              party = p1,
              commands = Seq(
                Command().withExercise(
                  ExerciseCommand(
                    templateId = Some(iouId("Iou")),
                    choice = "Iou_Split",
                    contractId = cid0,
                    choiceArgument = Some(
                      api
                        .Value()
                        .withRecord(
                          api.Record(fields =
                            Seq(api.RecordField(value = Some(api.Value().withNumeric("50"))))
                          )
                        )
                    ),
                  )
                )
              ),
            )
          )
        )
      )
      cid1 = t1.getTransaction.events(1).getCreated.contractId
      cid2 = t1.getTransaction.events(2).getCreated.contractId
      t2 <- client.commandServiceClient.submitAndWaitForTransaction(
        SubmitAndWaitRequest(
          Some(
            Commands(
              ledgerId = client.ledgerId.unwrap,
              applicationId = appId.unwrap,
              commandId = UUID.randomUUID().toString(),
              party = p1,
              commands = Seq(
                Command().withExercise(
                  ExerciseCommand(
                    templateId = Some(iouId("Iou")),
                    choice = "Iou_AddObserver",
                    contractId = cid2,
                    choiceArgument = Some(
                      api
                        .Value()
                        .withRecord(
                          api.Record(fields =
                            Seq(api.RecordField(value = Some(api.Value().withParty(p2))))
                          )
                        )
                    ),
                  )
                )
              ),
            )
          )
        )
      )
      cid3 = t2.getTransaction.events(1).getCreated.contractId
      t3 <- client.commandServiceClient.submitAndWaitForTransaction(
        SubmitAndWaitRequest(
          Some(
            Commands(
              ledgerId = client.ledgerId.unwrap,
              applicationId = appId.unwrap,
              commandId = UUID.randomUUID().toString(),
              party = p1,
              commands = Seq(
                Command().withExercise(
                  ExerciseCommand(
                    templateId = Some(iouId("Iou")),
                    choice = "Iou_Transfer",
                    contractId = cid3,
                    choiceArgument = Some(
                      api
                        .Value()
                        .withRecord(
                          api.Record(fields =
                            Seq(api.RecordField(value = Some(api.Value().withParty(p2))))
                          )
                        )
                    ),
                  )
                )
              ),
            )
          )
        )
      )
      cid4 = t3.getTransaction.events(1).getCreated.contractId
      _ <- client.commandServiceClient.submitAndWaitForTransaction(
        SubmitAndWaitRequest(
          Some(
            Commands(
              ledgerId = client.ledgerId.unwrap,
              applicationId = appId.unwrap,
              commandId = UUID.randomUUID().toString(),
              party = p2,
              commands = Seq(
                Command().withExercise(
                  ExerciseCommand(
                    templateId = Some(iouId("IouTransfer")),
                    choice = "IouTransfer_Accept",
                    contractId = cid4,
                    choiceArgument = Some(api.Value().withRecord(api.Record())),
                  )
                )
              ),
            )
          )
        )
      )
      _ <- Main.run(
        Config(
          ledgerHost = "localhost",
          ledgerPort = serverPort.value,
          parties = scala.List(p1, p2),
          outputPath = tmpDir,
          damlScriptLib = damlScriptLib.toString,
          sdkVersion = "0.0.0",
        )
      )

      _ = Seq[String](
        damlc.toString,
        "build",
        "--project-root",
        tmpDir.toString,
        "-o",
        tmpDir.resolve("dump.dar").toString,
      ).! shouldBe 0
      // Now run the DAML Script again
      encodedDar = DarReader().readArchiveFromFile(tmpDir.resolve("dump.dar").toFile).get
      dar: Dar[(PackageId, Package)] = encodedDar
        .map { case (pkgId, pkgArchive) => Decode.readArchivePayload(pkgId, pkgArchive) }
      _ <- Runner.run(
        dar,
        Ref.Identifier(dar.main._1, Ref.QualifiedName.assertFromString("Dump:dump")),
        inputValue = None,
        timeMode = ScriptTimeMode.WallClock,
        initialClients = Participants(
          default_participant = Some(new GrpcLedgerClient(client, ApplicationId("script"))),
          participants = Map.empty,
          party_participants = Map.empty,
        ),
      )
      // TODO Validate the script result.
    } yield succeed
  }
}