package com.github.kmizu.macro_peg.codegen

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

/**
 * Command-line tool: compile a `.mapeg` grammar to a standalone Scala parser.
 *
 * {{{
 *   sbt "runMain com.github.kmizu.macro_peg.codegen.PegCompiler grammar.mapeg -o Parser.scala"
 * }}}
 *
 * Object name, package and start rule default to the grammar's `%object`, `%package` and
 * `%start` directives; the flags below override them when the grammar has none.
 *
 * {{{
 *   PegCompiler <grammar.mapeg> [options]
 *     -o, --out <file>        output file (default: stdout)
 *         --object <name>     generated object name (default: %object or GeneratedParser)
 *         --package <name>    package declaration (default: %package or none)
 *         --start <rule>      start rule (default: %start or S)
 *         --backend <name>    rd | combinator (default: rd)
 *         --quiet             do not print timing information
 * }}}
 *
 * The legacy positional form `<grammar> [Object] [start] [out|-] [package]` is still accepted.
 */
object PegCompiler {
  final case class Options(
    grammarFile: String,
    objectName: String = "GeneratedParser",
    startRule: String = "S",
    outFile: String = "-",
    packageName: Option[String] = None,
    backend: Backend = Backend.RecursiveDescent,
    quiet: Boolean = false
  )

  private val usage: String =
    """Usage: PegCompiler <grammar.mapeg> [options]
      |  -o, --out <file>      output file (default: stdout)
      |      --object <name>   generated object name (default: %object directive or GeneratedParser)
      |      --package <name>  package declaration (default: %package directive or none)
      |      --start <rule>    start rule (default: %start directive or S)
      |      --backend <name>  rd | combinator (default: rd)
      |      --quiet           do not print timing information
      |Legacy positional form: <grammar> [Object] [start] [out|-] [package]
      |""".stripMargin

  /** Parses command-line arguments. Returns Left(message) on invalid input. */
  def parseArgs(args: Seq[String]): Either[String, Options] = {
    def parseBackend(name: String): Either[String, Backend] = name.toLowerCase match {
      case "rd" | "recursive-descent" | "recursivedescent" => Right(Backend.RecursiveDescent)
      case "combinator"                                    => Right(Backend.Combinator)
      case other => Left(s"unknown backend `$other` (expected rd or combinator)")
    }

    @annotation.tailrec
    def loop(rest: List[String], opts: Options, positional: Int): Either[String, Options] = rest match {
      case Nil => Right(opts)
      case ("-o" | "--out") :: v :: tail        => loop(tail, opts.copy(outFile = v), positional)
      case "--object" :: v :: tail              => loop(tail, opts.copy(objectName = v), positional)
      case "--package" :: v :: tail             => loop(tail, opts.copy(packageName = Some(v)), positional)
      case "--start" :: v :: tail               => loop(tail, opts.copy(startRule = v), positional)
      case "--backend" :: v :: tail =>
        parseBackend(v) match {
          case Right(b)  => loop(tail, opts.copy(backend = b), positional)
          case Left(msg) => Left(msg)
        }
      case "--quiet" :: tail                    => loop(tail, opts.copy(quiet = true), positional)
      case flag :: _ if flag.startsWith("-") && flag != "-" => Left(s"unknown or incomplete option `$flag`")
      case v :: tail =>
        // Legacy positional arguments after the grammar file: Object, start, out, package.
        val updated = positional match {
          case 0 => opts.copy(objectName = v)
          case 1 => opts.copy(startRule = v)
          case 2 => opts.copy(outFile = v)
          case 3 => opts.copy(packageName = Some(v))
          case _ => opts
        }
        if(positional > 3) Left(s"unexpected argument `$v`") else loop(tail, updated, positional + 1)
    }

    args.toList match {
      case Nil                                       => Left(usage)
      case ("-h" | "--help") :: _                    => Left(usage)
      case grammar :: rest if !grammar.startsWith("-") => loop(rest, Options(grammarFile = grammar), 0)
      case other :: _                                => Left(s"expected grammar file, got `$other`\n$usage")
    }
  }

  /** Runs the compiler; returns the process exit code instead of calling `sys.exit`. */
  def run(args: Seq[String]): Int = parseArgs(args) match {
    case Left(message) =>
      System.err.println(message)
      1
    case Right(opts) =>
      val path = Paths.get(opts.grammarFile)
      if(!Files.isRegularFile(path)) {
        System.err.println(s"Error: grammar file not found: ${opts.grammarFile}")
        return 1
      }
      val source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      val started = System.nanoTime()
      ParserGenerator.generateFromSource(
        source,
        objectName  = opts.objectName,
        packageName = opts.packageName,
        startRule   = Symbol(opts.startRule),
        backend     = opts.backend
      ) match {
        case Right(code) =>
          val elapsedMs = (System.nanoTime() - started) / 1000000L
          if(opts.outFile == "-") {
            println(code)
          } else {
            Files.write(Paths.get(opts.outFile), code.getBytes(StandardCharsets.UTF_8))
            if(!opts.quiet) System.err.println(s"Written to ${opts.outFile}  (${code.length} chars, ${elapsedMs} ms)")
          }
          0
        case Left(err) =>
          System.err.println(s"Error [${err.phase.label}]: ${err.message}")
          err.position.foreach(p => System.err.println(s"  at ${opts.grammarFile}:${p.line}:${p.column}"))
          err.hint.foreach(h => System.err.println(s"  Hint: $h"))
          2
      }
  }

  def main(args: Array[String]): Unit = {
    val code = run(args.toIndexedSeq)
    if(code != 0) sys.exit(code)
  }
}
