package com.github.kmizu.macro_peg.ruby

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object RubyCorpusRunner {
  private val defaultRoots: List[String] = List(
    "third_party/ruby3/upstream/ruby/test/ruby",
    "third_party/ruby3/upstream/ruby/bootstraptest",
    "third_party/ruby3/upstream/ruby/test/prism"
  )

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def parseWithTimeout(input: String, timeoutMs: Int): Either[String, RubyAst.Program] = {
    try {
      Await.result(Future(RubySubsetParser.parse(input)), timeoutMs.millis)
    } catch {
      case _: java.util.concurrent.TimeoutException =>
        Left(s"timeout after ${timeoutMs}ms")
      case NonFatal(e) =>
        Left(s"exception: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
    }
  }

  private def firstLine(message: String): String =
    message.linesIterator.nextOption().getOrElse(message)

  private def collectRubyFiles(roots: List[Path]): List[Path] =
    roots.flatMap { root =>
      if(!Files.exists(root)) Nil
      else {
        val stream = Files.walk(root)
        try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".rb")).toList
        finally stream.close()
      }
    }.sorted

  def main(args: Array[String]): Unit = {
    val roots =
      if(args.nonEmpty) args.toList.map(Paths.get(_))
      else defaultRoots.map(Paths.get(_))
    val files = collectRubyFiles(roots)
    if(files.isEmpty) {
      println("No .rb files found. Pass roots as args or fetch Ruby corpus into third_party/ruby3/upstream/ruby.")
      return
    }

    val started = System.nanoTime()
    val timeoutMs = sys.env.get("RUBY_CORPUS_TIMEOUT_MS").flatMap(v => v.toIntOption).getOrElse(1000)
    val sampleLimit = sys.env.get("RUBY_CORPUS_FAIL_SAMPLES").flatMap(v => v.toIntOption).getOrElse(20)

    var success = 0
    val failures = scala.collection.mutable.ArrayBuffer.empty[(Path, String)]

    files.foreach { path =>
      try {
        val source = Files.readString(path, StandardCharsets.UTF_8)
        parseWithTimeout(source, timeoutMs) match {
          case Right(_) =>
            success += 1
          case Left(error) =>
            if(failures.size < sampleLimit) failures += path -> firstLine(error)
        }
      } catch {
        case NonFatal(e) =>
          if(failures.size < sampleLimit) {
            failures += path -> s"read error: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
          }
      }
    }

    val total = files.size
    val failed = total - success
    val elapsedMs = (System.nanoTime() - started) / 1000000
    val rate = if(total == 0) 0.0 else success.toDouble / total.toDouble * 100.0

    println(f"Ruby corpus parse result: total=$total success=$success failed=$failed success_rate=$rate%.2f%% elapsed_ms=$elapsedMs timeout_ms=$timeoutMs")
    if(failures.nonEmpty) {
      println(s"Failure samples (first ${failures.size}):")
      failures.foreach { case (path, error) =>
        println(s"- ${path.toString}: $error")
      }
    }
  }
}
