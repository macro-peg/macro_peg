package com.github.kmizu.macro_peg

import Ast._

import com.github.kmizu.macro_peg.TypeError

class TypeChecker(grammar: Grammar) {
  private val simple: SimpleType = SimpleType(DUMMY_POSITION)

  private val ruleTypes: Map[Symbol, Type] = grammar.rules.map { r =>
    val paramTpes = r.argTypes.map(_.getOrElse(simple))
    val tpe: Type =
      if(r.args.isEmpty) simple
      else RuleType(DUMMY_POSITION, paramTpes, simple)
    r.name -> tpe
  }.toMap

  private def same(t1: Type, t2: Type): Boolean = (t1, t2) match {
    case (_: SimpleType, _: SimpleType) => true
    case (RuleType(_, ps1, r1), RuleType(_, ps2, r2)) =>
      ps1.length == ps2.length && ps1.zip(ps2).forall { case (a, b) => same(a, b) } && same(r1, r2)
    case _ => false
  }

  private def infer(exp: Expression, env: Map[Symbol, Type]): Either[TypeError, Type] = exp match {
    case StringLiteral(pos, _) => Right(simple)
    case Wildcard(pos) => Right(simple)
    case CharSet(pos, _, _) => Right(simple)
    case CharClass(pos, _, _) => Right(simple)
    case Sequence(pos, l, r) =>
      for(_ <- infer(l, env); _ <- infer(r, env)) yield simple
    case Alternation(pos, l, r) =>
      for(_ <- infer(l, env); _ <- infer(r, env)) yield simple
    case Repeat0(pos, b) => infer(b, env).map(_ => simple)
    case Repeat1(pos, b) => infer(b, env).map(_ => simple)
    case Optional(pos, b) => infer(b, env).map(_ => simple)
    case AndPredicate(pos, b) => infer(b, env).map(_ => simple)
    case NotPredicate(pos, b) => infer(b, env).map(_ => simple)
    case Identifier(pos, name) =>
      env.get(name).orElse(ruleTypes.get(name)) match {
        case Some(tpe) => Right(tpe)
        case None => Left(TypeError(pos, s"undefined identifier: $name"))
      }
    case Call(pos, name, args) =>
      env.get(name).orElse(ruleTypes.get(name)) match {
        case Some(rt @ RuleType(_, paramTypes, resultType)) =>
          if(paramTypes.length != args.length)
            Left(TypeError(pos, s"#arguments mismatch for $name"))
          else {
            val argTypes = args.map(a => infer(a, env))
            argTypes.collectFirst { case Left(err) => Left(err) } getOrElse {
              val as = argTypes.collect { case Right(t) => t }
              if(paramTypes.zip(as).forall{ case (e,a) => same(e,a) }) Right(resultType)
              else Left(TypeError(pos, s"type mismatch for $name"))
            }
          }
        case Some(_: SimpleType) => Left(TypeError(pos, s"$name is not a function"))
        case None => Left(TypeError(pos, s"undefined rule: $name"))
      }
    case Function(pos, args, body) =>
      val paramTypes = args.map(_ => simple)
      val env1 = env ++ args.map(_ -> simple)
      infer(body, env1).map(res => RuleType(DUMMY_POSITION, paramTypes, res))
    case Debug(pos, b) => infer(b, env)
  }

  def check(): Either[TypeError, Unit] = {
    grammar.rules.foreach { r =>
      val paramTypes = r.argTypes.map(_.getOrElse(simple))
      val env = ruleTypes ++ r.args.zip(paramTypes)
      infer(r.body, env) match {
        case Left(err) => return Left(err)
        case Right(_) => // ok
      }
    }
    Right(())
  }
}
