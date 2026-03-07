package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class RubyLexicalPegSpec extends AnyFunSpec with Diagrams {
  private val grammarSource: String = {
    val stream = getClass.getResourceAsStream("/com/github/kmizu/macro_peg/ruby/ruby_lexical.mapeg")
    val source = scala.io.Source.fromInputStream(stream)
    try source.mkString finally source.close()
  }

  private lazy val grammar = Parser.parse(grammarSource)

  describe("Ruby lexical PEG grammar") {
    it("parses the grammar without errors") {
      assert(grammar.rules.nonEmpty)
    }

    it("contains higher-order Token macro") {
      val tokenRule = grammar.rules.find(_.name == Symbol("Token"))
      assert(tokenRule.isDefined, "Token rule should exist")
      assert(tokenRule.get.args.nonEmpty, "Token should have parameters")
    }

    it("contains higher-order Kw macro") {
      val kwRule = grammar.rules.find(_.name == Symbol("Kw"))
      assert(kwRule.isDefined, "Kw rule should exist")
      assert(kwRule.get.args.nonEmpty, "Kw should have parameters")
    }

    it("contains DigitsWithUnderscore macro") {
      val rule = grammar.rules.find(_.name == Symbol("DigitsWithUnderscore"))
      assert(rule.isDefined, "DigitsWithUnderscore rule should exist")
      assert(rule.get.args.nonEmpty, "DigitsWithUnderscore should have parameters")
    }

    it("evaluates simple identifier sequence") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      val result = interpreter.evaluate("foo bar baz", Symbol("S"))
      assert(result.isSuccess, s"should parse identifiers: $result")
    }

    it("evaluates integer literals") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      assert(interpreter.evaluate("42", Symbol("S")).isSuccess)
      assert(interpreter.evaluate("0xFF", Symbol("S")).isSuccess)
      assert(interpreter.evaluate("0b1010", Symbol("S")).isSuccess)
      assert(interpreter.evaluate("1_000_000", Symbol("S")).isSuccess)
    }

    it("evaluates float literals") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      assert(interpreter.evaluate("3.14", Symbol("S")).isSuccess)
      assert(interpreter.evaluate("1.0e10", Symbol("S")).isSuccess)
    }

    it("evaluates mixed tokens") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      assert(interpreter.evaluate("foo 42 Bar 3.14", Symbol("S")).isSuccess)
    }

    it("evaluates with comments") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      assert(interpreter.evaluate("foo # comment\nbar", Symbol("S")).isSuccess)
    }

    it("does not accept reserved words as identifiers") {
      val interpreter = Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)
      // "if" alone should fail because S expects Identifier/ConstName/IntegerLiteral/FloatLiteral
      val result = interpreter.evaluate("if", Symbol("S"))
      assert(!result.isSuccess, "reserved word 'if' should not parse as S")
    }
  }
}
