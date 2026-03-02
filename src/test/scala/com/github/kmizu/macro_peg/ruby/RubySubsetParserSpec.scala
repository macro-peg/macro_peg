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
  }
}
