package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Failure, Success}
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class HigherOrderJsonSpec extends AnyFunSpec with Diagrams {
  private val jsonGrammar =
    """|
       |S = Sp Value !.;
       |
       |Token(p: ?) = p Sp;
       |SepBy1(Elem: ?, Sep: ?) = Elem (Sep Elem)*;
       |SepBy0(Elem: ?, Sep: ?) = SepBy1(Elem, Sep) / "";
       |
       |Value = Object / Array / String / Number / Token("true") / Token("false") / Token("null");
       |
       |Object = Token("{") SepBy0(Member, Token(",")) Token("}");
       |Member = String Token(":") Value;
       |
       |Array = Token("[") SepBy0(Value, Token(",")) Token("]");
       |
       |String = "\"" Char* "\"" Sp;
       |Char = Escape / [^\"\\\u0000-\u001f];
       |Escape = "\\" ("\"" / "\\" / "/" / "b" / "f" / "n" / "r" / "t" / Unicode);
       |Unicode = "u" Hex Hex Hex Hex;
       |Hex = [0-9a-f] / [A-F];
       |
       |Number = "-"? IntPart Frac? Exp? Sp;
       |IntPart = "0" / [1-9][0-9]*;
       |Frac = "." [0-9]+;
       |Exp = [eE] [+-]? [0-9]+;
       |
       |Sp = [ \t\r\n]*;
       |""".stripMargin

  describe("Higher-order JSON parser sample") {
    it("accepts valid JSON values") {
      val evaluator = Evaluator(Parser.parse(jsonGrammar))
      val validInputs = Seq(
        "{}",
        """{"a":1}""",
        """{"a":[1,2,3],"b":{"c":true,"d":null}}""",
        """[{"x":-12.34e+2}, false, null, "a\u0041\tb"]""",
        """  [ 1 , 2 , 3 ]  """
      )

      validInputs.foreach { input =>
        assert(evaluator.evaluate(input, Symbol("S")) == Success(""))
      }
    }

    it("rejects invalid JSON values") {
      val evaluator = Evaluator(Parser.parse(jsonGrammar))
      val invalidInputs = Seq(
        "{",
        """{"a":}""",
        """[1,2,]""",
        """{"a" 1}""",
        """{"a": tru}"""
      )

      invalidInputs.foreach { input =>
        assert(evaluator.evaluate(input, Symbol("S")) == Failure)
      }
    }
  }
}
