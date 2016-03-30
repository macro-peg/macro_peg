package com.github.kmizu.macro_peg

import org.scalatest.{DiagrammedAssertions, FunSpec}
import com.github.kmizu.macro_peg.combinator.MacroParsers._

/**
  * Created by Mizushima on 2016/03/15.
  */
class MacroParsersSpec extends FunSpec {
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
    it("a^n b^n c^n") {
      object AnBnCn {
        lazy val S: P[Any] = (refer(A) ~ !"b").and ~ string("a").+ ~ refer(B) ~ !any
        lazy val A: P[Any] = "a" ~ refer(A).? ~ "b"
        lazy val B: P[Any] = "b" ~ refer(B).? ~ "c"
      }
      val S = AnBnCn.S
      assert(S("").drop == ParseFailure("" , ""))
      assert(S("abc").drop == ParseSuccess(None , ""))
      assert(S("abb").drop == ParseFailure("", "abb"))
      assert(S("bba").drop == ParseFailure("", "bba"))
      assert(S("aabbcc").drop == ParseSuccess(None , ""))
    }
    it("min-xml") {
      object MinXML {
        lazy val I: P[Any] = range('a'to'z','A'to'Z', Seq('_')) ~ range('a'to'z','A'to'Z',Seq('_'),'0'to'9').*
        lazy val S: P[Any] = "<" ~ I.evalCC {tag =>
          ">" ~ S.* ~ "</" ~ tag ~ ">"
        }
      }
      val S = MinXML.S
      assert(S("<foo></foo>").drop == ParseSuccess(None, ""))
      assert(S("<foo></bar>").drop == ParseFailure("", "bar>"))
      assert(S("<foo><bar></bar></foo>").drop == ParseSuccess(None, ""))
      assert(S("<foo><bar></foo></bar>").drop == ParseFailure("",  "<bar></foo></bar>"))
    }
  }
}
