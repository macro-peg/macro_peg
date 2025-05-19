package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.EvaluationResult.{Success, Failure}
import com.github.kmizu.macro_peg.Runner.evalGrammar
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

class RunnerExamplesSpec extends AnyFunSpec with Diagrams {
  describe("Runner examples") {
    it("simple nested plus grammar") {
      val results = evalGrammar(
        """S = A !.; A = "1" A "1" / "+" A / "=";""",
        Seq("1+1=11", "11+1=111", "11+1=1", "11+11+11=111111")
      )
      assertResult(Seq(Success(""), Success(""), Failure, Success("")))(results)
    }

    it("plus and multiply grammar") {
      val grammar =
        """|
          |S = (Plus0("") / Mul0("")) !.;
          |// the number of occurence of '1 represents a natural number.
          |// a+b=c
          |Plus0(Left) = Plus1(Left, "") / &(Left "1") Plus0(Left "1");
          |
          |Plus1(Left, Right)
          |  = &(Left "+" Right "=") Plus2(Left, Right)
          |  / &(Left "+" Right "1") Plus1(Left, Right "1");
          |
          |Plus2(Left, Right)
          |  = Left "+" Right "=" Left Right;
          |
          |// check a*b=c
          |Mul0(Left)
          |  = &(Left "*") Mul1(Left, "", "")
          |  / &(Left "1") Mul0(Left "1");
          |
          |Mul1(Left, Right, Prod)
          |  = &(Left "*" Right "=") Mul2(Left, Right, Prod)
          |  / &(Left "*" Right "1") Mul1(Left, Right "1", Prod Left);
          |
          |Mul2(Left, Right, Prod)
          |  = Left "*" Right "=" Prod;
          |""".stripMargin
      val inputs = Seq(
        "1+1=11",
        "111+11=11111",
        "111+1=11111",
        "111*11=111111",
        "11*111=111111",
        "1*111=1"
      )
      val results = evalGrammar(grammar, inputs)
      assertResult(Seq(Success(""), Success(""), Failure, Success(""), Success(""), Failure))(results)
    }

    it("modifier parsing") {
      val grammar =
        """|
          |S = Modifiers(!"", "") !.;
          |Modifiers(AlreadyLooked, Scope) = (!AlreadyLooked) (
          |    &(Scope) Token("public") Modifiers(AlreadyLooked / "public", "public")
          |  / &(Scope) Token("protected") Modifiers(AlreadyLooked / "protected", "protected")
          |  / &(Scope) Token("private") Modifiers(AlreadyLooked / "private", "private")
          |  / Token("static") Modifiers(AlreadyLooked / "static", Scope)
          |  / Token("final") Modifiers(AlreadyLooked / "final", Scope)
          |  / ""
          |);
          |Token(t) = t Spacing;
          |Spacing = " "*;
          |""".stripMargin
      val inputs = Seq(
        "public static final",
        "public public",
        "public static public",
        "final static public",
        "final final",
        "public private",
        "protected public",
        "public static"
      )
      val results = evalGrammar(grammar, inputs)
      assertResult(Seq(Success(""), Failure, Failure, Success(""), Failure, Failure, Failure, Success("")))(results)
    }

    it("subtraction grammar") {
      val grammar =
        """|
          |S = ReadRight("") !.;
          |// the number of occurence of '1 represents a natural number.
          |// a-b=c
          |// Essentially, this checks a=b+c.
          |ReadRight(Right)
          |  = &("1"* "-" Right "1") ReadRight(Right "1")
          |  / &("1"* "-" Right "=") ReadDiff(Right, "");
          |
          |ReadDiff(Right, Diff)
          |  = &("1"* "-" Right "=" Diff "1") ReadDiff(Right, Diff "1")
          |  / &("1"* "-" Right "=" Diff !.) Check(Right, Diff);
          |
          |Check(Right, Diff)
          |  = Right Diff "-" Right "=" Diff;
          |""".stripMargin
      val inputs = Seq(
        "11-1=1",
        "1-1=",
        "111-11=1",
        "111-1=1",
        "111-1=111",
        "1-11="
      )
      val results = evalGrammar(grammar, inputs)
      assertResult(Seq(Success(""), Success(""), Success(""), Failure, Failure, Failure))(results)
    }

    it("exponent grammar") {
      val grammar =
        """|
          |S = ReadLeft("", "") !.;
          |// the number of occurence of '1 represents a natural number.
          |// |Seq| is the length of a sequence Seq.
          |// ^ is exponent operator
          |// ReadLeft("", "") checks input is a correct expression a^b=c.
          |
          |// Read a.
          |// LeftAsOnes is a sequence of "1" where |LeftAsOnes| = |a|.
          |// LeftAsDots is a sequence of . where |LeftAsDots| = |a|.
          |ReadLeft(LeftAsOnes, LeftAsDots)
          |  = &(LeftAsOnes "1") ReadLeft(LeftAsOnes "1", LeftAsDots .)
          |  / &(LeftAsOnes "^") ComputePadding(LeftAsOnes, LeftAsDots, "");
          |
          |// Compute Padding which is a sequene of .
          |// where |Padding| + |LeftAsDots| = |Input|
          |ComputePadding(LeftAsOnes, LeftAsDots, Padding)
          |  = &(Padding LeftAsDots .) ComputePadding(LeftAsOnes, LeftAsDots, Padding .)
          |  / &(Padding LeftAsDots !.) ReadRight(LeftAsOnes, Padding, "", "1");
          |
          |// Read b.
          |// Exp = a^Right.
          |ReadRight(Left, Padding, Right, Exp)
          |  = &(Left "^" Right "1") Multiply(Left, Padding, Right "1", Exp, "", "")
          |  / &(Left "^" Right "=") Check(Left, Right, Exp);
          |
          |// Compute Left * OldExp.
          |// This adds OldExp Left times into Exp.
          |// I is a loop counter.
          |Multiply(Left, Padding, Right, OldExp, Exp, I)
          |  = &(Padding I .) Multiply(Left, Padding, Right, OldExp, Exp OldExp, I .)
          |  / &(Padding I !.) ReadRight(Left, Padding, Right, Exp);
          |
          |// Check whole input.
          |Check(Left, Right, Exp)
          |  = Left "^" Right "=" Exp;
          |""".stripMargin
      val inputs = Seq(
        "11^111=11111111",
        "11^=1",
        "1^11=1",
        "^11=",
        "11^111=1111111",
        "11^111=111111111"
      )
      val results = evalGrammar(grammar, inputs)
      assertResult(Seq(Success(""), Success(""), Success(""), Success(""), Failure, Failure))(results)
    }

    it("identifier grammar") {
      val results = evalGrammar(
        "S = [a-zA-Z_][a-zA-Z0-9_]*;",
        Seq("hoge", "foo", "hoge1", "foo1", "1foo", "2hoge", "123")
      )
      assertResult(Seq(Success(""), Success(""), Success(""), Success(""), Failure, Failure, Failure))(results)
    }
  }
}
