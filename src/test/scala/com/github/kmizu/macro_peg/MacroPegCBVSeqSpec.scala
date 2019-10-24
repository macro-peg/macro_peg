package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Runner.evalGrammar
import org.scalatest.{DiagrammedAssertions, FunSpec}
import com.github.kmizu.macro_peg.EvaluationResult.Success

class MacroPegCBVSeqSpec extends FunSpec with DiagrammedAssertions {
  describe("Macro PEG with call by value seq example") {

    it("simple") {
      val results = evalGrammar(
        """
          |S = F("a", "b", "c"); F(A, B, C) = "abc";
     """.stripMargin,
        Seq("abcabc"),
        EvaluationStrategy.CallByValueSeq
      )
      assertResult(Seq(Success("")))(results)
    }

    it("xml") {
      val results = evalGrammar(
        """
          |S = F("<", [a-zA-Z_]+, ">"); F(LT, N, GT) = F("<", [a-zA-Z_]+, ">")* LT "/" N GT;
        """.stripMargin,
        Seq( "<a></a>"),
        EvaluationStrategy.CallByValueSeq
      )
      assertResult(Seq(Success("")))(results)
    }

  }
  describe("Macro PEG with call by value par example") {
    it("simple") {
      val results = evalGrammar(
        """
          |S = F("a"); F(A) = A A A;
     """.stripMargin,
        Seq("aaa"),
        EvaluationStrategy.CallByValuePar
      )
      assertResult(Seq(Success("")))(results)
    }

    it("xml") {
      val results = evalGrammar(
        """
          |S = "<" F([a-zA-Z_]+); F(N) = N ">" ("<" F([a-zA-Z_]+))* "</" N ">";
        """.stripMargin,
        Seq( "<a><b></b></a>"),
        EvaluationStrategy.CallByValuePar
      )
      assertResult(Seq(Success("")))(results)
    }
  }
}

