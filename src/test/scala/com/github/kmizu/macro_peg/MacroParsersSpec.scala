package com.github.kmizu.macro_peg

import org.scalatest.{DiagrammedAssertions, FunSpec}
import com.github.kmizu.macro_peg.combinator.MacroParsers._

/**
  * Created by Mizushima on 2016/03/15.
  */
class MacroParserSpec extends FunSpec {
  describe("MacroParsers Example") {
    it("palindrome") {
      object Palindrome {
        lazy val S: MacroParser[Any] = P("") ~ !any
        def P(r: MacroParser[Any]): MacroParser[Any] = "a" ~ refer(P("a" ~ r)) / "b" ~ refer(P("b" ~ r)) / r
      }
      val S = Palindrome.S
      assert(S("a").drop == ParseFailure("", "a"))
      assert(S("b").drop == ParseFailure("", "b"))
      assert(S("ab").drop == ParseFailure("", "ab"))
      assert(S("abba").drop == ParseSuccess(None, ""))
      assert(S("abbb").drop == ParseFailure("", "abbb"))
    }
  }
}
