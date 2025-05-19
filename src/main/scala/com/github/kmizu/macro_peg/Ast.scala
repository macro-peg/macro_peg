package com
package github
package kmizu
package macro_peg

/** This object provides types representing ASTs of extended PEG.
  * It is used as namespace.
  * @author Kota Mizushima
  */
object Ast {
  type ==>[-A, +B] = PartialFunction[A, B]
  
  val DUMMY_POSITION: Position = Position(-1, -1)

  /** A trait for types that has position. */
  trait HasPosition { def pos: Position }
  /** This class represents position in a source file.
 *
    * @param line line number (0-origin)
    * @param column column number (0-origin) */
  case class Position(line: Int, column: Int)
  /** This class represents an AST of PEG grammar.
 *
    * @param pos position in source file
    * @param rules the list of rules constituting PEG grammar */
  case class Grammar(pos: Position, rules: List[Rule]) extends HasPosition {
    def +(newRule: Rule): Grammar = Grammar(pos, rules = newRule::rules)

    def isWellFormed: Boolean = {
      val ruleMapping = rules.map{r => r.name -> r}.toMap
      val ruleNames = ruleMapping.keySet

      // Check undefined references
      def checkDefined(exp: Expression, env: Set[Symbol]): Boolean = exp match {
        case Sequence(_, l, r) => checkDefined(l, env) && checkDefined(r, env)
        case Alternation(_, l, r) => checkDefined(l, env) && checkDefined(r, env)
        case Repeat0(_, b) => checkDefined(b, env)
        case Repeat1(_, b) => checkDefined(b, env)
        case Optional(_, b) => checkDefined(b, env)
        case AndPredicate(_, b) => checkDefined(b, env)
        case NotPredicate(_, b) => checkDefined(b, env)
        case Call(_, name, args) =>
          (env(name) || ruleNames(name)) && args.forall(a => checkDefined(a, env))
        case Identifier(_, name) => env(name) || ruleNames(name)
        case Function(_, args, body) => checkDefined(body, env ++ args.toSet)
        case Debug(_, b) => checkDefined(b, env)
        case _ => true
      }
      if(!rules.forall(r => checkDefined(r.body, r.args.toSet))) return false

      import scala.collection.mutable.{Map => MutableMap}
      val nullable: MutableMap[Symbol, Boolean] = MutableMap.empty
      ruleNames.foreach(n => nullable(n) = false)

      def exprNullable(exp: Expression, env: Set[Symbol]): Boolean = exp match {
        case Sequence(_, l, r) => exprNullable(l, env) && exprNullable(r, env)
        case Alternation(_, l, r) => exprNullable(l, env) || exprNullable(r, env)
        case Repeat0(_, _) => true
        case Repeat1(_, b) => exprNullable(b, env)
        case Optional(_, _) => true
        case AndPredicate(_, _) => true
        case NotPredicate(_, _) => true
        case StringLiteral(_, s) => s.isEmpty
        case Wildcard(_) => false
        case CharSet(_, _, _) => false
        case CharClass(_, _, _) => false
        case Call(_, name, _) => if(env(name)) true else nullable.getOrElse(name, false)
        case Identifier(_, name) => if(env(name)) true else nullable.getOrElse(name, false)
        case Function(_, args, body) => exprNullable(body, env ++ args.toSet)
        case Debug(_, b) => exprNullable(b, env)
      }

      var changed = true
      while(changed) {
        changed = false
        for(r <- rules) {
          val n = exprNullable(r.body, r.args.toSet)
          if(n != nullable(r.name)) { nullable(r.name) = n; changed = true }
        }
      }

      def nullableExp(exp: Expression, env: Set[Symbol]): Boolean = exprNullable(exp, env)

      // repetition on nullable expression causes infinite loop
      def checkRepetition(exp: Expression, env: Set[Symbol]): Boolean = exp match {
        case Sequence(_, l, r) => checkRepetition(l, env) && checkRepetition(r, env)
        case Alternation(_, l, r) => checkRepetition(l, env) && checkRepetition(r, env)
        case Repeat0(_, b) => !nullableExp(b, env) && checkRepetition(b, env)
        case Repeat1(_, b) => !nullableExp(b, env) && checkRepetition(b, env)
        case Optional(_, b) => checkRepetition(b, env)
        case AndPredicate(_, b) => checkRepetition(b, env)
        case NotPredicate(_, b) => checkRepetition(b, env)
        case Call(_, _, args) => args.forall(a => checkRepetition(a, env))
        case Function(_, args, body) => checkRepetition(body, env ++ args.toSet)
        case Debug(_, b) => checkRepetition(b, env)
        case _ => true
      }
      if(!rules.forall(r => checkRepetition(r.body, r.args.toSet))) return false

      // left recursion detection
      def leadsToSelf(sym: Symbol, exp: Expression, env: Set[Symbol], visited: Set[Symbol]): Boolean = exp match {
        case Sequence(_, l, r) =>
          leadsToSelf(sym, l, env, visited) || (nullableExp(l, env) && leadsToSelf(sym, r, env, visited))
        case Alternation(_, l, r) => leadsToSelf(sym, l, env, visited) || leadsToSelf(sym, r, env, visited)
        case Repeat0(_, b) => leadsToSelf(sym, b, env, visited)
        case Repeat1(_, b) => leadsToSelf(sym, b, env, visited)
        case Optional(_, b) => leadsToSelf(sym, b, env, visited)
        case AndPredicate(_, b) => leadsToSelf(sym, b, env, visited)
        case NotPredicate(_, b) => leadsToSelf(sym, b, env, visited)
        case Call(_, name, _) =>
          if(name == sym) true
          else if(ruleMapping.contains(name) && nullable(name) && !visited(name))
            leadsToSelf(sym, ruleMapping(name).body, ruleMapping(name).args.toSet, visited + name)
          else false
        case Identifier(_, name) =>
          if(name == sym) true
          else if(ruleMapping.contains(name) && nullable(name) && !visited(name))
            leadsToSelf(sym, ruleMapping(name).body, ruleMapping(name).args.toSet, visited + name)
          else false
        case Function(_, args, body) => leadsToSelf(sym, body, env ++ args.toSet, visited)
        case Debug(_, b) => leadsToSelf(sym, b, env, visited)
        case _ => false
      }
      if(rules.exists(r => leadsToSelf(r.name, r.body, r.args.toSet, Set()))) return false

      true
    }
  }
  /** This class represents an AST of rule in PEG grammar.
 *
    * @param pos position in source file
    * @param name the name of this rule.  It is referred in body
    * @param body the parsing expression which this rule represents */
  case class Rule(pos: Position, name: Symbol, body: Expression, args: List[Symbol] = Nil) extends HasPosition
  /** This trait represents common super-type of parsing expression AST. */
  sealed trait Expression extends HasPosition
  /** This class represents an AST of sequence (e1 e2).
 *
    * @param pos position in source file
    * @param lhs e1
    * @param rhs e2 */
  case class Sequence(pos: Position, lhs: Expression, rhs: Expression) extends Expression
  /** This class represents an AST of ordered choice (e1 / e2).
 *
    * @param pos position in source file
    * @param lhs e1
    * @param rhs e2 */
  case class Alternation(pos: Position, lhs: Expression, rhs: Expression) extends Expression
  /** This class represents an AST of repetition e*.
 *
    * @param pos position in source file
    * @param body e */
  case class Repeat0(pos: Position, body: Expression) extends Expression
  /** This class represents an AST of one-or-more repetition e+.
 *
    * @param pos position in source file
    * @param body e */
  case class Repeat1(pos: Position, body: Expression) extends Expression
  /** This class represents an AST of zero-or-one occurrence e?.
 *
    * @param pos position in source file
    * @param body e */
  case class Optional(pos: Position, body: Expression) extends Expression
  /** This class represents an AST of and-predicate &(e).
 *
    * @param pos position in source file
    * @param body e */
  case class AndPredicate(pos: Position, body: Expression) extends Expression
  /** This class represents an AST of not-predicate !(e).
 *
    * @param pos position in source file
    * @param body e */
  case class NotPredicate(pos: Position, body: Expression) extends Expression
  /** This class represents an AST of string literal "...".
 *
    * @param pos    position in source file
    * @param target literal */
  case class StringLiteral(pos: Position, target: String) extends Expression
  /** This class represents an AST of wild-card character ..
 *
    * @param pos position in source file */
  case class Wildcard(pos: Position) extends Expression
  /** This class represents an AST of character set,
    *  which is created from CharSet.
    */
  case class CharSet(pos: Position, positive: Boolean, elems: Set[Char]) extends Expression
  /** This class represents an AST of character class [...].
 *
    * @param pos position in source file
    * @param elems the list of element constituting character class. */
  case class CharClass(pos: Position, positive: Boolean, elems: List[CharClassElement]) extends Expression
  /** This trait represents common super-type of element in character class. */
  sealed trait CharClassElement
  /** An element of character class representing one character. */
  case class OneChar(ch: Char) extends CharClassElement
  /** An element of character class representing characters in this range.
 *
    * @param from start of the range
    * @param to end of the range */
  case class CharRange(from: Char, to: Char) extends CharClassElement

  /**
    * This class represents an AST of Debug(e)
 *
    * @param pos  pos position in source file
    * @param body e */
  case class Debug(pos: Position, body: Expression) extends Expression

  /** This class represents an AST of rule calls.
 *
    * @param pos position in source file
    * @param name the name of identifier */
  case class Call(pos: Position, name: Symbol, args: List[Expression]) extends Expression

  /** This class represents an AST of identifier.
    * An identifier is used as reference of nonterminal.
 *
    * @param pos  position in source file
    * @param name the name of identifier */
  case class Identifier(pos: Position, name: Symbol) extends Expression

  case class Function(pos: Position, args: List[Symbol], body: Expression) extends Expression

  sealed abstract class Type(pos: Position)
  case class SimpleType(pos: Position) extends Type(pos)
  case class RuleType(pos: Position, paramTypes: List[Type], resultType: Type) extends Type(pos)

}
