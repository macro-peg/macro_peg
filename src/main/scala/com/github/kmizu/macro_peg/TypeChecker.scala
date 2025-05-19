package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Ast._

case class TypeError(pos: Position, msg: String) extends RuntimeException(s"${pos.line}:${pos.column}: $msg")

object TypeChecker {
  private val baseType: SimpleType = SimpleType(DUMMY_POSITION)

  def check(grammar: Grammar): Unit = {
    val ruleEnv: Map[Symbol, Type] = grammar.rules.map { r =>
      val paramTypes = r.argTypes.map(_.getOrElse(baseType))
      r.name -> RuleType(r.pos, paramTypes, baseType)
    }.toMap
    grammar.rules.foreach { rule =>
      val paramEnv = rule.args.zip(rule.argTypes.map(_.getOrElse(baseType))).toMap
      checkExpression(rule.body, ruleEnv ++ paramEnv)
    }
  }

  private def checkSimple(pos: Position, t: Type): Unit = t match {
    case _: SimpleType =>
    case _: RuleType   => throw TypeError(pos, "function value used as parsing expression")
  }

  private def checkExpression(exp: Expression, env: Map[Symbol, Type]): Type = exp match {
    case Sequence(pos, l, r) =>
      checkSimple(pos, checkExpression(l, env))
      checkSimple(pos, checkExpression(r, env))
      baseType
    case Alternation(pos, l, r) =>
      checkSimple(pos, checkExpression(l, env))
      checkSimple(pos, checkExpression(r, env))
      baseType
    case Repeat0(pos, b) =>
      checkSimple(pos, checkExpression(b, env)); baseType
    case Repeat1(pos, b) =>
      checkSimple(pos, checkExpression(b, env)); baseType
    case Optional(pos, b) =>
      checkSimple(pos, checkExpression(b, env)); baseType
    case AndPredicate(pos, b) =>
      checkSimple(pos, checkExpression(b, env)); baseType
    case NotPredicate(pos, b) =>
      checkSimple(pos, checkExpression(b, env)); baseType
    case Debug(pos, b) =>
      checkExpression(b, env); baseType
    case StringLiteral(_, _) | Wildcard(_) | CharSet(_,_,_) | CharClass(_,_,_) =>
      baseType
    case Identifier(_, name) =>
      env.getOrElse(name, baseType)
    case Call(pos, name, args) =>
      env.get(name) match {
        case Some(RuleType(_, paramTypes, resultType)) =>
          if(paramTypes.length != args.length)
            throw TypeError(pos, s"${name.name} expects ${paramTypes.length} arguments but ${args.length} were given")
          args.foreach(a => checkExpression(a, env))
          resultType
        case Some(_: SimpleType) =>
          throw TypeError(pos, s"${name.name} is not a function")
        case None =>
          args.foreach(a => checkExpression(a, env)); baseType
      }
    case Function(pos, params, body) =>
      val localEnv = env ++ params.map(_ -> baseType)
      val res = checkExpression(body, localEnv)
      RuleType(pos, params.map(_ => baseType), res)
  }
}
