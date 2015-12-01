package com
package github
package kmizu
package hopeg

/** This object provides types representing ASTs of extended PEG.
  * It is used as namespace.
  * @author Kota Mizushima
  */
object Ast {
  type ==>[-A, +B] = PartialFunction[A, B]
  
  val DUMMY_POS = Pos(-1, -1)
  
  /** A trait for types that has position. */
  trait HasPosition { def pos: Pos }
  /** This class represents position in a source file.
    * @param line line number (0-origin)
    * @param column column number (0-origin) */
  case class Pos (line: Int, column: Int)
  /** This class represents an AST of PEG grammar.
    * @param pos position in source file
    * @param rules the list of rules constituting PEG grammar */
  case class Grammar(pos: Pos, rules: List[Rule]) extends HasPosition {
    def +(newRule: Rule): Grammar = Grammar(pos, rules = newRule::rules)
  }
  /** This class represents an AST of rule in PEG grammar.
    * @param pos position in source file
    * @param name the name of this rule.  It is referred in body
    * @param body the parsing expression which this rule represents */
  case class Rule(pos: Pos, name: Symbol, body: Exp, args: List[Symbol] = Nil, action: Any ==> Any = { case a => a }) extends HasPosition
  /** This trait represents common super-type of parsing expression AST. */
  sealed trait Exp extends HasPosition
  /** This class represents an AST of sequence (e1 e2).
    * @param pos position in source file
    * @param lhs e1
    * @param rhs e2 */
  case class Seq(pos: Pos, lhs: Exp, rhs: Exp) extends Exp
  /** This class represents an AST of ordered choice (e1 / e2).
    * @param pos position in source file
    * @param lhs e1
    * @param rhs e2 */
  case class Alt(pos: Pos, lhs: Exp, rhs: Exp) extends Exp
  /** This class represents an AST of repetition e*.
    * @param pos position in source file
    * @param body e */
  case class Rep0(pos: Pos, body: Exp) extends Exp
  /** This class represents an AST of one-or-more repetition e+.
    * @param pos position in source file
    * @param body e */
  case class Rep1(pos: Pos, body: Exp) extends Exp
  /** This class represents an AST of zero-or-one occurrence e?.
    * @param pos position in source file
    * @param body e */
  case class Opt(pos: Pos, body: Exp) extends Exp
  /** This class represents an AST of and-predicate &(e).
    * @param pos position in source file
    * @param body e */
  case class AndPred(pos: Pos, body: Exp) extends Exp
  /** This class represents an AST of not-predicate !(e).
    * @param pos position in source file
    * @param body e */
  case class NotPred(pos: Pos, body: Exp) extends Exp
  /** This class represents an AST of string literal "...".
    * @param pos position in source file
    * @param target literal */
  case class Str(pos: Pos, target: String) extends Exp
  /** This class represents an AST of wild-card character ..
    * @param pos position in source file */
  case class Wildcard(pos: Pos) extends Exp

  /** This class represents an AST of rule calls.
    * @param pos position in source file
    * @param name the name of identifier */
  case class Call(pos: Pos, name: Symbol, args: List[Exp]) extends Exp
  /** This class represents an AST of identifier.
    * An identifier is used as reference of nonterminal.
    * @param pos position in source file
    * @param name the name of identifier */
  case class Ident(pos: Pos, name: Symbol) extends Exp
}
