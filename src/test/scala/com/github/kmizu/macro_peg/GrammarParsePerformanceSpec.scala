package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec

/**
 * Guards against the grammar parser becoming quadratic again.
 *
 * Before the index-based character primitives in `Parser`, parsing `ruby.mpeg` (~1300 lines)
 * took around five minutes because every single-character test copied the remaining input.
 * It now takes about a second, so a generous bound still catches a regression of that kind.
 */
class GrammarParsePerformanceSpec extends AnyFunSpec {
  private def resource(path: String): String = {
    val stream = getClass.getResourceAsStream(path)
    assert(stream != null, s"missing test resource $path")
    val source = scala.io.Source.fromInputStream(stream)
    try source.mkString finally source.close()
  }

  private def timed[A](body: => A): (A, Long) = {
    val started = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - started) / 1000000L)
  }

  describe("Parser.parse on large grammars") {
    it("parses ruby.mpeg well under the old multi-minute time") {
      val (grammar, ms) = timed(Parser.parse(resource("/ruby.mpeg")))
      assert(grammar.rules.size > 100)
      assert(ms < 30000L, s"parsing ruby.mpeg took ${ms} ms; the grammar parser has become pathologically slow again")
    }

    it("parses ruby_grammar.mapeg well under the old multi-minute time") {
      val (grammar, ms) = timed(Parser.parse(resource("/com/github/kmizu/macro_peg/ruby/ruby_grammar.mapeg")))
      assert(grammar.rules.size > 100)
      assert(ms < 30000L, s"parsing ruby_grammar.mapeg took ${ms} ms; the grammar parser has become pathologically slow again")
    }
  }
}
