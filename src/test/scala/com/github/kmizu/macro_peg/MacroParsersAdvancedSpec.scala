package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.combinator.MacroParsers._
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class MacroParsersAdvancedSpec extends AnyFunSpec with Diagrams {
  describe("MacroParsers advanced diagnostics") {
    it("supports cut to prevent fallback branch") {
      val withCut = ("a".s ~ "b".s.cut) / ("a".s ~ "c".s)
      val withoutCut = ("a".s ~ "b".s) / ("a".s ~ "c".s)

      assert(withoutCut("ac").isInstanceOf[ParseSuccess[_]])
      assert(withCut("ac").isInstanceOf[ParseFailure])
    }

    it("supports label") {
      val parser = "a".s.label("letter-a")
      val result = parser("b").asInstanceOf[ParseFailure]
      assert(result.expected == List("letter-a"))
    }

    it("supports recover") {
      val parser = ("a".s ~ "b".s).recover("a".s ~ "c".s)
      assert(parser("ac").isInstanceOf[ParseSuccess[_]])
    }

    it("supports trace and formatted failure") {
      val parser = ("a".s ~ "b".s).trace("AB")
      val failure = parser("ax").asInstanceOf[ParseFailure]
      assert(failure.ruleStack.contains("AB"))

      val rendered = formatFailure("ax", failure)
      assert(rendered.contains("1:2"))
    }
  }
}
