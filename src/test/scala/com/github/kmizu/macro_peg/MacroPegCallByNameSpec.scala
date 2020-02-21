package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Failure, Success}
import com.github.kmizu.macro_peg.Runner.evalGrammar
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class MacroPegCallByNameSpec extends AnyFunSpec with Diagrams {
  describe("Macro PEG with call by name example") {
    it("palindrome") {
      val results = evalGrammar(
        """
          |S = P("") !.;
          |P(r) = "a" P("a" r) / "b" P("b" r) / r;
     """.stripMargin,
        Seq("abba", "abba","abbbba", "a"),
        EvaluationStrategy.CallByName
      )
      assertResult(Seq(Success(""), Success(""), Success(""), Failure))(results)
    }
  }
}

