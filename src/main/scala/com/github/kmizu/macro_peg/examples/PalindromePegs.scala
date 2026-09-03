package com.github.kmizu.macro_peg.examples

/**
 * Explicit grammars for palindrome languages.
 *
 * Background. `PAL = { w in {a,b}* | w = reverse(w) }` is in PEG: Galil (1978) recognizes all
 * initial palindromes on a real-time multitape Turing machine, Kim & Park (2026) compile such a
 * machine into a scaffolding automaton (SCA), and Loff-Moreira-Reis give `L in PEG` iff
 * `reverse(L) in SCA`; since `reverse(PAL) = PAL`, PAL is in PEG. That argument produces no
 * grammar one can run, and no explicit plain PEG for PAL is known.
 *
 * What is explicit is written down here:
 *
 *   - [[macroPalindrome]] full PAL, as a two-rule *Macro* PEG, and [[macroEvenPalindrome]] for the
 *     even-length half. The macro parameter carries the reversed prefix, which is exactly the
 *     state a plain PEG cannot keep.
 *   - [[inner]] `L_K = PAL and (at most K occurrences of 'b')`, a plain PEG of size O(K).
 *     `L_0 subset L_1 subset ...` and their union is PAL.
 *   - [[outer]] `D_K = { w | w(k) = w(|w|-1-k) for all k < K with 2k+1 < |w| }`, a plain PEG of
 *     size O(K^2). `D_0 superset D_1 superset ...` and their intersection is PAL. `D_K` checks the
 *     outermost K of the pairs that the macro parameter checks all at once, so the family measures
 *     what the parameter is buying: unboundedly many pairs from a fixed number of rules.
 *   - [[bounded]] `PAL and (length <= N)` exactly, a plain PEG of size O(N^2).
 *
 * So PAL is squeezed between two explicit chains of plain PEG languages, and every bounded-length
 * slice of PAL is an explicit plain PEG; only the unbounded limit needs the non-constructive route.
 *
 * The gadget that makes the outer family work is right-anchored addressing: "the character sitting
 * exactly k positions from the right end is c" is a plain PEG of size O(k),
 * {{{ A = [ab] A / "c" [ab]^k !.; }}}
 * because the recursion descends to the end of the input first and unwinds onto the unique position
 * that has exactly k characters after it.
 */
object PalindromePegs {

  /**
   * Full PAL as a Macro PEG. `r` accumulates the reversed prefix read so far, and the last two
   * alternatives close the palindrome: `[ab] r` consumes an odd centre first, `r` takes the even
   * case. The order matters — trying `r` before `[ab] r` makes the grammar reject "aaa".
   */
  val macroPalindrome: String =
    """S = P("") !.;
      |P(r) = "a" P("a" r) / "b" P("b" r) / [ab] r / r;""".stripMargin

  /**
   * Even-length palindromes as a Macro PEG: the same grammar without the odd centre. This is the
   * language of Loff-Moreira-Reis Conjecture 7 ("even palindromes are not in PEG"), which the
   * Galil / Kim-Park / LMR composition refutes — a plain PEG for it exists but is not known
   * explicitly, while two macro rules suffice.
   */
  val macroEvenPalindrome: String =
    """S = P("") !.;
      |P(r) = "a" P("a" r) / "b" P("b" r) / r;""".stripMargin

  private def rep(symbol: String, times: Int): String =
    if (times <= 0) "" else List.fill(times)(symbol).mkString(" ")

  // ---------------------------------------------------------------- inner family

  /**
   * Rules `A_m` for every `m` reachable from `ks` by repeatedly subtracting 2.
   * `A_m` matches the palindromes that contain exactly `m` occurrences of `'b'`: strip matching
   * `'a'`s from both ends, then the two outermost `'b'`s pair up and the count drops by two.
   */
  private def innerRules(ks: Seq[Int]): Seq[String] = {
    val needed = scala.collection.mutable.SortedSet.empty[Int]
    for (k <- ks; m <- k to 0 by -2) needed += m
    needed.toSeq.sorted.reverse.map {
      case 0 => "A0 = [a]*;"
      case 1 => """A1 = "a" A1 "a" / "b";"""
      case m => s"""A$m = "a" A$m "a" / "b" A${m - 2} "b";"""
    }
  }

  /** Plain PEG for `PAL and (exactly k occurrences of 'b')`. */
  def innerExact(k: Int): String = {
    require(k >= 0, s"k must be non-negative, got $k")
    (s"S = A$k !.;" +: innerRules(Seq(k))).mkString("\n")
  }

  /** Plain PEG for `PAL and (at most maxB occurrences of 'b')`. */
  def inner(maxB: Int): String = {
    require(maxB >= 0, s"maxB must be non-negative, got $maxB")
    val start = (maxB to 0 by -1).map(k => s"A$k !.").mkString(" / ")
    (s"S = $start;" +: innerRules(0 to maxB)).mkString("\n")
  }

  // ---------------------------------------------------------------- outer family

  /** `C_k` checks `w(k) = w(|w|-1-k)`, vacuously true when the input is too short to have both. */
  private def mirrorRules(depth: Int): Seq[String] = {
    val checks = (0 until depth).map { k =>
      val skip = if (k == 0) "" else rep("[ab]", k) + " "
      s"""C$k = !(${rep("[ab]", 2 * k + 2)}) / $skip( "a" &(A$k) / "b" &(B$k) );"""
    }
    val addressing = (0 until depth).flatMap { k =>
      val tail = if (k == 0) "" else " " + rep("[ab]", k)
      Seq(s"""A$k = [ab] A$k / "a"$tail !.;""", s"""B$k = [ab] B$k / "b"$tail !.;""")
    }
    checks ++ addressing
  }

  /** Plain PEG for `D_depth`: the first `depth` characters mirror the last `depth`. */
  def outer(depth: Int): String = {
    require(depth >= 0, s"depth must be non-negative, got $depth")
    val guards = (0 until depth).map(k => s"&C$k").mkString(" ")
    val start = if (depth == 0) "S = [ab]* !.;" else s"S = $guards [ab]* !.;"
    (start +: mirrorRules(depth)).mkString("\n")
  }

  /** Plain PEG for `PAL and (length <= maxLength)`, exactly. */
  def bounded(maxLength: Int): String = {
    require(maxLength >= 0, s"maxLength must be non-negative, got $maxLength")
    val depth = (maxLength + 1) / 2
    val guards = (0 until depth).map(k => s"&C$k").mkString(" ")
    val prefix = s"!(${rep("[ab]", maxLength + 1)})"
    val start =
      if (depth == 0) s"S = $prefix [ab]* !.;" else s"S = $prefix $guards [ab]* !.;"
    (start +: mirrorRules(depth)).mkString("\n")
  }
}
