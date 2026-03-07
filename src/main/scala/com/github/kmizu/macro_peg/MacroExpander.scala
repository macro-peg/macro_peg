package com.github.kmizu.macro_peg

/**
 * Utility to expand macro calls syntactically. This is a very
 * naive implementation intended as a first step toward
 * higher-order macro support.
 */
object MacroExpander {
  import Ast._

  private def substitute(exp: Expression, env: Map[Symbol, Expression]): Expression = exp match {
    case Identifier(pos, name) if env.contains(name) => env(name)
    case Sequence(pos, l, r) => Sequence(pos, substitute(l, env), substitute(r, env))
    case Alternation(pos, l, r) => Alternation(pos, substitute(l, env), substitute(r, env))
    case Repeat0(pos, b) => Repeat0(pos, substitute(b, env))
    case Repeat1(pos, b) => Repeat1(pos, substitute(b, env))
    case Optional(pos, b) => Optional(pos, substitute(b, env))
    case AndPredicate(pos, b) => AndPredicate(pos, substitute(b, env))
    case NotPredicate(pos, b) => NotPredicate(pos, substitute(b, env))
    case Call(pos, name, params) if env.contains(name) =>
      env(name) match {
        case Identifier(_, target) =>
          Call(pos, target, params.map(p => substitute(p, env)))
        case Function(_, fArgs, fBody) =>
          val newParams = params.map(p => substitute(p, env))
          val newEnv = fArgs.zip(newParams).toMap
          substitute(fBody, newEnv)
        case _ =>
          Call(pos, name, params.map(p => substitute(p, env)))
      }
    case Call(pos, name, params) => Call(pos, name, params.map(p => substitute(p, env)))
    case Function(pos, args, body) => Function(pos, args, substitute(body, env))
    case Debug(pos, b) => Debug(pos, substitute(b, env))
    case Labeled(pos, label, b) => Labeled(pos, label, substitute(b, env))
    case Cut(pos, b) => Cut(pos, substitute(b, env))
    case SemanticAction(_, _) => exp  // action code is opaque Scala string — don't touch
    case other => other
  }

  private def expand(exp: Expression, rules: Map[Symbol, Rule]): Expression = {
    // Expansion of a recursive higher-order grammar never terminates; callers run it on a
    // worker thread with a timeout and interrupt it, so bail out as soon as that happens.
    if(Thread.currentThread().isInterrupted) throw new InterruptedException("macro expansion cancelled")
    expandNode(exp, rules)
  }

  private def expandNode(exp: Expression, rules: Map[Symbol, Rule]): Expression = exp match {
    case Call(pos, name, params) if rules.contains(name) =>
      val rule = rules(name)
      val newParams = params.map(p => expand(p, rules))
      val env = rule.args.zip(newParams).toMap
      val replaced = substitute(rule.body, env)
      expand(replaced, rules)
    case Sequence(pos, l, r) => Sequence(pos, expand(l, rules), expand(r, rules))
    case Alternation(pos, l, r) => Alternation(pos, expand(l, rules), expand(r, rules))
    case Repeat0(pos, b) => Repeat0(pos, expand(b, rules))
    case Repeat1(pos, b) => Repeat1(pos, expand(b, rules))
    case Optional(pos, b) => Optional(pos, expand(b, rules))
    case AndPredicate(pos, b) => AndPredicate(pos, expand(b, rules))
    case NotPredicate(pos, b) => NotPredicate(pos, expand(b, rules))
    case Function(pos, args, body) => Function(pos, args, expand(body, rules))
    case Debug(pos, b) => Debug(pos, expand(b, rules))
    case Labeled(pos, label, b) => Labeled(pos, label, expand(b, rules))
    case Cut(pos, b) => Cut(pos, expand(b, rules))
    case SemanticAction(_, _) => exp  // action code is opaque Scala string — don't touch
    case other => other
  }

  def expandGrammar(grammar: Grammar): Grammar = {
    val ruleMap = grammar.rules.map(r => r.name -> r).toMap
    val newRules = grammar.rules.map { r =>
      r.copy(body = expand(r.body, ruleMap))
    }
    grammar.copy(rules = newRules)
  }
}
