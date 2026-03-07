package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class RubyGrammarPegSpec extends AnyFunSpec with Diagrams {
  private val grammarSource: String = {
    val stream = getClass.getResourceAsStream("/com/github/kmizu/macro_peg/ruby/ruby_grammar.mapeg")
    val source = scala.io.Source.fromInputStream(stream)
    try source.mkString finally source.close()
  }

  private lazy val grammar = Parser.parse(grammarSource)

  private lazy val interpreter =
    Interpreter.fromGrammar(grammar, EvaluationStrategy.CallByName)

  private def parse(input: String): Boolean =
    interpreter.evaluate(input, Symbol("Program")).isSuccess

  describe("Ruby grammar PEG — structural recognition") {

    it("parses the grammar without errors") {
      assert(grammar.rules.nonEmpty)
    }

    it("contains higher-order Token macro") {
      val tokenRule = grammar.rules.find(_.name == Symbol("Token"))
      assert(tokenRule.isDefined)
      assert(tokenRule.get.args.nonEmpty)
    }

    // ── Literals ──────────────────────────────────────────────────────────────

    it("parses integer literals") {
      assert(parse("42"))
      assert(parse("0xFF"))
      assert(parse("0b1010"))
      assert(parse("1_000_000"))
      assert(parse("0o777"))
    }

    it("parses float literals") {
      assert(parse("3.14"))
      assert(parse("1.0e10"))
      assert(parse("1.5ri"))
    }

    it("parses string literals") {
      assert(parse(""""hello world""""))
      assert(parse("""'single quoted'"""))
      assert(parse(""""with #{interpolation}""""))
      assert(parse(""""nested #{"inner"}""""))
    }

    it("parses symbol literals") {
      assert(parse(":foo"))
      assert(parse(":bar!"))
      assert(parse(""":"quoted sym""""))
    }

    it("parses array literals") {
      assert(parse("[]"))
      assert(parse("[1, 2, 3]"))
      assert(parse("[1, *arr, 2]"))
    }

    it("parses hash literals") {
      assert(parse("{}"))
      assert(parse("{a: 1, b: 2}"))
      assert(parse("{\"key\" => value}"))
    }

    it("parses regex literals") {
      assert(parse("/hello/"))
      assert(parse("/foo|bar/ix"))
    }

    it("parses percent literals") {
      assert(parse("%w(foo bar baz)"))
      assert(parse("%q(hello world)"))
      assert(parse("""%Q{hello #{name}}"""))
    }

    it("parses char literals") {
      assert(parse("?a"))
      assert(parse("""?\n"""))
    }

    // ── Identifiers & variables ───────────────────────────────────────────────

    it("parses identifiers") {
      assert(parse("foo"))
      assert(parse("foo_bar"))
    }

    it("parses instance variables") {
      assert(parse("@foo"))
    }

    it("parses class variables") {
      assert(parse("@@counter"))
    }

    it("parses global variables") {
      assert(parse("$foo"))
      assert(parse("$0"))
    }

    it("parses constants") {
      assert(parse("Foo"))
      assert(parse("Foo::Bar::Baz"))
    }

    // ── Expressions ───────────────────────────────────────────────────────────

    it("parses arithmetic expressions") {
      assert(parse("1 + 2 * 3"))
      assert(parse("(1 + 2) * (3 - 4)"))
      assert(parse("2 ** 10"))
      assert(parse("10 / 3"))
      assert(parse("10 % 3"))
    }

    it("parses string operations") {
      assert(parse(""""hello" + " world""""))
      assert(parse(""""abc" * 3"""))
    }

    it("parses comparison expressions") {
      assert(parse("a == b"))
      assert(parse("a != b"))
      assert(parse("a < b && b > c"))
      assert(parse("x <=> y"))
      assert(parse("a === b"))
    }

    it("parses logical expressions") {
      assert(parse("a && b"))
      assert(parse("a || b"))
      assert(parse("!a"))
      assert(parse("a and b"))
      assert(parse("a or b"))
      assert(parse("not a"))
    }

    it("parses ternary expressions") {
      assert(parse("a ? b : c"))
      assert(parse("x > 0 ? \"pos\" : \"neg\""))
    }

    it("parses range expressions") {
      assert(parse("1..10"))
      assert(parse("1...10"))
      assert(parse("(a..b)"))
    }

    it("parses assignment") {
      assert(parse("x = 1"))
      assert(parse("x += 1"))
      assert(parse("x ||= default"))
      assert(parse("@foo = bar"))
      assert(parse("a, b = 1, 2"))
    }

    it("parses method calls") {
      assert(parse("foo()"))
      assert(parse("foo(1, 2, 3)"))
      assert(parse("foo(a, *b, **c)"))
      assert(parse("obj.method"))
      assert(parse("obj.method(arg)"))
      assert(parse("obj&.safe_nav"))
      assert(parse("Foo::bar"))
    }

    it("parses block syntax") {
      assert(parse("arr.each { |x| puts x }"))
      assert(parse("arr.each do |x|\n  puts x\nend"))
      assert(parse("[1,2].map { |x| x * 2 }"))
    }

    it("parses indexing") {
      assert(parse("arr[0]"))
      assert(parse("arr[1, 2]"))
      assert(parse("h[:key]"))
    }

    it("parses lambda literals") {
      assert(parse("-> { 42 }"))
      assert(parse("->(x) { x + 1 }"))
      assert(parse("->(x, y) { x + y }"))
    }

    // ── Control flow ──────────────────────────────────────────────────────────

    it("parses if/elsif/else") {
      assert(parse("if x\n  puts 1\nend"))
      assert(parse("if x\n  a\nelsif y\n  b\nelse\n  c\nend"))
    }

    it("parses unless") {
      assert(parse("unless done\n  work\nend"))
    }

    it("parses while / until") {
      assert(parse("while x > 0\n  x -= 1\nend"))
      assert(parse("until done\n  work\nend"))
    }

    it("parses for loops") {
      assert(parse("for i in 1..10\n  puts i\nend"))
    }

    it("parses case/when") {
      assert(parse("case x\nwhen 1\n  :one\nwhen 2\n  :two\nelse\n  :other\nend"))
    }

    it("parses case/in (pattern matching)") {
      assert(parse("case data\nin [a, b]\n  a + b\nin {x:}\n  x\nend"))
    }

    it("parses postfix if/unless/while") {
      assert(parse("puts x if debug"))
      assert(parse("retry unless done"))
      assert(parse("x += 1 while x < 10"))
    }

    it("parses return/next/break") {
      assert(parse("def f\n  return 42\nend"))
      assert(parse("[1,2,3].each { |x| next if x == 2; puts x }"))
      assert(parse("loop { break if done }"))
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    it("parses def statements") {
      assert(parse("def foo\n  nil\nend"))
      assert(parse("def foo(a, b)\n  a + b\nend"))
      assert(parse("def foo(a, b = 1, *c, **d, &e)\n  nil\nend"))
      assert(parse("def foo(x) = x * 2"))
    }

    it("parses class definitions") {
      assert(parse("class Foo\n  def initialize\n    @x = 1\n  end\nend"))
      assert(parse("class Bar < Foo\n  nil\nend"))
      assert(parse("class << self\n  def x\n  end\nend"))
    }

    it("parses module definitions") {
      assert(parse("module Mixin\n  def meth\n  end\nend"))
    }

    it("parses begin/rescue/ensure") {
      assert(parse("begin\n  risky\nrescue => e\n  handle e\nensure\n  cleanup\nend"))
    }

    it("parses alias") {
      assert(parse("alias :new_name :old_name"))
      assert(parse("alias new_name old_name"))
    }

    it("parses yield / super") {
      assert(parse("def f\n  yield\nend"))
      assert(parse("def f\n  super(1, 2)\nend"))
    }

    it("parses defined?") {
      assert(parse("defined?(x)"))
    }

    // ── Complex / multi-statement ─────────────────────────────────────────────

    it("parses multiple statements") {
      assert(parse("x = 1\ny = 2\nz = x + y"))
    }

    it("parses method chaining") {
      assert(parse("\"hello\".upcase.reverse.split(\"\")"))
    }

    it("parses backtick command strings") {
      assert(parse("`ls -la`"))
    }

    it("parses raise") {
      assert(parse("raise \"error\""))
      assert(parse("raise ArgumentError, \"bad arg\""))
    }
  }
}
