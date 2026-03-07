package com.github.kmizu.macro_peg.codegen

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

/**
 * Command-line tool: compile a .mapeg file to a standalone Scala parser.
 *
 * Usage:
 *   sbt "runMain com.github.kmizu.macro_peg.codegen.PegCompiler grammar.mapeg [OutputObject] [startRule] [out.scala]"
 *
 * The generated Scala file can then be compiled with:
 *   scalac -classpath <macro_peg.jar> out.scala
 * and run with:
 *   scala -classpath .:macro_peg.jar -e "println(GeneratedParser.parse(\"x = 1\"))"
 */
object PegCompiler {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      System.err.println(
        """Usage: PegCompiler <grammar.mapeg> [ObjectName=GeneratedParser] [startRule=S] [out.scala=-] [package]
          |  -  : write to stdout
          |""".stripMargin)
      sys.exit(1)
    }

    val pegFile    = args(0)
    val objectName = if (args.length > 1) args(1) else "GeneratedParser"
    val startRule  = Symbol(if (args.length > 2) args(2) else "S")
    val outFile    = if (args.length > 3) args(3) else "-"

    val packageName = if (args.length > 4) Some(args(4)) else None
    val source = new String(Files.readAllBytes(Paths.get(pegFile)), StandardCharsets.UTF_8)

    ParserGenerator.generateFromSource(
      source,
      objectName  = objectName,
      packageName = packageName,
      startRule   = startRule,
      backend     = Backend.RecursiveDescent
    ) match {
      case Right(code) =>
        if (outFile == "-") {
          println(code)
        } else {
          Files.write(Paths.get(outFile), code.getBytes(StandardCharsets.UTF_8))
          System.err.println(s"Written to $outFile  (${code.length} chars)")
        }
      case Left(err) =>
        System.err.println(s"Error [${err.phase.label}]: ${err.message}")
        err.hint.foreach(h => System.err.println(s"  Hint: $h"))
        sys.exit(2)
    }
  }
}
