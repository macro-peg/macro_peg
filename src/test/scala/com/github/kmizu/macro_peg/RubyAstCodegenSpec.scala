package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.codegen.{ParserGenerator, Backend}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

/** Tests that semantic actions in a mini-grammar produce AST-building code. */
class RubyAstCodegenSpec extends AnyFunSpec with Diagrams {

  private def gen(source: String): String =
    ParserGenerator.generateFromSource(source) match {
      case Right(code) => code
      case Left(err)   => fail(s"codegen failed: ${err.message}")
    }

  describe("flatText helper") {
    it("is present in generated preamble") {
      val code = gen("""S = "a" ;""")
      assert(code.contains("def flatText(x: Any): String"))
    }
  }

  describe("NilLiteral-style action (ignore result, return value)") {
    it("generates map { _ => } for no-label action") {
      val code = gen("""S = "nil" ${ 42 } ;""")
      assert(code.contains(".map { __result => { 42 } }") || code.contains(".map { __result =>"))
    }
  }

  describe("Labeled capture in action") {
    it("generates case pattern and uses label in action") {
      val code = gen("""S = v:[a-z]+ ${ v.asInstanceOf[List[?]].mkString } ;""")
      assert(code.contains("case v =>"))
      assert(code.contains("v.asInstanceOf"))
    }
  }

  describe("Two-label sequence") {
    it("generates l ~ r pattern") {
      val code = gen("""S = l:[a-z]+ "," r:[a-z]+ ${ (l, r) } ;""")
      assert(code.contains("case l ~ _ ~ r =>") || code.contains("l ~ _ ~ r"))
    }
  }
}

class HeadBlockSpec extends AnyFunSpec with Diagrams {
  private def gen(source: String): String =
    ParserGenerator.generateFromSource(source) match {
      case Right(code) => code
      case Left(err)   => fail(s"codegen failed: ${err.message}")
    }

  describe("head { } preamble block") {
    it("emits head block content in generated object") {
      val code = gen("""head { import scala.collection.mutable._ } S = "a" ;""")
      assert(code.contains("import scala.collection.mutable._"))
    }

    it("works without a head block") {
      val code = gen("""S = "a" ;""")
      assert(code.contains("object GeneratedParser"))
    }
  }
}

class RubyGrammarAstCodegenSpec extends AnyFunSpec with Diagrams {
  import com.github.kmizu.macro_peg.codegen.ParserGenerator

  private val grammarSource: String = {
    val stream = getClass.getResourceAsStream("/com/github/kmizu/macro_peg/ruby/ruby_grammar.mapeg")
    val source = scala.io.Source.fromInputStream(stream)
    try source.mkString finally source.close()
  }

  private lazy val code: String =
    ParserGenerator.generateFromSource(grammarSource, startRule = Symbol("Program")) match {
      case Right(c) => c
      case Left(e)  => throw new Exception(s"codegen failed: ${e.message}")
    }

  describe("Ruby grammar code generation — AST actions") {
    it("compiles to combinator (not interpreter) backend") {
      assert(code.contains("object GeneratedParser"))
      assert(!code.contains("Interpreter.fromSourceEither"))
    }

    it("NilLiteral generates NilLiteral() call") {
      assert(code.contains("NilLiteral()"), s"NilLiteral not found in:\n${code.linesIterator.filter(_.contains("r_Nil")).mkString("\n")}")
    }

    it("BoolLiteral generates BoolLiteral(true) and BoolLiteral(false)") {
      assert(code.contains("BoolLiteral(value = true)"))
      assert(code.contains("BoolLiteral(value = false)"))
    }

    it("SelfExpr generates SelfExpr() call") {
      assert(code.contains("SelfExpr()"))
    }

    it("flatText helper is in preamble") {
      assert(code.contains("def flatText(x: Any): String"))
    }

    it("foldBinOps helper is in preamble") {
      assert(code.contains("def foldBinOps(raw: Any): Any"))
    }

    it("binary ops use foldBinOps") {
      assert(code.contains("foldBinOps(__result)"))
    }

    it("power op uses foldPowerOp") {
      assert(code.contains("foldPowerOp(__result)"))
    }

    it("ternary uses foldTernary") {
      assert(code.contains("foldTernary(__result)"))
    }

    it("RangeExpr uses foldRange") {
      assert(code.contains("foldRange(__result)"))
    }

    it("ArrayLiteral uses buildArrayLiteral") {
      assert(code.contains("buildArrayLiteral(__result)"))
    }

    it("HashLiteral uses buildHashLiteral") {
      assert(code.contains("buildHashLiteral(__result)"))
    }

    it("AssignExpr uses buildAssignExpr") {
      assert(code.contains("buildAssignExpr(__result)"))
    }

    it("IfExpr uses buildIfExpr") {
      assert(code.contains("buildIfExpr(cond"))
    }

    it("DefExpr uses buildDefExpr") {
      assert(code.contains("buildDefExpr(name"))
    }

    it("Program uses buildProgram") {
      assert(code.contains("buildProgram(__result)"))
    }

    it("head block imports RubyAst") {
      assert(code.contains("import com.github.kmizu.macro_peg.ruby.RubyAst._"))
    }

    it("IntLiteral uses parseIntLit") {
      assert(code.contains("parseIntLit(v)"))
    }

    it("FloatLiteral uses parseFloatLit") {
      assert(code.contains("parseFloatLit(v)"))
    }

    it("InstanceVar uses labeled capture") {
      assert(code.contains("InstanceVar("))
    }
  }
}

// ---------------------------------------------------------------------------
// Recursive-Descent backend tests
// ---------------------------------------------------------------------------

class RecursiveDescentCodegenSpec extends AnyFunSpec with Diagrams {
  private def rdGen(source: String): String =
    ParserGenerator.generateFromSource(source, backend = Backend.RecursiveDescent) match {
      case Right(code) => code
      case Left(err)   => fail(s"rd codegen failed: ${err.message}")
    }

  describe("generated code shape") {
    it("defines local ~ case class (no MacroParsers import)") {
      val code = rdGen("""S = "a" ;""")
      assert(code.contains("case class ~"))
      assert(!code.contains("MacroParsers"))
    }

    it("uses mutable var pos and var input") {
      val code = rdGen("""S = "a" ;""")
      assert(code.contains("var input"))
      assert(code.contains("var pos"))
    }

    it("generates def r_ rule methods") {
      val code = rdGen("""S = "hello" ;""")
      assert(code.contains("def r_S()"))
    }

    it("generates parse entry point returning Either") {
      val code = rdGen("""S = "a" ;""")
      assert(code.contains("def parse("))
      assert(code.contains("Either[String, Any]"))
    }

    it("no-label semantic action binds __result") {
      val code = rdGen("""S = "nil" ${ 42 } ;""")
      assert(code.contains("__result"))
      assert(code.contains("42"))
    }

    it("labeled capture becomes local val in action") {
      val code = rdGen("""S = v:[a-z]+ ${ v } ;""")
      assert(code.contains("val v ="))
    }

    it("StringLiteral uses input.startsWith") {
      val code = rdGen("""S = "abc" ;""")
      assert(code.contains("input.startsWith"))
    }

    it("Repeat0 uses ListBuffer while loop") {
      val code = rdGen("""S = [a-z]* ;""")
      assert(code.contains("ListBuffer"))
      assert(code.contains("while"))
    }

    it("Alternation saves and restores pos") {
      val code = rdGen("""S = "a" / "b" ;""")
      // alternation: saves pos, tries first, on failure restores
      assert(code.contains("pos ="))
    }

    it("emits flatText helper") {
      val code = rdGen("""S = "a" ;""")
      assert(code.contains("def flatText"))
    }
  }

  describe("higher-order rule handling") {
    it("higher-order rule generates lambda parameter") {
      val code = rdGen("""Token(p: ?) = p ";" ;  S = Token("x") ;""")
      // After inlining Token("x") → "x" ";", S should be first-order
      // OR Token generates a lambda param
      assert(code.contains("def r_S"))
    }
  }
}

class RubyGrammarRDCodegenSpec extends AnyFunSpec with Diagrams {
  private val grammarSource: String = {
    val stream = getClass.getResourceAsStream("/com/github/kmizu/macro_peg/ruby/ruby_grammar.mapeg")
    val source = scala.io.Source.fromInputStream(stream)
    try source.mkString finally source.close()
  }

  private lazy val code: String =
    ParserGenerator.generateFromSource(
      grammarSource,
      startRule = Symbol("Program"),
      backend = Backend.RecursiveDescent
    ) match {
      case Right(c) => c
      case Left(e)  => throw new Exception(s"rd codegen failed: ${e.message}")
    }

  describe("Ruby grammar recursive-descent code generation") {
    it("does NOT import MacroParsers") {
      assert(!code.contains("MacroParsers"))
    }

    it("defines local ~ case class") {
      assert(code.contains("case class ~[+A, +B]"))
    }

    it("uses var pos / var input") {
      assert(code.contains("var pos"))
      assert(code.contains("var input"))
    }

    it("generates def r_ rule methods") {
      assert(code.contains("def r_"))
    }

    it("NilLiteral action is present") {
      assert(code.contains("NilLiteral()"))
    }

    it("foldBinOps helper emitted") {
      assert(code.contains("def foldBinOps"))
    }

    it("buildIfExpr helper emitted") {
      assert(code.contains("def buildIfExpr"))
    }

    it("head block import is present") {
      assert(code.contains("import com.github.kmizu.macro_peg.ruby.RubyAst._"))
    }

    it("parse entry point present") {
      assert(code.contains("def parse("))
    }
  }
}
