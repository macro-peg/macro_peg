package com
package github
package kmizu
package macro_peg

case class MacroPEGEvaluator(grammar: Ast.Grammar) {
  private def expand(node: Ast.Expression): Ast.Expression = node match {
    case Ast.CharClass(pos, positive, elems) =>
      Ast.CharSet(pos, positive, elems.foldLeft(Set[Char]()){
        case (set, Ast.CharRange(f, t)) => (set /: (f to t))((set, c) => set + c)
        case (set, Ast.OneChar(c)) => set + c
      })
    case Ast.Alternation(pos, e1, e2) => Ast.Alternation(pos, expand(e1), expand(e2))
    case Ast.Sequence(pos, e1, e2) => Ast.Sequence(pos, expand(e1), expand(e2))
    case Ast.Repeat0(pos, body) => Ast.Repeat0(pos, expand(body))
    case Ast.Repeat1(pos, body) => Ast.Repeat1(pos, expand(body))
    case Ast.Optional(pos, body) => Ast.Optional(pos, expand(body))
    case Ast.AndPredicate(pos, body) => Ast.AndPredicate(pos, expand(body))
    case Ast.NotPredicate(pos, body) => Ast.NotPredicate(pos, expand(body))
    case e => e
  }
  private val FUNS: Map[Symbol, Ast.Expression] = {
    grammar.rules.map{r => r.name -> (if(r.args.isEmpty) expand(r.body) else Ast.Function(r.body.pos, r.args, expand(r.body)))}.toMap
  }

  private[this] def eval(input: String, exp: Ast.Expression): Option[String] = {
    def evaluateIn(input: String, exp: Ast.Expression, bindings: Map[Symbol, Ast.Expression]): Option[String] = exp match {
      case Ast.Debug(pos, body) =>
        println("DEBUG: " + extract(body, bindings))
        Some(input)
      case Ast.Alternation(pos, l, r) =>
        evaluateIn(input, l, bindings).orElse(evaluateIn(input, r, bindings))
      case Ast.Sequence(pos, l, r) =>
        for(in <- evaluateIn(input, l, bindings);
            in <- evaluateIn(in, r, bindings)) yield in
      case Ast.AndPredicate(pos, body) =>
        evaluateIn(input, body, bindings).map{_ => input}
      case Ast.NotPredicate(pos, body) =>
        evaluateIn(input, body, bindings) match {
          case Some(in) => None
          case None => Some(input)
        }
      case Ast.Call(pos, name, params) =>
        val fun = bindings(name).asInstanceOf[Ast.Function]
        val args = fun.args
        val body = fun.body
        if(args.length != params.length) throw EvaluationException(s"args length of $name should be equal to params length")
        val nparams = args.zip(params.map(p => extract(p, bindings))).toMap
        evaluateIn(input, body, bindings ++ nparams)
      case Ast.Identifier(pos, name) =>
        val body = bindings(name)
        evaluateIn(input, body, bindings)
      case Ast.Optional(pos, body) =>
        evaluateIn(input, body, bindings).orElse(Some(input))
      case Ast.Repeat0(pos, body) =>
        var in = input
        var result: Option[String] = None
        while({result = evaluateIn(in, body, bindings); result != None}) {
          in = result.get
        }
        Some(in)
      case Ast.Repeat1(pos, body) =>
        var in = input
        var result = evaluateIn(in, body, bindings)
        if(result.isEmpty) return None
        in = result.get
        while({result = evaluateIn(in, body, bindings); result != None}) {
          in = result.get
        }
        Some(in)
      case Ast.StringLiteral(pos, target) =>
        if (input.startsWith(target)) Some(input.substring(target.length)) else None
      case Ast.CharSet(_, positive, set) =>
        if(input == "" || (positive != set(input(0)))) None
        else Some(input.substring(1))
      case Ast.Wildcard(pos) =>
        if (input.length >= 1) Some(input.substring(1)) else None
      case Ast.CharClass(_, _, _) => sys.error("must be unreachable")
      case Ast.Function(_, _, _) => sys.error("must be unreachable")
    }
    evaluateIn(input, exp, FUNS)
  }

  private[this] def extract(exp: Ast.Expression, bindings: Map[Symbol, Ast.Expression]): Ast.Expression = exp match {
    case Ast.Debug(pos, body) =>
      Ast.Debug(pos, extract(body, bindings))
    case Ast.Alternation(pos, l, r) =>
      Ast.Alternation(pos, extract(l, bindings), extract(r, bindings))
    case Ast.Sequence(pos, l, r) =>
      Ast.Sequence(pos, extract(l, bindings), extract(r, bindings))
    case Ast.AndPredicate(pos, body) =>
      Ast.AndPredicate(pos, extract(body, bindings))
    case Ast.NotPredicate(pos, body) =>
      Ast.NotPredicate(pos, extract(body, bindings))
    case Ast.Call(pos, name, params) =>
      Ast.Call(pos, name, params.map(r => extract(r, bindings)))
    case Ast.Identifier(pos, name) =>
      bindings.getOrElse(name, Ast.Identifier(pos, name))
    case Ast.Function(pos, args, body) =>
      Ast.Function(pos, args, body)
    case Ast.Optional(pos, body) =>
      Ast.Optional(pos, extract(body, bindings))
    case Ast.Repeat0(pos, body) =>
      Ast.Repeat0(pos, extract(body, bindings))
    case Ast.Repeat1(pos, body) =>
      Ast.Repeat1(pos, extract(body, bindings))
    case Ast.StringLiteral(pos, target) =>
      Ast.StringLiteral(pos, target)
    case Ast.Wildcard(pos) =>
      Ast.Wildcard(pos)
    case ast@Ast.CharClass(_, _, _) => ast
    case ast@Ast.CharSet(_, _, _) => ast
  }

  def evaluate(input: String, start: Symbol): Option[String] = {
    val body = FUNS(start)
    eval(input, body).map{str => input.substring(0, input.length - str.length)}
  }
}
