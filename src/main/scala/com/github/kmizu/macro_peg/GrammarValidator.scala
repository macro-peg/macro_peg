package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Ast._

case class GrammarError(pos: Position, message: String, hint: Option[String] = None)

object GrammarValidator {
  def validate(grammar: Grammar): Either[GrammarError, Unit] = {
    val ruleMapping = grammar.rules.map(r => r.name -> r).toMap
    val rawRuleNames = grammar.directives.collect { case RawRuleDirective(n, _) => Symbol(n) }.toSet
    val ruleNames = ruleMapping.keySet ++ rawRuleNames

    def undefinedReference(exp: Expression, env: Set[Symbol]): Option[GrammarError] = exp match {
      case Sequence(_, l, r) =>
        undefinedReference(l, env).orElse(undefinedReference(r, env))
      case Alternation(_, l, r) =>
        undefinedReference(l, env).orElse(undefinedReference(r, env))
      case Repeat0(_, b) => undefinedReference(b, env)
      case Repeat1(_, b) => undefinedReference(b, env)
      case Optional(_, b) => undefinedReference(b, env)
      case AndPredicate(_, b) => undefinedReference(b, env)
      case NotPredicate(_, b) => undefinedReference(b, env)
      case Call(pos, name, args) =>
        if(!(env(name) || ruleNames(name))) {
          Some(GrammarError(pos, s"undefined rule or variable: ${name.name}", Some(s"define `${name.name}` before use")))
        } else {
          args.foldLeft(Option.empty[GrammarError])((acc, a) => acc.orElse(undefinedReference(a, env)))
        }
      case Identifier(pos, name) =>
        if(env(name) || ruleNames(name)) None
        else Some(GrammarError(pos, s"undefined rule or variable: ${name.name}", Some(s"define `${name.name}` before use")))
      case Function(_, args, body) => undefinedReference(body, env ++ args.toSet)
      case Labeled(_, _, b) => undefinedReference(b, env)
      case Cut(_, b) => undefinedReference(b, env)
      case SemanticAction(_, _) => None
      case Debug(_, b) => undefinedReference(b, env)
      case ActionBlock(_, b, _) => undefinedReference(b, env)
      case LeftProject(_, l, r) => undefinedReference(l, env).orElse(undefinedReference(r, env))
      case RightProject(_, l, r) => undefinedReference(l, env).orElse(undefinedReference(r, env))
      case IgnoredExpr(_, e) => undefinedReference(e, env)
      case _ => None
    }

    val undefinedError = grammar.rules.iterator
      .map(r => undefinedReference(r.body, r.args.toSet))
      .collectFirst { case Some(err) => err }
    if(undefinedError.nonEmpty) return Left(undefinedError.get)

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
      // Parameters (in env but not in rules) are treated as non-nullable;
      // they will be expanded with concrete arguments at call sites.
      case Call(_, name, _) => if(env(name) && !ruleNames(name)) false else nullable.getOrElse(name, false)
      case Identifier(_, name) => if(env(name) && !ruleNames(name)) false else nullable.getOrElse(name, false)
      case Function(_, args, body) => exprNullable(body, env ++ args.toSet)
      case Labeled(_, _, b) => exprNullable(b, env)
      case Cut(_, b) => exprNullable(b, env)
      case SemanticAction(_, _) => true
      case Debug(_, b) => exprNullable(b, env)
      case ActionBlock(_, b, _) => exprNullable(b, env)
      case LeftProject(_, l, r) => exprNullable(l, env) && exprNullable(r, env)
      case RightProject(_, l, r) => exprNullable(l, env) && exprNullable(r, env)
      case IgnoredExpr(_, e) => exprNullable(e, env)
    }

    var changed = true
    while(changed) {
      changed = false
      grammar.rules.foreach { r =>
        val n = exprNullable(r.body, r.args.toSet)
        if(n != nullable(r.name)) {
          nullable(r.name) = n
          changed = true
        }
      }
    }

    def nullableExpression(exp: Expression, env: Set[Symbol]): Boolean = exprNullable(exp, env)

    def nullableRepetition(exp: Expression, env: Set[Symbol]): Option[GrammarError] = exp match {
      case Sequence(_, l, r) =>
        nullableRepetition(l, env).orElse(nullableRepetition(r, env))
      case Alternation(_, l, r) =>
        nullableRepetition(l, env).orElse(nullableRepetition(r, env))
      case Repeat0(pos, b) =>
        if(nullableExpression(b, env)) Some(GrammarError(pos, "nullable expression inside `*` can cause infinite loop", Some("rewrite repeated expression so it always consumes input")))
        else nullableRepetition(b, env)
      case Repeat1(pos, b) =>
        if(nullableExpression(b, env)) Some(GrammarError(pos, "nullable expression inside `+` can cause infinite loop", Some("rewrite repeated expression so it always consumes input")))
        else nullableRepetition(b, env)
      case Optional(_, b) => nullableRepetition(b, env)
      case AndPredicate(_, b) => nullableRepetition(b, env)
      case NotPredicate(_, b) => nullableRepetition(b, env)
      case Call(_, _, args) =>
        args.foldLeft(Option.empty[GrammarError])((acc, a) => acc.orElse(nullableRepetition(a, env)))
      case Function(_, args, body) => nullableRepetition(body, env ++ args.toSet)
      case Labeled(_, _, b) => nullableRepetition(b, env)
      case Cut(_, b) => nullableRepetition(b, env)
      case SemanticAction(_, _) => None
      case Debug(_, b) => nullableRepetition(b, env)
      case ActionBlock(_, b, _) => nullableRepetition(b, env)
      case LeftProject(_, l, r) => nullableRepetition(l, env).orElse(nullableRepetition(r, env))
      case RightProject(_, l, r) => nullableRepetition(l, env).orElse(nullableRepetition(r, env))
      case IgnoredExpr(_, e) => nullableRepetition(e, env)
      case _ => None
    }

    // Skip nullable-repetition check for higher-order rules (args.nonEmpty): parameters are
    // macro-expanded at call sites and validated there; treating them as nullable here
    // produces false positives for rules like SepBy0(elem, sep) = (elem (sep elem)*)?.
    val repetitionError = grammar.rules.iterator
      .filter(_.args.isEmpty)
      .map(r => nullableRepetition(r.body, r.args.toSet))
      .collectFirst { case Some(err) => err }
    if(repetitionError.nonEmpty) return Left(repetitionError.get)

    def leadsToSelf(sym: Symbol, exp: Expression, env: Set[Symbol], visited: Set[Symbol]): Option[Position] = exp match {
      case Sequence(_, l, r) =>
        leadsToSelf(sym, l, env, visited)
          .orElse(if(nullableExpression(l, env)) leadsToSelf(sym, r, env, visited) else None)
      case Alternation(_, l, r) =>
        leadsToSelf(sym, l, env, visited).orElse(leadsToSelf(sym, r, env, visited))
      case Repeat0(_, b) => leadsToSelf(sym, b, env, visited)
      case Repeat1(_, b) => leadsToSelf(sym, b, env, visited)
      case Optional(_, b) => leadsToSelf(sym, b, env, visited)
      case AndPredicate(_, b) => leadsToSelf(sym, b, env, visited)
      case NotPredicate(_, b) => leadsToSelf(sym, b, env, visited)
      case Call(pos, name, _) =>
        if(name == sym) Some(pos)
        else if(ruleMapping.contains(name) && nullable(name) && !visited(name))
          leadsToSelf(sym, ruleMapping(name).body, ruleMapping(name).args.toSet, visited + name)
        else None
      case Identifier(pos, name) =>
        if(name == sym) Some(pos)
        else if(ruleMapping.contains(name) && nullable(name) && !visited(name))
          leadsToSelf(sym, ruleMapping(name).body, ruleMapping(name).args.toSet, visited + name)
        else None
      case Function(_, args, body) => leadsToSelf(sym, body, env ++ args.toSet, visited)
      case Labeled(_, _, b) => leadsToSelf(sym, b, env, visited)
      case Cut(_, b) => leadsToSelf(sym, b, env, visited)
      case SemanticAction(_, _) => None
      case Debug(_, b) => leadsToSelf(sym, b, env, visited)
      case ActionBlock(_, b, _) => leadsToSelf(sym, b, env, visited)
      case LeftProject(_, l, r) =>
        leadsToSelf(sym, l, env, visited)
          .orElse(if(nullableExpression(l, env)) leadsToSelf(sym, r, env, visited) else None)
      case RightProject(_, l, r) =>
        leadsToSelf(sym, l, env, visited)
          .orElse(if(nullableExpression(l, env)) leadsToSelf(sym, r, env, visited) else None)
      case IgnoredExpr(_, e) => leadsToSelf(sym, e, env, visited)
      case _ => None
    }

    val leftRecursionError = grammar.rules.iterator
      .filter(_.args.isEmpty)
      .map(r => leadsToSelf(r.name, r.body, r.args.toSet, Set.empty).map { pos =>
        GrammarError(pos, s"left recursion detected for rule `${r.name.name}`", Some("remove direct or nullable-indirect left recursion"))
      })
      .collectFirst { case Some(err) => err }
    if(leftRecursionError.nonEmpty) return Left(leftRecursionError.get)

    Right(())
  }
}
