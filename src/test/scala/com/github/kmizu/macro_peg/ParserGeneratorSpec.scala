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

    it("inlines simple macro and generates combinator code") {
      // F(x) = x is a trivial passthrough macro; inlining gives S = "a"
      val grammar = Parser.parse("S = F(\"a\"); F(x) = x;")
      val generated = ParserGenerator.generate(grammar)
      assert(generated.isRight)
      val code = generated.toOption.get
      assert(code.contains("object GeneratedParser"))
      assert(code.contains("lazy val r_S"))
      assert(code.contains("def parseAll"))
    }

    it("generates code where :ign removes element from ~ tuple (right side ignored)") {
      // "hello":ign "world" — action sees only "world", not ("hello" ~ "world")
      val source = """
%object TestIgn;
%start foo;
foo = "hello":ign "world" => { w => w } ;
"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight, result.left.toOption.getOrElse(""))
      val code = result.toOption.get
      // Should use projection (map with _ for ignored side), NOT new ~
      assert(!code.contains("new ~(_r"), "ignored element should not appear in ~ pair")
    }

    it("generates code where :ign removes element from ~ tuple (left side ignored)") {
      // "open":ign content "close":ign — action sees only content
      val source = """
%object TestIgn2;
%start bar;
bar = "(" :ign [a-z]+ ")" :ign => { cs => cs } ;
"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight, result.left.toOption.getOrElse(""))
    }

    it("inlines lambda-style higher-order grammar to combinator code") {
      // Double((x -> x x), "aa") fully beta-reduces at compile time
      val source = """|
        |S = Double((x -> x x), "aa") !.;
        |Double(f: ?, s: ?) = f(f(s));
        |""".stripMargin
      val generated = ParserGenerator.generateFromSource(source)
      assert(generated.isRight)
      val code = generated.toOption.get
      assert(code.contains("object GeneratedParser"))
      assert(code.contains("lazy val r_S"))
      assert(code.contains("def parseAll"))
    }

    it("falls back to interpreter for recursive higher-order grammar") {
      // F(x) = "b" F(x "c") grows unboundedly — MacroExpander would loop
      val source = """|
        |S = F("a");
        |F(x: ?) = "b" F(x "c");
        |""".stripMargin
      val generated = ParserGenerator.generateFromSource(source)
      assert(generated.isRight)
      val code = generated.toOption.get
      assert(code.contains("Interpreter.fromSourceEither"))
      assert(code.contains("strategy: EvaluationStrategy"))
    }

    it("inlines Token-style higher-order macros to fast combinator code") {
      val source = """|
        |S = Id*;
        |Id = Token(IdRaw);
        |Token(p: ?) = p Sp;
        |IdRaw = [a-z]+;
        |Sp = " "*;
        |""".stripMargin
      val generated = ParserGenerator.generateFromSource(source)
      assert(generated.isRight)
      val code = generated.toOption.get
      assert(code.contains("object GeneratedParser"))
      // Token macro was inlined — no separate def for it
      assert(!code.contains("def r_Token"))
      assert(code.contains("def parseAll"))
    }
  }
}

// Note: appended below the existing describe block — wrap in separate describe
class ParserGeneratorSemanticActionSpec extends org.scalatest.funspec.AnyFunSpec with org.scalatest.diagrams.Diagrams {
  import com.github.kmizu.macro_peg.codegen.ParserGenerator

  describe("ParserGenerator — semantic actions") {
    it("generates .map with no labels") {
      val source = """S = "a" "b" ${ "ok" } ;"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight)
      val code = result.toOption.get
      assert(code.contains(".map { __result => {") && code.contains("\"ok\""), s"got:\n$code")
    }

    it("generates .map { case l ~ r => } with labels") {
      val source = """S = l:"a" r:"b" ${ l.toString + r.toString } ;"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight)
      val code = result.toOption.get
      assert(code.contains("case l ~ r =>"), s"got:\n$code")
    }

    it("generates correct pattern for mixed labeled/unlabeled") {
      val source = """S = l:"a" "+" r:"b" ${ l.toString + r.toString } ;"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight)
      val code = result.toOption.get
      // Should have l ~ _ ~ r pattern
      assert(code.contains("case l ~ _ ~ r =>"), s"got:\n$code")
    }

    it("generates correct pattern for single label") {
      val source = """S = v:[a-z]+ ${ v.mkString } ;"""
      val result = ParserGenerator.generateFromSource(source)
      assert(result.isRight)
      val code = result.toOption.get
      assert(code.contains("case v =>"), s"got:\n$code")
    }
  }
}
