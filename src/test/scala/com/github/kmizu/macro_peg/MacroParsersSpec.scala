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
    it("sequence without repetition") {
      object SequenceWithoutRepetition {
        lazy val S: P[Any] = Modifiers(!"") ~ !any
        def Modifiers(AlreadyLooked: P[Any]): P[Any] = (!AlreadyLooked) ~ (
          "a" ~ refer(Modifiers(AlreadyLooked / "a"))
        / "b" ~ refer(Modifiers(AlreadyLooked / "b"))
        / "c" ~ refer(Modifiers(AlreadyLooked / "c"))
        / ""
        )
      }
      val S = SequenceWithoutRepetition.S
      assert(S("a").drop == ParseSuccess(None, ""))
      assert(S("b").drop == ParseSuccess(None, ""))
      assert(S("c").drop == ParseSuccess(None, ""))
      assert(S("aa").drop == ParseFailure("", "aa"))
      assert(S("bb").drop == ParseFailure("", "bb"))
      assert(S("cc").drop == ParseFailure("", "cc"))
      assert(S("ab").drop == ParseSuccess(None, ""))
      assert(S("ac").drop == ParseSuccess(None, ""))
      assert(S("ba").drop == ParseSuccess(None, ""))
      assert(S("bc").drop == ParseSuccess(None, ""))
      assert(S("ca").drop == ParseSuccess(None, ""))
      assert(S("cb").drop == ParseSuccess(None, ""))
    }
  }
}
