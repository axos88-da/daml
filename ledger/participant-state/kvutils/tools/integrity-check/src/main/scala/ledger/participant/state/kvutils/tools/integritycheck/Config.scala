// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils.tools.integritycheck

import java.nio.file.{Path, Paths}

import com.daml.ledger.participant.state.kvutils.export.LedgerDataExporter
import scopt.OptionParser

case class Config(
    exportFilePath: Path,
    performByteComparison: Boolean,
    sortWriteSet: Boolean,
) {
  def exportFileName: String = exportFilePath.getFileName.toString
}

object Config {
  private implicit val `Read Path`: scopt.Read[Path] = scopt.Read.reads(Paths.get(_))

  private val ParseInput: Config = Config(
    exportFilePath = null,
    performByteComparison = true,
    sortWriteSet = false,
  )

  private val Parser: OptionParser[Config] =
    new OptionParser[Config]("integrity-checker") {
      head("kvutils Integrity Checker")
      note(
        s"You can produce a ledger export on a kvutils ledger by setting ${LedgerDataExporter.EnvironmentVariableName}=/path/to/file${System.lineSeparator}")
      help("help")
      arg[Path]("PATH")
        .text("The path to the ledger export file.")
        .action((exportFilePath, config) => config.copy(exportFilePath = exportFilePath))
      opt[Unit]("skip-byte-comparison")
        .text("Skips the byte-for-byte comparison. Useful when comparing behavior across versions.")
        .action((_, config) => config.copy(performByteComparison = false))
      opt[Unit]("sort-write-set")
        .text(
          "Sorts the computed write set. Older exports sorted before writing. Newer versions order them intentionally.")
        .action((_, config) => config.copy(sortWriteSet = true))
    }

  def parse(args: Seq[String]): Option[Config] =
    Parser.parse(args, Config.ParseInput)
}
