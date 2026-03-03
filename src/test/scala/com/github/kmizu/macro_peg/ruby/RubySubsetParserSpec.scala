package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.ruby.RubyAst._
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class RubySubsetParserSpec extends AnyFunSpec with Diagrams {
  describe("RubySubsetParser") {
    it("parses calls and binary expressions into AST") {
      val input = "foo(1, 2).bar(3); x = 1 + 2 * 3"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(Call(None, "foo", List(IntLiteral(1), IntLiteral(2)))),
              "bar",
              List(IntLiteral(3))
            )
          ),
          Assign(
            "x",
            BinaryOp(
              IntLiteral(1),
              "+",
              BinaryOp(IntLiteral(2), "*", IntLiteral(3))
            )
          )
        ))
      )
    }

    it("parses class/def structure and nested statements") {
      val input = "class User; def greet(name); \"hi\"; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ClassDef(
            "User",
            List(
              Def(
                "greet",
                List("name"),
                List(ExprStmt(StringLiteral("hi")))
              )
            )
          )
        ))
      )
    }

    it("parses array and hash literals") {
      val input = "x = [1, 2, {\"a\" => 3}]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            ArrayLiteral(List(
              IntLiteral(1),
              IntLiteral(2),
              HashLiteral(List(StringLiteral("a") -> IntLiteral(3)))
            ))
          )
        ))
      )
    }

    it("returns parse failure for broken syntax") {
      val input = "def greet(; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isLeft)
    }

    it("parses module with if/unless and symbols") {
      val input = "module M; if flag; :ok; else; :ng; end; unless done; x = :wait; else; x = :done; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ModuleDef(
            "M",
            List(
              IfExpr(
                LocalVar("flag"),
                List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
                List(ExprStmt(SymbolLiteral("ng", UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnlessExpr(
                LocalVar("done"),
                List(Assign("x", SymbolLiteral("wait", UnknownSpan), UnknownSpan)),
                List(Assign("x", SymbolLiteral("done", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("supports RubyFullParser entrypoint") {
      val input = "x = :\"hello\""
      val parsed = RubyFullParser.parse(input)
      assert(parsed == Right(Program(List(Assign("x", SymbolLiteral("hello", UnknownSpan), UnknownSpan)), UnknownSpan)))
    }

    it("parses if with elsif chain") {
      val input = "if a; :x; elsif b; :y; else; :z; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("a"),
            List(ExprStmt(SymbolLiteral("x", UnknownSpan), UnknownSpan)),
            List(
              IfExpr(
                LocalVar("b"),
                List(ExprStmt(SymbolLiteral("y", UnknownSpan), UnknownSpan)),
                List(ExprStmt(SymbolLiteral("z", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses postfix if/unless modifiers") {
      val input = "x = 1 if ready; y = 2 unless done"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(LocalVar("ready"), List(Assign("x", IntLiteral(1), UnknownSpan)), Nil, UnknownSpan),
          UnlessExpr(LocalVar("done"), List(Assign("y", IntLiteral(2), UnknownSpan)), Nil, UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses command-style calls without parentheses") {
      val input = "puts :ok; add 1, 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "puts", List(SymbolLiteral("ok", UnknownSpan)), UnknownSpan),
            UnknownSpan
          ),
          ExprStmt(
            Call(None, "add", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call with postfix modifier") {
      val input = "log 1, 2 if ready"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("ready"),
            List(ExprStmt(Call(None, "log", List(IntLiteral(1), IntLiteral(2)), UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call inside def body") {
      val input = "def greet(name); puts name; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "greet",
            List("name"),
            List(
              ExprStmt(Call(None, "puts", List(LocalVar("name")), UnknownSpan), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver command-style calls without parentheses") {
      val input = "logger.info 1, 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(Some(LocalVar("logger")), "info", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses do-end block attached to call") {
      val input = "items.each do |x| puts x; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(LocalVar("items")), "each", Nil, UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses brace block attached to call") {
      val input = "add(1, 2) { |x| puts x }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(None, "add", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses newline-separated statements") {
      val input =
        """x = 1
          |y = 2""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", IntLiteral(1), UnknownSpan),
          Assign("y", IntLiteral(2), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses receiver parenthesized call with multiline do-end block") {
      val input =
        """items.each(1) do |x|
          |  puts x
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(LocalVar("items")), "each", List(IntLiteral(1)), UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses multiline if/else without semicolons") {
      val input =
        """if ready
          |  x = 1
          |else
          |  x = 2
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("ready"),
            List(Assign("x", IntLiteral(1), UnknownSpan)),
            List(Assign("x", IntLiteral(2), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses chained dot calls with no-arg methods in expressions") {
      val input = "x = user.profile.name"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(
                Call(
                  Some(LocalVar("user")),
                  "profile",
                  Nil,
                  UnknownSpan
                )
              ),
              "name",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses block attached to chained call expression") {
      val input = "foo.bar(1).baz do |x| puts x; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(
                Some(Call(Some(LocalVar("foo")), "bar", List(IntLiteral(1)), UnknownSpan)),
                "baz",
                Nil,
                UnknownSpan
              ),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses safe-navigation call with operator method name") {
      val input = "x = timeout&.*(timeout_scale)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(LocalVar("timeout", UnknownSpan)),
              "*",
              List(LocalVar("timeout_scale", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses return statement in method body") {
      val input = "def f; return 1; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def("f", Nil, List(Return(Some(IntLiteral(1)), UnknownSpan)), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses bare return with postfix modifier") {
      val input = "return if done"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("done"),
            List(Return(None, UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses self expression and self receiver command call") {
      val input = "x = self.name; self.log 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(SelfExpr(UnknownSpan)), "name", Nil, UnknownSpan),
            UnknownSpan
          ),
          ExprStmt(
            Call(Some(SelfExpr(UnknownSpan)), "log", List(IntLiteral(1)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses constant path expressions") {
      val input = "x = JSON::Parser"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", ConstRef(List("JSON", "Parser"), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses module/class names with constant paths") {
      val input = "module A::B; class C::D; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ModuleDef(
            "A::B",
            List(ClassDef("C::D", Nil, UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses single-quoted string literals") {
      val input = "msg = 'ok'"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("msg", StringLiteral("ok"), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses percent-quoted string literals") {
      val input = "assert_equal %q{ok}, %{ng}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("ok"), StringLiteral("ng")), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bracket index access expression") {
      val input = "x = ENV[\"HOME\"]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(ConstRef(List("ENV"), UnknownSpan)), "[]", List(StringLiteral("HOME")), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses class definition with superclass") {
      val input = "class Child < Parent; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ClassDef(
            "Child",
            Nil,
            UnknownSpan,
            Some(ConstRef(List("Parent"), UnknownSpan))
          )
        ), UnknownSpan)
      )
    }

    it("parses begin-rescue-ensure blocks") {
      val input =
        """begin
          |  x = 1
          |rescue Error => e
          |  x = 2
          |ensure
          |  x = 3
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          BeginRescue(
            List(Assign("x", IntLiteral(1), UnknownSpan)),
            List(
              RescueClause(
                List(ConstRef(List("Error"), UnknownSpan)),
                Some("e"),
                List(Assign("x", IntLiteral(2), UnknownSpan)),
                UnknownSpan
              )
            ),
            Nil,
            List(Assign("x", IntLiteral(3), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses singleton class definition") {
      val input =
        """class << self
          |  def x; 1; end
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          SingletonClassDef(
            SelfExpr(UnknownSpan),
            List(
              Def("x", Nil, List(ExprStmt(IntLiteral(1), UnknownSpan)), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses instance/class/global variables and assignments") {
      val input =
        """@x = 1
          |@@y = 2
          |$z = 3
          |w = @x + @@y
          |v = $z""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("@x", IntLiteral(1), UnknownSpan),
          Assign("@@y", IntLiteral(2), UnknownSpan),
          Assign("$z", IntLiteral(3), UnknownSpan),
          Assign("w", BinaryOp(InstanceVar("@x", UnknownSpan), "+", ClassVar("@@y", UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("v", GlobalVar("$z", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses for-in loop with range and compound assignment") {
      val input =
        """sum = 0
          |for x in (1..5)
          |  sum += x
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("sum", IntLiteral(0), UnknownSpan),
          ForIn(
            "x",
            RangeExpr(IntLiteral(1), IntLiteral(5), exclusive = false, UnknownSpan),
            List(
              Assign("sum", BinaryOp(LocalVar("sum", UnknownSpan), "+", LocalVar("x", UnknownSpan), UnknownSpan), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses begin-rescue with retry") {
      val input =
        """begin
          |  require 'tmpdir'
          |rescue LoadError
          |  retry
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          BeginRescue(
            List(ExprStmt(Call(None, "require", List(StringLiteral("tmpdir")), UnknownSpan), UnknownSpan)),
            List(
              RescueClause(
                List(ConstRef(List("LoadError"), UnknownSpan)),
                None,
                List(Retry(UnknownSpan)),
                UnknownSpan
              )
            ),
            Nil,
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses no-arg bare call with block chained by method calls") {
      val input = "lambda{ 1 }.call.call"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(
                Call(
                  Some(
                    CallWithBlock(
                      LocalVar("lambda", UnknownSpan),
                      Block(Nil, List(ExprStmt(IntLiteral(1), UnknownSpan)), UnknownSpan),
                      UnknownSpan
                    )
                  ),
                  "call",
                  Nil,
                  UnknownSpan
                )
              ),
              "call",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses block-pass parameter and argument") {
      val input = "def each(&block); [1, 2].each(&block); end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "each",
            List("&block"),
            List(
              ExprStmt(
                Call(
                  Some(ArrayLiteral(List(IntLiteral(1), IntLiteral(2)), UnknownSpan)),
                  "each",
                  List(LocalVar("&block", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses method names with punctuation and unary/binary operators") {
      val input = "if !Dir.respond_to?(:mktmpdir) || force; ok! 1; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            BinaryOp(
              UnaryOp(
                "!",
                Call(
                  Some(ConstRef(List("Dir"), UnknownSpan)),
                  "respond_to?",
                  List(SymbolLiteral("mktmpdir", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              ),
              "||",
              LocalVar("force", UnknownSpan),
              UnknownSpan
            ),
            List(ExprStmt(Call(None, "ok!", List(IntLiteral(1)), UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bare no-arg punctuated call in condition") {
      val input = "if block_given?; :ok; else; :ng; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            Call(None, "block_given?", Nil, UnknownSpan),
            List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
            List(ExprStmt(SymbolLiteral("ng", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver attribute assignment as expression statement") {
      val input = "self.columns = indent"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(Some(SelfExpr(UnknownSpan)), "columns=", List(LocalVar("indent", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver logical assignment inside condition") {
      val input = "if (self.columns ||= 0) < n; :ok; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            BinaryOp(
              BinaryOp(
                Call(Some(SelfExpr(UnknownSpan)), "columns", Nil, UnknownSpan),
                "||",
                IntLiteral(0, UnknownSpan),
                UnknownSpan
              ),
              "<",
              LocalVar("n", UnknownSpan),
              UnknownSpan
            ),
            List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver compound assignment") {
      val input = "self.columns += 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(SelfExpr(UnknownSpan)),
              "columns=",
              List(
                BinaryOp(
                  Call(Some(SelfExpr(UnknownSpan)), "columns", Nil, UnknownSpan),
                  "+",
                  IntLiteral(1, UnknownSpan),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses variable minus compound assignment") {
      val input = "w -= 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "w",
            BinaryOp(LocalVar("w", UnknownSpan), "-", IntLiteral(1, UnknownSpan), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses ternary operator in call arguments") {
      val input = "x = Test::JobServer.max_jobs(wn > 0 ? wn : 1024)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(ConstRef(List("Test", "JobServer"), UnknownSpan)),
              "max_jobs",
              List(
                IfExpr(
                  BinaryOp(LocalVar("wn", UnknownSpan), ">", IntLiteral(0, UnknownSpan), UnknownSpan),
                  List(ExprStmt(LocalVar("wn", UnknownSpan), UnknownSpan)),
                  List(ExprStmt(IntLiteral(1024, UnknownSpan), UnknownSpan)),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses def name ending with question mark") {
      val input = "def empty?; true; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def("empty?", Nil, List(ExprStmt(BoolLiteral(true, UnknownSpan), UnknownSpan)), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses nested percent-q parentheses strings") {
      val input = "x = %q( Object.const_defined?(:C) )"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", StringLiteral(" Object.const_defined?(:C) "), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses regular expression literal in call arguments") {
      val input = "assert_match /\\\\Aok\\\\z/, value"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_match", List(StringLiteral("\\\\Aok\\\\z"), LocalVar("value", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses symbol literals with variable-like names") {
      val input = "trace_var(:$a); trace_var(:@x); trace_var(:@@y)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("$a", UnknownSpan)), UnknownSpan), UnknownSpan),
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("@x", UnknownSpan)), UnknownSpan), UnknownSpan),
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("@@y", UnknownSpan)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses uppercase percent-Q strings") {
      val input = "message = %Q{ENSURE\\n}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("message", StringLiteral("ENSURE\\n"), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses percent word-array literals") {
      val input = "keywords = %w[break next redo]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "keywords",
            ArrayLiteral(List(
              StringLiteral("break"),
              StringLiteral("next"),
              StringLiteral("redo")
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses regex match operators") {
      val input = "/freebsd/ =~ RUBY_PLATFORM or skip"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            BinaryOp(
              BinaryOp(
                StringLiteral("freebsd", UnknownSpan),
                "=~",
                ConstRef(List("RUBY_PLATFORM"), UnknownSpan),
                UnknownSpan
              ),
              "or",
              LocalVar("skip", UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses =begin/=end block comments as spacing") {
      val input =
        """=begin
          |generated
          |=end
          |ok 1
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "ok", List(IntLiteral(1)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses percent-r regex literals in command calls") {
      val input = """assert_match %r"Invalid #{keyword}\\b", value"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_match",
              List(StringLiteral("Invalid #{keyword}\\\\b"), LocalVar("value", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses multiline or with command-call rhs") {
      val input =
        """/freebsd/ =~ RUBY_PLATFORM or
          |assert_finish 5, %q{ok}
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            BinaryOp(
              BinaryOp(
                StringLiteral("freebsd", UnknownSpan),
                "=~",
                ConstRef(List("RUBY_PLATFORM"), UnknownSpan),
                UnknownSpan
              ),
              "or",
              Call(None, "assert_finish", List(IntLiteral(5), StringLiteral("ok", UnknownSpan)), UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses label-style hash entries") {
      val input = "opts = { frozen_string_literal: true, mode: :strict }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "opts",
            HashLiteral(List(
              SymbolLiteral("frozen_string_literal", UnknownSpan) -> BoolLiteral(true, UnknownSpan),
              SymbolLiteral("mode", UnknownSpan) -> SymbolLiteral("strict", UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses call arguments with keyword labels") {
      val input = """Prism.parse("1", command_line: "p", line: 4)"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(ConstRef(List("Prism"), UnknownSpan)),
              "parse",
              List(
                StringLiteral("1", UnknownSpan),
                HashLiteral(List(SymbolLiteral("command_line", UnknownSpan) -> StringLiteral("p", UnknownSpan)), UnknownSpan),
                HashLiteral(List(SymbolLiteral("line", UnknownSpan) -> IntLiteral(4, UnknownSpan)), UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses squiggly heredoc argument with trailing args") {
      val input =
        """assert_equal "false", <<~RUBY, "literal strings are mutable", "--disable-frozen-string-literal"
          |  eval("'test'").frozen?
          |RUBY
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_equal",
              List(
                StringLiteral("false", UnknownSpan),
                StringLiteral("  eval(\"'test'\").frozen?\n", UnknownSpan),
                StringLiteral("literal strings are mutable", UnknownSpan),
                StringLiteral("--disable-frozen-string-literal", UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses unary plus and minus operators") {
      val input = "line = -2; value = +line"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("line", UnaryOp("-", IntLiteral(2, UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("value", UnaryOp("+", LocalVar("line", UnknownSpan), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses shift operators and postfix modifier on expression statements") {
      val input = "path << \"b\" if n; bits = value >> 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("n", UnknownSpan),
            List(
              ExprStmt(
                BinaryOp(LocalVar("path", UnknownSpan), "<<", StringLiteral("b", UnknownSpan), UnknownSpan),
                UnknownSpan
              )
            ),
            Nil,
            UnknownSpan
          ),
          Assign(
            "bits",
            BinaryOp(LocalVar("value", UnknownSpan), ">>", IntLiteral(2, UnknownSpan), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses while with assignment expression condition") {
      val input = "while (node = queue.shift); return node if node; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          WhileExpr(
            AssignExpr(
              "node",
              Call(Some(LocalVar("queue", UnknownSpan)), "shift", Nil, UnknownSpan),
              UnknownSpan
            ),
            List(
              IfExpr(
                LocalVar("node", UnknownSpan),
                List(Return(Some(LocalVar("node", UnknownSpan)), UnknownSpan)),
                Nil,
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses symbol literals for forwarding markers") {
      val input = "scopes = [:*, :**, :&]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "scopes",
            ArrayLiteral(
              List(
                SymbolLiteral("*", UnknownSpan),
                SymbolLiteral("**", UnknownSpan),
                SymbolLiteral("&", UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses script body after -x style shell preamble") {
      val input =
        """"exec" "${RUBY-ruby}" "-x" "$0" "$@" || true # -*- Ruby -*-
          |#!./ruby
          |ok 1
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "ok", List(IntLiteral(1, UnknownSpan)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses array literal entries split across newlines") {
      val input =
        """pairs = [
          |  ["a", 1],
          |  ["b", 2]
          |]
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "pairs",
            ArrayLiteral(List(
              ArrayLiteral(List(StringLiteral("a", UnknownSpan), IntLiteral(1, UnknownSpan)), UnknownSpan),
              ArrayLiteral(List(StringLiteral("b", UnknownSpan), IntLiteral(2, UnknownSpan)), UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses parenthesized call args split across newlines") {
      val input =
        """assert_equal(
          |  "x",
          |  "y"
          |)
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("x", UnknownSpan), StringLiteral("y", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses singleton def with default params and ||= assignment") {
      val input =
        """def Dir.mktmpdir(prefix_suffix=nil, tmpdir=nil)
          |  tmpdir ||= Dir.tmpdir
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "Dir.mktmpdir",
            List("prefix_suffix", "tmpdir"),
            List(
              Assign(
                "tmpdir",
                BinaryOp(
                  LocalVar("tmpdir", UnknownSpan),
                  "||",
                  Call(Some(ConstRef(List("Dir"), UnknownSpan)), "tmpdir", Nil, UnknownSpan),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses case when else expression") {
      val input =
        """case prefix_suffix
          |when nil
          |  prefix = "d"
          |when String
          |  prefix = prefix_suffix
          |else
          |  prefix = "x"
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          CaseExpr(
            Some(LocalVar("prefix_suffix", UnknownSpan)),
            List(
              WhenClause(
                List(NilLiteral(UnknownSpan)),
                List(Assign("prefix", StringLiteral("d", UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              WhenClause(
                List(ConstRef(List("String"), UnknownSpan)),
                List(Assign("prefix", LocalVar("prefix_suffix", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            List(Assign("prefix", StringLiteral("x", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }
  }
}
