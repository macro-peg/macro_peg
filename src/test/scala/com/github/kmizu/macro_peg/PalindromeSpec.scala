package com.github.kmizu.macro_peg

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.diagrams.Diagrams

/**
 * Palindromes in Macro PEG, and what plain PEG can and cannot do.
 *
 * Whether the language of palindromes has a plain PEG (no parameterized rules) is a question
 * Loff, Moreira and Reis raised in 2020; they conjectured that even-length palindromes have
 * none (Conjecture 7), observing that "PEGs cannot find the middle bit of the input". They did
 * give a plain PEG for palindromes whose length is a power of two, where the middle IS findable:
 * "am I at a position whose distance from the end is a power of two?" is decidable by a
 * lookahead that consumes the whole remaining input.
 *
 * Macro PEG sidesteps the middle entirely. A parameterized rule can carry the reversed prefix
 * as an accumulator and then match it against the remaining input — the same trick as the copy
 * language `{ww}`. Under call-by-name the argument is spliced in unevaluated, so `Pal("0" acc)`
 * really does build up a longer literal-matching expression at each step:
 *
 * {{{
 * S = Pal("") !.;
 * Pal(acc: ?) = "0" Pal("0" acc) / "1" Pal("1" acc) / acc;
 * }}}
 *
 * These tests check the grammars exhaustively against the mathematical predicate, and pin the
 * two facts that make the plain-PEG question interesting:
 *
 *  - the natural plain PEG `A = "0" A "0" / "1" A "1" / ""` is SOUND but INCOMPLETE — it never
 *    accepts a non-palindrome, yet misses infinitely many palindromes, the smallest being
 *    `0000`. Prioritized choice commits the inner call to a match that is too long, and PEG
 *    cannot backtrack into a subexpression that already succeeded.
 *  - the order of the base cases in the Macro PEG version matters: the odd case
 *    (`("0" / "1") acc`) must precede the even case (`acc`), or the constant-symbol odd
 *    palindromes (`0`, `000`, `00000`, ...) are lost while mixed ones such as `010` survive.
 */
class PalindromeSpec extends AnyFunSpec with Diagrams {
  private val maxLen = 10

  private lazy val inputs: Seq[String] = {
    def go(n: Int): Seq[String] =
      if (n == 0) Seq("")
      else for { s <- go(n - 1); c <- Seq("0", "1") } yield s + c
    (0 to maxLen).flatMap(go)
  }

  private def recognizer(source: String): String => Boolean = {
    val interpreter = Interpreter.fromGrammar(Parser.parse(source), EvaluationStrategy.CallByName)
    input => interpreter.evaluate(input, Symbol("S")).isSuccess
  }

  private def isPalindrome(s: String): Boolean = s == s.reverse
  private def isEvenPalindrome(s: String): Boolean = isPalindrome(s) && s.length % 2 == 0

  /** Inputs the grammar rejects although they are in the language. */
  private def missed(accepts: String => Boolean, expected: String => Boolean): Seq[String] =
    inputs.filter(s => expected(s) && !accepts(s))

  /** Inputs the grammar accepts although they are not in the language. */
  private def spurious(accepts: String => Boolean, expected: String => Boolean): Seq[String] =
    inputs.filter(s => !expected(s) && accepts(s))

  private val evenPalindromeGrammar =
    """S = Pal("") !.;
      |Pal(acc: ?) = "0" Pal("0" acc) / "1" Pal("1" acc) / acc;
      |""".stripMargin

  private val palindromeGrammar =
    """S = Pal("") !.;
      |Pal(acc: ?) = "0" Pal("0" acc) / "1" Pal("1" acc) / ("0" / "1") acc / acc;
      |""".stripMargin

  private val naivePlainPegGrammar =
    """S = A !.;
      |A = "0" A "0" / "1" A "1" / "";
      |""".stripMargin

  describe("Macro PEG for even-length palindromes") {
    it("recognizes exactly the even-length palindromes over {0,1}") {
      val accepts = recognizer(evenPalindromeGrammar)
      assert(missed(accepts, isEvenPalindrome) == Seq.empty)
      assert(spurious(accepts, isEvenPalindrome) == Seq.empty)
    }

    it("accepts the empty string and rejects every odd-length input") {
      val accepts = recognizer(evenPalindromeGrammar)
      assert(accepts(""))
      assert(!accepts("0"))
      assert(!accepts("010"))
    }

    it("accepts the repeated-symbol palindromes the naive plain PEG loses") {
      val accepts = recognizer(evenPalindromeGrammar)
      assert(accepts("0000"))
      assert(accepts("011110"))
      assert(accepts("00000000"))
    }
  }

  describe("Macro PEG for all palindromes") {
    it("recognizes exactly the palindromes over {0,1}") {
      val accepts = recognizer(palindromeGrammar)
      assert(missed(accepts, isPalindrome) == Seq.empty)
      assert(spurious(accepts, isPalindrome) == Seq.empty)
    }

    it("loses the constant-symbol odd palindromes if the even base case is tried first") {
      // With `acc` ahead of `("0" / "1") acc`, the recursion unwinds one step too early exactly
      // when every symbol is the same, so `0`, `000`, `00000`, ... (and their `1` counterparts)
      // are lost while mixed odd palindromes such as `010` still parse.
      val accepts = recognizer(
        """S = Pal("") !.;
          |Pal(acc: ?) = "0" Pal("0" acc) / "1" Pal("1" acc) / acc / ("0" / "1") acc;
          |""".stripMargin)
      assert(accepts("0110"))
      assert(accepts("010"))
      assert(!accepts("0"))
      assert(!accepts("000"))
      assert(!accepts("11111"))
      val lost = missed(accepts, isPalindrome)
      assert(lost.forall(w => w.length % 2 == 1 && w.distinct.length == 1))
    }
  }

  describe("the naive plain PEG A = \"0\" A \"0\" / \"1\" A \"1\" / \"\"") {
    it("never accepts a non-palindrome (sound)") {
      val accepts = recognizer(naivePlainPegGrammar)
      assert(spurious(accepts, isEvenPalindrome) == Seq.empty)
    }

    it("misses palindromes, the smallest being 0000 (incomplete)") {
      val accepts = recognizer(naivePlainPegGrammar)
      val lost = missed(accepts, isEvenPalindrome)
      assert(lost.nonEmpty)
      assert(lost.minBy(_.length) == "0000")
      assert(!accepts("0000"))
      assert(!accepts("1111"))
      assert(!accepts("011110"))
    }

    it("still handles the palindromes whose greedy inner match lands correctly") {
      val accepts = recognizer(naivePlainPegGrammar)
      assert(accepts("00"))
      assert(accepts("0110"))
      assert(accepts("001100"))
    }
  }
}
