package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.codegen.ParserGenerator
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class ParserGeneratorSpec extends AnyFunSpec with Diagrams {
  describe("ParserGenerator") {
    it("generates parser code for first-order grammar") {
      val grammar = Parser.parse("S = \"a\" \"b\";")
      val generated = ParserGenerator.generate(grammar)
      assert(generated.isRight)
      val code = generated.toOption.get
      assert(code.contains("object GeneratedParser"))
      assert(code.contains("lazy val r_S"))
      assert(code.contains("def parseAll"))
    }

    it("rejects grammars with macro parameters") {
      val grammar = Parser.parse("S = F(\"a\"); F(x) = x;")
      val generated = ParserGenerator.generate(grammar)
      assert(generated.isLeft)
      assert(generated.left.toOption.get.phase == DiagnosticPhase.Generation)
    }
  }
}
