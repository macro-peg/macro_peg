package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Failure, Success}
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class PureHigherOrderRubyLikeSpec extends AnyFunSpec with Diagrams {
  private val heredocLikeGrammar =
    """|
       |S = Heredoc("<<", Ident) !.;
       |
       |Heredoc(Open, Delim) =
       |  ( !(Delim (NL / !.)) (!NL .)* NL )*
       |  Delim (NL / "");
       |
       |Ident = [A-Z] [A-Z0-9_]*;
       |NL = "\n";
       |""".stripMargin

  describe("pure higher-order Ruby-like scannerless patterns") {
    it("parses heredoc-like input without external lexer state") {
      val interpreter = Interpreter.fromSource(heredocLikeGrammar, EvaluationStrategy.CallByValueSeq)
      val input =
        """<<EOS
          |hello
          |world
          |EOS
          |""".stripMargin
      assert(interpreter.evaluate(input, Symbol("S")) == Success(""))
    }

    it("rejects heredoc-like input when terminator does not match captured delimiter") {
      val interpreter = Interpreter.fromSource(heredocLikeGrammar, EvaluationStrategy.CallByValueSeq)
      val input =
        """<<EOS
          |hello
          |EOT
          |""".stripMargin
      assert(interpreter.evaluate(input, Symbol("S")) == Failure)
    }
  }
}
