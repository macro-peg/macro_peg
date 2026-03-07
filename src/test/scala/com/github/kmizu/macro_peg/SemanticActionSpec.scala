package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class SemanticActionSpec extends AnyFunSpec with Diagrams {
  describe("Macro PEG with semantic actions") {
    it("parses rule with action block") {
      val grammar = Parser.parse("""S = "a" "b" { lhs + rhs } ;""")
      assert(grammar.rules.size == 1)
      val body = grammar.rules.head.body
      // Body should be Sequence(Sequence("a", "b"), SemanticAction("..."))
      assert(body.isInstanceOf[Ast.Sequence])
    }

    it("parses rule without action block") {
      val grammar = Parser.parse("""S = "a" "b" ;""")
      assert(grammar.rules.size == 1)
      val body = grammar.rules.head.body
      assert(body.isInstanceOf[Ast.Sequence])
      // No SemanticAction in the tree
      body match {
        case Ast.Sequence(_, _, rhs) => assert(!rhs.isInstanceOf[Ast.SemanticAction])
        case _ => fail("expected Sequence")
      }
    }

    it("parses action with nested braces") {
      val grammar = Parser.parse("""S = "x" { if (true) { 1 } else { 2 } } ;""")
      val body = grammar.rules.head.body
      body match {
        case Ast.Sequence(_, _, Ast.SemanticAction(_, code)) =>
          assert(code.contains("if"))
          assert(code.contains("{"))
          assert(code.contains("}"))
        case _ => fail(s"expected Sequence with SemanticAction, got $body")
      }
    }

    it("parses labeled expression") {
      val grammar = Parser.parse("""S = h:[a-z] t:[a-z]* { h + t.mkString } ;""")
      val body = grammar.rules.head.body
      // Should contain Labeled nodes
      def containsLabeled(e: Ast.Expression): Boolean = e match {
        case Ast.Labeled(_, _, _) => true
        case Ast.Sequence(_, l, r) => containsLabeled(l) || containsLabeled(r)
        case _ => false
      }
      assert(containsLabeled(body), s"expected Labeled in $body")
    }

    it("extracts label names correctly") {
      val grammar = Parser.parse("""S = head:[a-z] ;""")
      val body = grammar.rules.head.body
      body match {
        case Ast.Labeled(_, "head", Ast.CharClass(_, true, _)) => // ok
        case _ => fail(s"expected Labeled(head, CharClass), got $body")
      }
    }
  }
}
