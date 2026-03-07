package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Ast._

/**
 * Lightweight DSL for constructing PEG grammar ASTs in tests.
 *
 * Uses Scala 3 extension methods and `DUMMY_POSITION` throughout so callers
 * never need to thread a `Position` value.  Import everything with:
 *
 *   import GrammarDsl.*
 *
 * Then write, e.g.:
 *
 *   val g = grammar(
 *     rule("S", str("a") ~ rep0(str("b")) | str("c")),
 *     rule("A", charSet(true, 'x', 'y'))
 *   )
 *
 * The `~` and `|` extension operators on `Expression` mirror the standard PEG
 * operators (sequence and ordered choice).  Postfix-style combinators like
 * `rep0`, `rep1`, and `opt` are plain functions that take an `Expression`.
 */
object GrammarDsl:

  private val P = DUMMY_POSITION

  // ── terminal constructors ──────────────────────────────────────────────────

  def str(s: String): Expression = StringLiteral(P, s)

  def wild: Expression = Wildcard(P)

  def charSet(positive: Boolean, chars: Char*): Expression =
    CharSet(P, positive, chars.toSet)

  def charClass(positive: Boolean, elems: CharClassElement*): Expression =
    CharClass(P, positive, elems.toList)

  def charRange(from: Char, to: Char): CharClassElement = CharRange(from, to)
  def oneChar(ch: Char): CharClassElement = OneChar(ch)

  // ── reference / call ──────────────────────────────────────────────────────

  def ref(name: String): Expression = Identifier(P, Symbol(name))

  def call(name: String, args: Expression*): Expression =
    Call(P, Symbol(name), args.toList)

  def fn(params: String*)(body: Expression): Expression =
    Function(P, params.map(Symbol(_)).toList, body)

  // ── combinators ───────────────────────────────────────────────────────────

  def seq(l: Expression, r: Expression): Expression = Sequence(P, l, r)
  def alt(l: Expression, r: Expression): Expression = Alternation(P, l, r)
  def rep0(e: Expression): Expression = Repeat0(P, e)
  def rep1(e: Expression): Expression = Repeat1(P, e)
  def opt(e: Expression): Expression  = Optional(P, e)
  def andP(e: Expression): Expression = AndPredicate(P, e)
  def notP(e: Expression): Expression = NotPredicate(P, e)
  def dbg(e: Expression): Expression  = Debug(P, e)
  def cut(e: Expression): Expression  = Cut(P, e)

  // ── semantic actions & labels ─────────────────────────────────────────────

  def action(code: String): Expression = SemanticAction(P, code)

  def labeled(label: String, e: Expression): Expression = Labeled(P, label, e)

  // ── rule / grammar ────────────────────────────────────────────────────────

  def rule(name: String, body: Expression): Rule =
    Rule(P, Symbol(name), body)

  /** Higher-order rule with named parameters. */
  def ruleHO(name: String, body: Expression, params: String*): Rule =
    Rule(P, Symbol(name), body, params.map(Symbol(_)).toList)

  def grammar(rules: Rule*): Grammar =
    Grammar(P, rules.toList)

  // ── infix operators via extension ─────────────────────────────────────────

  extension (l: Expression)
    /** Sequence: `a ~ b` → `Sequence(a, b)` */
    def ~(r: Expression): Expression = Sequence(P, l, r)

    /** Ordered choice: `a | b` → `Alternation(a, b)` */
    def |(r: Expression): Expression = Alternation(P, l, r)

    /** Label: `"v" %: expr` → `Labeled("v", expr)` */
    def %:(label: String): Expression = Labeled(P, label, l)
