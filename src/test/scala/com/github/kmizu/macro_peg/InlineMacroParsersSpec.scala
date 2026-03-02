package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.Success
import com.github.kmizu.macro_peg.InlineMacroParsers._
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class InlineMacroParsersSpec extends AnyFunSpec with Diagrams {
  describe("InlineMacroParsers") {
    it("compiles grammar at compile time and evaluates input") {
      val parser = mpeg("""S = "ab" !.;""")
      assert(parser.evaluate("ab") == Success(""))
      assert(parser.accepts("ab"))
      assert(!parser.accepts("ac"))
      assert(parser.evaluateEither("ab") == Right(Success("")))
    }

    it("supports higher-order grammar in compile-time API") {
      val parser = mpeg(
        """|
           |S = Double((x -> x x), "aa") !.;
           |Double(f: (?)->?, s: ?) = f(f(s));
           |""".stripMargin
      )
      assert(parser.accepts("aaaaaaaa"))
      assert(!parser.accepts("aaaa"))
    }

    it("fails compilation for undefined rules") {
      assertTypeError(
        """import com.github.kmizu.macro_peg.InlineMacroParsers.*
          |val p = mpeg("S = A;")
          |""".stripMargin
      )
    }

    it("requires literal grammar string") {
      assertTypeError(
        """import com.github.kmizu.macro_peg.InlineMacroParsers.*
          |val g = "S = \"a\";"
          |val p = mpeg(g)
          |""".stripMargin
      )
    }

    it("supports strategy selection for dynamic delimiter capture") {
      val parser = mpeg(
        """|
           |S = Heredoc("<<", Ident) !.;
           |Heredoc(Open, Delim) =
           |  ( !(Delim (NL / !.)) (!NL .)* NL )*
           |  Delim (NL / "");
           |Ident = [A-Z] [A-Z0-9_]*;
           |NL = "\n";
           |""".stripMargin,
        strategy = EvaluationStrategy.CallByValueSeq
      )
      val valid =
        """<<EOS
          |body
          |EOS
          |""".stripMargin
      val invalid =
        """<<EOS
          |body
          |EOT
          |""".stripMargin
      assert(parser.accepts(valid))
      assert(!parser.accepts(invalid))
    }
  }
}
