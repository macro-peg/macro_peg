package com
package github
package kmizu
package hopeg

import com.github.kmizu.hopeg.Ast.Exp

case class HOPEGEvaluator(grammar: Ast.Grammar) {
  private[this] val rules: Map[Symbol, (List[Symbol], Exp)] = {
    grammar.rules.map{r => (r.name, (r.args, r.body))}.toMap
  }

  private[this] def evaluate(input: String, exp: Ast.Exp, bindings: Map[Symbol, Ast.Exp]): Option[String] = exp match {
    case Ast.Alt(pos, l, r) =>
      evaluate(input, l, bindings).orElse(evaluate(input, r, bindings))
    case Ast.Seq(pos, l, r) =>
      for(in1 <- evaluate(input, l, bindings);
          in2 <- evaluate(in1, r, bindings)) yield in2
    case Ast.AndPred(pos, body) =>
      evaluate(input, body, bindings).map{_ => input}
    case Ast.NotPred(pos, body) =>
      evaluate(input, body, bindings) match {
        case Some(in) => None
        case None => Some(input)
      }
    case Ast.Call(pos, name, params) =>
      val (args, body) = rules(name)
      val insts = params.map(p => extract(p, bindings))
      evaluate(input, body, args.zip(insts).toMap)
    case Ast.Ident(pos, name) =>
      val body = bindings.getOrElse(name, {
        val (Nil, body) = rules(name)
        body
      })
      evaluate(input, body, bindings)
    case Ast.Opt(pos, body) =>
      evaluate(input, body, bindings).orElse(Some(input))
    case Ast.Rep0(pos, body) =>
      var in = input
      var result: Option[String] = None
      while({result = evaluate(in, body, bindings); result != None}) {
        in = result.get
      }
      Some(in)
    case Ast.Rep1(pos, body) =>
      var in = input
      var result = evaluate(in, body, bindings)
      if(result.isEmpty) return None
      in = result.get
      while({result = evaluate(in, body, bindings); result != None}) {
        in = result.get
      }
      Some(in)
    case Ast.Str(pos, target) =>
      if (input.startsWith(target)) Some(input.substring(target.length)) else None
    case Ast.Wildcard(pos) =>
      if (input.length >= 1) Some(input.substring(1)) else None
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
      bindings.get(name).getOrElse(Ast.Ident(pos, name))
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
    val (Nil, body) = rules(start)
    evaluate(input, body, Map.empty)
  }
}
