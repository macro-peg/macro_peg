package com
package github
package kmizu
package hopeg

import com.github.kmizu.hopeg.Ast.Exp

case class HOPEGEvaluator(grammar: Ast.Grammar) {
  private val FUNS: Map[Symbol, Ast.Exp] = {
    grammar.rules.map{r => r.name -> Ast.Fun(r.body.pos, r.args, r.body)}.toMap
  }

  private[this] def eval(input: String, exp: Ast.Exp): Option[String] = {
    def evaluateIn(input: String, exp: Ast.Exp, bindings: Map[Symbol, Ast.Exp]): Option[String] = exp match {
      case Ast.Alt(pos, l, r) =>
        evaluateIn(input, l, bindings).orElse(evaluateIn(input, r, bindings))
      case Ast.Seq(pos, l, r) =>
        for(in <- evaluateIn(input, l, bindings);
            in <- evaluateIn(in, r, bindings)) yield in
      case Ast.AndPred(pos, body) =>
        evaluateIn(input, body, bindings).map{_ => input}
      case Ast.NotPred(pos, body) =>
        evaluateIn(input, body, bindings) match {
          case Some(in) => None
          case None => Some(input)
        }
      case Ast.Call(pos, name, params) =>
        val fun = bindings(name).asInstanceOf[Ast.Fun]
        val args = fun.args
        val body = fun.body
        val nparams = args.zip(params.map(p => extract(p, bindings))).toMap
        evaluateIn(input, body, bindings ++ nparams)
      case Ast.Ident(pos, name) =>
        val body = bindings(name)
        evaluateIn(input, body, bindings)
      case Ast.Opt(pos, body) =>
        evaluateIn(input, body, bindings).orElse(Some(input))
      case Ast.Rep0(pos, body) =>
        var in = input
        var result: Option[String] = None
        while({result = evaluateIn(in, body, bindings); result != None}) {
          in = result.get
        }
        Some(in)
      case Ast.Rep1(pos, body) =>
        var in = input
        var result = evaluateIn(in, body, bindings)
        if(result.isEmpty) return None
        in = result.get
        while({result = evaluateIn(in, body, bindings); result != None}) {
          in = result.get
        }
        Some(in)
      case Ast.Str(pos, target) =>
        if (input.startsWith(target)) Some(input.substring(target.length)) else None
      case Ast.Wildcard(pos) =>
        if (input.length >= 1) Some(input.substring(1)) else None
    }
    evaluateIn(input, exp, FUNS)
  }

  private[this] def extract(exp: Ast.Exp, bindings: Map[Symbol, Ast.Exp]): Ast.Exp = exp match {
    case Ast.Alt(pos, l, r) =>
      Ast.Alt(pos, extract(l, bindings), extract(r, bindings))
    case Ast.Seq(pos, l, r) =>
      Ast.Seq(pos, extract(l, bindings), extract(r, bindings))
    case Ast.AndPred(pos, body) =>
      Ast.AndPred(pos, extract(body, bindings))
    case Ast.NotPred(pos, body) =>
      Ast.NotPred(pos, extract(body, bindings))
    case Ast.Call(pos, name, params) =>
      Ast.Call(pos, name, params.map(r => extract(r, bindings)))
    case Ast.Ident(pos, name) =>
      bindings.getOrElse(name, Ast.Ident(pos, name))
    case Ast.Fun(pos, args, body) =>
      Ast.Fun(pos, args, body)
    case Ast.Opt(pos, body) =>
      Ast.Opt(pos, extract(body, bindings))
    case Ast.Rep0(pos, body) =>
      Ast.Rep0(pos, extract(body, bindings))
    case Ast.Rep1(pos, body) =>
      Ast.Rep1(pos, extract(body, bindings))
    case Ast.Str(pos, target) =>
      Ast.Str(pos, target)
    case Ast.Wildcard(pos) =>
      Ast.Wildcard(pos)
  }

  def evaluate(input: String, start: Symbol): Option[String] = {
    val body = FUNS(start).asInstanceOf[Ast.Fun].body
    eval(input, body)
  }
}
