package com
package github
package kmizu
package macro_peg
package ir

import com.github.kmizu.macro_peg.Ast

/** ScalaCC common IR (intermediate representation).
  *
  * This sits between the surface [[Ast.Grammar]] and the various code emitters
  * (combinator / recursive-descent / interpreter / inline-macro). Lowering
  * (`Ast.Grammar => Ir.Program`) performs validation, type resolution,
  * higher-order expansion, scanner partitioning and normalization ONCE, so that
  * every emitter consumes the same already-normalized, first-order-where-possible,
  * scanner-aware tree.
  *
  * Design notes:
  *  - `Sequence`/`Alternation` from the AST are binary; here `Seq`/`Alt` are
  *    flattened to N-ary so emitters don't re-implement `flattenParts`.
  *  - Labeled captures and the trailing semantic action of a sequence are
  *    structured into [[Seq]] (`parts` + `action`) rather than discovered at
  *    emit time.
  *  - `CharSet` and `CharClass` are unified into [[Chars]].
  *  - Higher-order nodes ([[HoCall]]/[[Lambda]]) only survive when lowering
  *    could NOT inline them (interpreter-fallback path); the normal path is
  *    first-order.
  *  - The IR is scanner-aware ([[Level]], [[Section]], [[TokenRef]]); a single
  *    scannerless grammar lowers to `lexical = None` (back-compat).
  */
object Ir {
  type Position = Ast.Position

  /** Which PEG level a section/rule belongs to. */
  enum Level:
    case Lexical, Syntactic

  /** Token vs skip classification for lexical rules (Pillar C). */
  enum TokenKind:
    case Token, Skip

  /** Resolved type of a rule / expression result. */
  enum IrType:
    case TString
    case TUnknown
    case TUser(fqcn: String)
    case TFun(params: Vector[IrType], result: IrType)

  /** A formal parameter of a (still) higher-order rule. */
  final case class Param(name: String, tpe: IrType)

  /** A resolved, pre-mangled rule identifier.
    *
    * @param name    original grammar name
    * @param mangled emitter-safe Scala identifier (e.g. `r_Foo`)
    * @param level   which level this rule lives in
    */
  final case class RuleId(name: String, mangled: String, level: Level)

  /** A semantic action carried with enough info for BOTH source codegen
    * (raw `code` string spliced as text) and the inline macro (parse `code`
    * against `boundLabels` to build a typed `Expr`).
    *
    * @param code        raw embedded Scala code
    * @param pos         source position of the action
    * @param boundLabels labels visible to the action, with resolved types
    */
  final case class ActionRef(code: String, pos: Position, boundLabels: Vector[(String, IrType)])

  /** A part of a flattened sequence: an expression with an optional capture label. */
  final case class LabeledExpr(label: Option[String], expr: Expr)

  /** Lowered parsing expression. */
  enum Expr:
    /** N-ary sequence with an optional trailing semantic action. */
    case Seq(parts: Vector[LabeledExpr], action: Option[ActionRef])
    /** N-ary ordered choice. */
    case Alt(branches: Vector[Expr])
    /** Repetition: `oneOrMore` selects `+` vs `*`. */
    case Rep(body: Expr, oneOrMore: Boolean)
    case Opt(body: Expr)
    case And(body: Expr)
    case Not(body: Expr)
    case Str(value: String)
    case AnyChar
    /** Unified character set: ranges + single chars, positive or negated. */
    case Chars(positive: Boolean, ranges: Vector[(Char, Char)], singles: Set[Char])
    /** Reference to another (0-arg, first-order) rule. */
    case RuleRef(target: RuleId)
    /** Parser-level reference to a lexical token (Pillar C). */
    case TokenRef(token: RuleId)
    case Cut(body: Expr)
    case Debug(body: Expr)
    // --- higher-order (Macro PEG): handled natively via the lambda-param method ---
    /** Reference to a rule/lambda parameter, emitted as a `() => Option[Any]` thunk call. */
    case ParamRef(index: Int, name: String)
    /** Call to a parameterized (higher-order) rule with argument expressions. */
    case HoCall(target: RuleId, args: Vector[Expr])
    /** Lambda argument passed to a higher-order rule. */
    case Lambda(params: Vector[String], body: Expr)

  /** A lowered rule. */
  final case class IrRule(
    id: RuleId,
    params: Vector[Param],          // empty for lowered first-order rules
    body: Expr,
    resultType: IrType,
    isTokenRule: Boolean = false,   // Pillar C: lexical token rule
    tokenKind: Option[TokenKind] = None,
    activeStates: Vector[String] = Vector.empty
  )

  /** A lexical state declaration (JavaCC-like). */
  final case class LexState(name: String)

  /** A group of rules at one level. */
  final case class Section(level: Level, rules: Vector[IrRule], lexStates: Vector[LexState] = Vector.empty)

  /** A fully lowered grammar ready for emission.
    *
    * @param lexical   scanner section, or None for a scannerless grammar
    * @param syntactic parser section
    * @param preamble  user `head { ... }` block, verbatim
    * @param startRule entry rule (in the syntactic section)
    * @param needsInterpreter true when higher-order rules could not be inlined,
    *        so static emitters must fall back to the interpreter
    */
  final case class Program(
    lexical: Option[Section],
    syntactic: Section,
    preamble: String,
    startRule: RuleId,
    needsInterpreter: Boolean = false
  ):
    /** All rules across both levels. */
    def allRules: Vector[IrRule] = lexical.map(_.rules).getOrElse(Vector.empty) ++ syntactic.rules
}
