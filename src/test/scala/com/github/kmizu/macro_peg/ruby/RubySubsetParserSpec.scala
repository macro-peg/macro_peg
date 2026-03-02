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
  }
}
