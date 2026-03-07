package com.github.kmizu.macro_peg.ruby

import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object RubyCorpusRunner {
  private val defaultRoots: List[String] = List(
    "third_party/ruby3/upstream/ruby/test/ruby",
    "third_party/ruby3/upstream/ruby/bootstraptest",
    "third_party/ruby3/upstream/ruby/test/prism"
  )
  private val warmupSource: String = {
    val base =
      """class Warmup
        |  def run(arg, kw: 1, &block)
        |    value = [arg, kw, {foo: kw}]
        |    value.each do |item|
        |      block&.call(item)
        |    end
        |    (value.first, self.class.name), *value[0, 1], (value[1], value.last[:foo]) = ["x", "y"], 1, 2, ["z", 3]
        |    case value
        |    in [head, *tail]
        |      head
        |    else
        |      nil
        |    end
        |  end
        |end
        |
        |Warmup.new.run(1, kw: 2) { |x| x }
        |assert_separately(%W[- #{'src'}], __FILE__, __LINE__, <<-'eom', timeout: Float::INFINITY)
        |  value = /#{Warmup}/
        |eom
        |""".stripMargin
    // Add assert_equal patterns similar to test_keyword.rb for JIT warmup
    val assertBlock = (1 to 50).map { i =>
      s"""assert_equal([[$i, h1], {}], o.foo(:bar, $i, :a=>1))
         |assert_equal([$i, h1], o.foo(:baz, $i, **h1))
         |assert_equal([[$i], h1], o.foo(:dbar, $i, a: 1))
         |assert_nil(c.send(:m, **{}))
         |""".stripMargin
    }.mkString
    (base + "\n" + assertBlock) * 3
  }

  private final case class FailureInfo(path: Path, reason: String, message: String, elapsedMs: Long)
  private final case class ParseTiming(path: Path, elapsedMs: Long, status: String)

  private def parseWithTimeout(input: String, timeoutMs: Int): Either[String, RubyAst.Program] = {
    @volatile var result: Either[String, RubyAst.Program] = null
    @volatile var thrown: Throwable = null
    val worker = new Thread(
      () => {
        try {
          result = RubyParser.parse(input)
        } catch {
          case t: Throwable =>
            thrown = t
        }
      },
      "ruby-corpus-parser-worker"
    )
    worker.setDaemon(true)
    worker.start()
    worker.join(timeoutMs.toLong)

    if(worker.isAlive) {
      worker.interrupt()
      worker.join(10L)
      if(worker.isAlive) {
        try {
          worker.stop()
        } catch {
          case _: UnsupportedOperationException =>
            ()
        }
        Left(s"timeout after ${timeoutMs}ms")
      } else if(thrown != null) {
        Left(s"exception: ${thrown.getClass.getSimpleName}: ${Option(thrown.getMessage).getOrElse("")}")
      } else if(result == null) {
        Left("exception: parser worker exited without a result")
      } else {
        result
      }
    } else if(thrown != null) {
      Left(s"exception: ${thrown.getClass.getSimpleName}: ${Option(thrown.getMessage).getOrElse("")}")
    } else if(result == null) {
      Left("exception: parser worker exited without a result")
    } else {
      result
    }
  }

  private def firstLine(message: String): String =
    message.linesIterator.nextOption().getOrElse(message)

  private def classifyFailure(message: String): String =
    if(message.startsWith("timeout after")) "timeout"
    else if(message.startsWith("exception:")) "exception"
    else "parse_error"

  private def normalizeFailureKey(failure: FailureInfo): String =
    s"${failure.reason}: ${firstLine(failure.message).replaceAll("\\s+", " ").trim}"

  private def collectRubyFiles(roots: List[Path]): List[Path] =
    roots.flatMap { root =>
      if(!Files.exists(root)) Nil
      else {
        val stream = Files.walk(root)
        try stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".rb")).toList
        finally stream.close()
      }
    }.sorted

  private val codingPattern = """(?i)\bcoding\s*[:=]\s*([A-Za-z0-9._-]+)""".r
  private val fileEncodingPattern = """(?i)\bfileencoding=([A-Za-z0-9._-]+)""".r

  private def detectRubyEncoding(bytes: Array[Byte]): Option[Charset] = {
    val header = new String(bytes.take(512), StandardCharsets.ISO_8859_1)
    val firstTwoLines = header.linesIterator.take(2).mkString("\n")
    val encodingName =
      codingPattern.findFirstMatchIn(firstTwoLines).map(_.group(1))
        .orElse(fileEncodingPattern.findFirstMatchIn(firstTwoLines).map(_.group(1)))
    encodingName.flatMap { name =>
      try Some(Charset.forName(name))
      catch {
        case _: Exception => None
      }
    }
  }

  private def readRubySource(path: Path): String = {
    val bytes = Files.readAllBytes(path)
    val charset = detectRubyEncoding(bytes).getOrElse(StandardCharsets.UTF_8)
    new String(bytes, charset)
  }

  def main(args: Array[String]): Unit = {
    val roots =
      if(args.nonEmpty) args.toList.map(Paths.get(_))
      else defaultRoots.map(Paths.get(_))
    val allFiles = collectRubyFiles(roots)
    if(allFiles.isEmpty) {
      println("No .rb files found. Pass roots as args or fetch Ruby corpus into third_party/ruby3/upstream/ruby.")
      return
    }

    val started = System.nanoTime()
    val timeoutMs = sys.env.get("RUBY_CORPUS_TIMEOUT_MS").flatMap(v => v.toIntOption).getOrElse(1000)
    val sampleLimit = sys.env.get("RUBY_CORPUS_FAIL_SAMPLES").flatMap(v => v.toIntOption).getOrElse(20)
    val fullError = sys.env.get("RUBY_CORPUS_FULL_ERROR").contains("1")
    val clusterEnabled = sys.env.get("RUBY_CORPUS_CLUSTER").contains("1")
    val profileEnabled = sys.env.get("RUBY_CORPUS_PROFILE").contains("1")
    val profileTop = sys.env.get("RUBY_CORPUS_PROFILE_TOP").flatMap(_.toIntOption).getOrElse(20)
    val gcAfterTimeout = !sys.env.get("RUBY_CORPUS_GC_AFTER_TIMEOUT").contains("0")
    val warmupRounds = sys.env.get("RUBY_CORPUS_WARMUP_ROUNDS").flatMap(_.toIntOption).getOrElse(5)
    val failureOutPath = sys.env.get("RUBY_CORPUS_FAIL_OUT").map(Paths.get(_))
    val maxFiles = sys.env.get("RUBY_CORPUS_MAX_FILES").flatMap(_.toIntOption).filter(_ > 0)
    val files = maxFiles.map(allFiles.take).getOrElse(allFiles)
    maxFiles.foreach { limit =>
      println(s"RUBY_CORPUS_MAX_FILES active: using first ${files.size}/${allFiles.size} files")
    }

    if(warmupRounds > 0) {
      (0 until warmupRounds).foreach { _ =>
        RubyParser.parse(warmupSource)
      }
    }

    var success = 0
    val failures = ArrayBuffer.empty[FailureInfo]
    val timings = ArrayBuffer.empty[ParseTiming]

    files.foreach { path =>
      try {
        val source = readRubySource(path)
        val fileStarted = System.nanoTime()
        parseWithTimeout(source, timeoutMs) match {
          case Right(_) =>
            success += 1
            val elapsedMs = (System.nanoTime() - fileStarted) / 1000000
            timings += ParseTiming(path, elapsedMs, "success")
          case Left(error) =>
            val elapsedMs = (System.nanoTime() - fileStarted) / 1000000
            val message = if(fullError) error else firstLine(error)
            val reason = classifyFailure(error)
            failures += FailureInfo(path, reason, message, elapsedMs)
            timings += ParseTiming(path, elapsedMs, reason)
            if(reason == "timeout" && gcAfterTimeout) {
              System.gc()
            }
        }
      } catch {
        case NonFatal(e) =>
          val message = s"read error: ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
          failures += FailureInfo(path, "read_error", message, elapsedMs = 0L)
          timings += ParseTiming(path, elapsedMs = 0L, "read_error")
      }
    }

    val total = files.size
    val failed = total - success
    val elapsedMs = (System.nanoTime() - started) / 1000000
    val rate = if(total == 0) 0.0 else success.toDouble / total.toDouble * 100.0

    println(f"Ruby corpus parse result: total=$total success=$success failed=$failed success_rate=$rate%.2f%% elapsed_ms=$elapsedMs timeout_ms=$timeoutMs")
    if(failures.nonEmpty) {
      val samples = failures.take(sampleLimit)
      println(s"Failure samples (first ${samples.size}/${failures.size}):")
      samples.foreach { failure =>
        println(s"- ${failure.path.toString}: ${failure.reason}: ${failure.message}")
      }
    }

    if(clusterEnabled && failures.nonEmpty) {
      println("Failure clusters:")
      failures
        .groupBy(normalizeFailureKey)
        .view
        .mapValues(_.size)
        .toList
        .sortBy { case (_, count) => -count }
        .take(20)
        .foreach { case (key, count) =>
          println(s"- $count x $key")
        }
    }

    if(profileEnabled && timings.nonEmpty) {
      println(s"Slowest files (top ${math.min(profileTop, timings.size)}):")
      timings
        .sortBy(-_.elapsedMs)
        .take(profileTop)
        .foreach { timing =>
          println(s"- ${timing.elapsedMs}ms\t${timing.status}\t${timing.path}")
        }
    }

    failureOutPath.foreach { outputPath =>
      val rows = failures.map { failure =>
        val safeMessage = failure.message.replace('\n', ' ').replace('\t', ' ')
        s"${failure.path}\t${failure.reason}\t${failure.elapsedMs}\t$safeMessage"
      }
      Files.write(
        outputPath,
        rows.asJava,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      println(s"Wrote failure report: $outputPath")
    }
  }
}
