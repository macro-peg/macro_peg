package com
package github
package kmizu
package hopeg

import com.github.kmizu.hopeg.Ast.Exp

class HOPEGEvaluator(grammar: Ast.Grammar) {
  private[this] val rules: Map[Symbol, (List[Symbol], Exp)] = {
    grammar.rules.map{r => (r.name, (r.args, r.body))}.toMap
  }
  def evaluate(input: String, start: Symbol): Option[String] = {
    val (Nil, body) = rules(start)
    evaluate(input, body)
  }

  def evaluate(input: String, exp: Ast.Exp): Option[String] = exp match {
    case Ast.Alt(pos, l, r) =>
      evaluate(input, l).orElse(evaluate(input, r))
    case Ast.Seq(pos, l, r) =>
      for(in1 <- evaluate(input, l);
          in2 <- evaluate(in1, r)) yield in2
    case Ast.AndPred(pos, body) =>
      evaluate(input, body).map{_ => input}
    case Ast.NotPred(pos, body) =>
      evaluate(input, body) match {
        case Some(in) => None
        case None => Some(input)
      }
    case Ast.Call(pos, name, params) =>
      None
    case Ast.Ident(pos, name) =>
      val (Nil, body) = rules(name)
      evaluate(input, body)
    case Ast.Opt(pos, body) =>
      evaluate(input, body).orElse(Some(input))
    case Ast.Rep0(pos, body) =>
      var in = input
      var result: Option[String] = None
      while({result = evaluate(in, body); result != None}) {
        in = result.get
      }
      Some(in)
    case Ast.Rep1(pos, body) =>
      var in = input
      var result = evaluate(in, body)
      if(result.isEmpty) return None
      in = result.get
      while({result = evaluate(in, body); result != None}) {
        in = result.get
      }
      Some(in)
    case Ast.Str(pos, target) =>
      if (input.startsWith(target)) Some(input.substring(target.length)) else None
    case Ast.Wildcard(pos) =>
      if (input.length >= 1) Some(input.substring(1)) else None
  }
}
