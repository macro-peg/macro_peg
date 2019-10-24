package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.MacroPEGRunner.tryGrammar
import com.github.kmizu.macro_peg.combinator.MacroParsers._
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{DiagrammedAssertions, FunSpec}

import MacroPEGEvaluator._

class MacroPegCBVSeqSpec extends FunSpec with DiagrammedAssertions with GeneratorDrivenPropertyChecks {
  describe("Macro PEG with call by value seq example") {

    it("simple") {
      val results = tryGrammar(
        "abcabc",
        """
          |S = F("a", "b", "c"); F(A, B, C) = "abc";
     """.stripMargin,
        EvaluationStrategy.CallByValueSeq,
        "abcabc"
      )
      assertResult(Seq(Success("")))(results)
    }

    it("xml") {
      val results = tryGrammar(
        "xml",
        """
          |S = F("<", [a-zA-Z_]+, ">"); F(LT, N, GT) = F("<", [a-zA-Z_]+, ">")* LT "/" N GT;
     """.stripMargin,
        EvaluationStrategy.CallByValueSeq,
        "<a></a>"
      )
      assertResult(Seq(Success("")))(results)
    }

  }
}

