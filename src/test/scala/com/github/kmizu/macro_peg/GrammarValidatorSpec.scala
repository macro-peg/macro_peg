package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class GrammarValidatorSpec extends AnyFunSpec with Diagrams {
  describe("GrammarValidator") {
    it("rejects undefined references") {
      val grammar = Parser.parse("S = A;")
      val result = GrammarValidator.validate(grammar)
      assert(result.isLeft)
      assert(result.left.toOption.get.message.contains("undefined"))
    }

    it("rejects nullable repetition") {
      val grammar = Parser.parse("S = (\"\")*;")
      val result = GrammarValidator.validate(grammar)
      assert(result.isLeft)
      assert(result.left.toOption.get.message.contains("nullable"))
    }

    it("rejects left recursion") {
      val grammar = Parser.parse("S = S \"a\" / \"a\";")
      val result = GrammarValidator.validate(grammar)
      assert(result.isLeft)
      assert(result.left.toOption.get.message.contains("left recursion"))
    }

    it("accepts well-formed grammar") {
      val grammar = Parser.parse("S = \"a\"+ !.;")
      val result = GrammarValidator.validate(grammar)
      assert(result.isRight)
    }
  }
}
