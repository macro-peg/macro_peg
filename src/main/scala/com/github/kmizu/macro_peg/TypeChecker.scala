package com.github.kmizu.macro_peg

import Ast._

object TypeChecker {
  case class TypeError(message: String) extends Exception(message)

  private val dummy = Ast.SimpleType(Ast.DUMMY_POSITION)

  def check(grammar: Grammar): Unit = {
    val ruleTypes: Map[Symbol, Type] = grammar.rules.map { r =>
      val paramTypes = r.argTypes.map(_.getOrElse(dummy))
      val t: Type =
        if (r.args.isEmpty) SimpleType(r.pos)
        else RuleType(r.pos, paramTypes, dummy)
      r.name -> t
    }.toMap

    grammar.rules.foreach { rule =>
      val env = ruleTypes ++ rule.args.zip(rule.argTypes.map(_.getOrElse(dummy)))
      val bodyType = infer(rule.body, env)
      bodyType match {
        case _: RuleType =>
          throw TypeError(s"rule ${rule.name} returns function")
        case _ =>
      }
    }
  }

  private def infer(exp: Expression, env: Map[Symbol, Type]): Type = exp match {
    case Sequence(_, l, r) => infer(l, env); infer(r, env); dummy
    case Alternation(_, l, r) => infer(l, env); infer(r, env); dummy
    case Repeat0(_, b) => infer(b, env); dummy
    case Repeat1(_, b) => infer(b, env); dummy
    case Optional(_, b) => infer(b, env); dummy
    case AndPredicate(_, b) => infer(b, env); dummy
    case NotPredicate(_, b) => infer(b, env); dummy
    case Debug(_, b) => infer(b, env); dummy
    case StringLiteral(_, _) => dummy
    case Wildcard(_) => dummy
    case CharSet(_, _, _) => dummy
    case CharClass(_, _, _) => dummy
    case Identifier(_, name) => env.getOrElse(name, dummy)
    case Function(_, args, body) =>
      val env2 = env ++ args.map(_ -> dummy)
      infer(body, env2)
      RuleType(Ast.DUMMY_POSITION, List.fill(args.length)(dummy), dummy)
    case Call(_, name, params) =>
      env.get(name) match {
        case Some(rt@RuleType(_, paramTypes, resultType)) =>
          if (paramTypes.length != params.length)
            throw TypeError(s"arity mismatch in call to $name")
          params.zip(paramTypes).foreach { case (arg, pt) =>
            val at = infer(arg, env)
            (pt, at) match {
              case (_: SimpleType, _: SimpleType) =>
              case (_: RuleType, _: RuleType) =>
              case (_: SimpleType, _: RuleType) =>
                throw TypeError(s"expected simple argument for $name")
              case (_: RuleType, _: SimpleType) =>
                throw TypeError(s"expected function argument for $name")
            }
          }
          resultType
        case Some(_: SimpleType) =>
          throw TypeError(s"$name is not a function")
        case None =>
          throw TypeError(s"undefined reference to $name")
      }
  }
}
