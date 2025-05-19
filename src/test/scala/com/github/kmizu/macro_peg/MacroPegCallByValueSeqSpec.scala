package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Runner.evalGrammar
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams
import com.github.kmizu.macro_peg.EvaluationResult.Success

class MacroPegCallByValueSeqSpec extends AnyFunSpec with Diagrams {
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
}

