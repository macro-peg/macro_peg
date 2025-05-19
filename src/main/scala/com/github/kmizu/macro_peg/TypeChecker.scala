package com.github.kmizu.macro_peg

import Ast._

object TypeChecker {
  private val baseType = SimpleType(DUMMY_POSITION)

  private def same(t1: Type, t2: Type): Boolean = (t1, t2) match {
    case (_: SimpleType, _: SimpleType) => true
    case (RuleType(_, ps1, r1), RuleType(_, ps2, r2)) =>
      ps1.length == ps2.length && ps1.zip(ps2).forall { case (a, b) => same(a, b) } && same(r1, r2)
    case _ => false
  }

  def wellTyped(grammar: Grammar): Boolean = {
    val ruleTypes: Map[Symbol, RuleType] = grammar.rules.map { r =>
      val paramTypes = r.argTypes.map(_.getOrElse(baseType))
      r.name -> RuleType(DUMMY_POSITION, paramTypes, baseType)
    }.toMap

    def infer(exp: Expression, env: Map[Symbol, Type]): Option[Type] = exp match {
      case Sequence(_, l, r) =>
        for { _ <- infer(l, env); _ <- infer(r, env) } yield baseType
      case Alternation(_, l, r) =>
        for { _ <- infer(l, env); _ <- infer(r, env) } yield baseType
      case Repeat0(_, b) => infer(b, env).map(_ => baseType)
      case Repeat1(_, b) => infer(b, env).map(_ => baseType)
      case Optional(_, b) => infer(b, env).map(_ => baseType)
      case AndPredicate(_, b) => infer(b, env).map(_ => baseType)
      case NotPredicate(_, b) => infer(b, env).map(_ => baseType)
      case StringLiteral(_, _) => Some(baseType)
      case CharSet(_, _, _) => Some(baseType)
      case CharClass(_, _, _) => Some(baseType)
      case Wildcard(_) => Some(baseType)
      case Debug(_, b) => infer(b, env)
      case Call(_, name, args) =>
        env.get(name).orElse(ruleTypes.get(name)) match {
          case Some(RuleType(_, paramTypes, resultType)) if paramTypes.length == args.length =>
            val inferred = args.map(infer(_, env))
            if (inferred.forall(_.isDefined) && inferred.map(_.get).zip(paramTypes).forall { case (a, p) => same(a, p) })
              Some(resultType)
            else None
          case _ => None
        }
      case Identifier(_, name) => env.get(name).orElse(ruleTypes.get(name))
      case Function(_, args, body) =>
        val argTpes = args.map(_ => baseType)
        infer(body, env ++ args.zip(argTpes).toMap).map(_ => RuleType(DUMMY_POSITION, argTpes, baseType))
    }

    grammar.rules.forall { rule =>
      val paramTypes = rule.argTypes.map(_.getOrElse(baseType))
      infer(rule.body, rule.args.zip(paramTypes).toMap ++ ruleTypes).isDefined
    }
}
