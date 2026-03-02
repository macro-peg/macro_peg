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
  }
}
