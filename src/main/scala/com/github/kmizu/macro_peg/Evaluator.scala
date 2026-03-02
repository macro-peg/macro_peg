package com
package github
package kmizu
package macro_peg

import scala.collection.mutable

import com.github.kmizu.macro_peg.Ast.Position
import com.github.kmizu.macro_peg.EvaluationResult.{Success, Failure}

case class Evaluator(grammar: Ast.Grammar, strategy: EvaluationStrategy = EvaluationStrategy.CallByName) {
  private def expand(node: Ast.Expression): Ast.Expression = node match {
    case Ast.CharClass(pos, positive, elems) =>
      Ast.CharSet(pos, positive, elems.foldLeft(Set[Char]()){
        case (set, Ast.CharRange(f, t)) => (f to t).foldLeft(set)((s, c) => s + c)
        case (set, Ast.OneChar(c)) => set + c
      })
    case Ast.Alternation(pos, e1, e2) => Ast.Alternation(pos, expand(e1), expand(e2))
    case Ast.Sequence(pos, e1, e2) => Ast.Sequence(pos, expand(e1), expand(e2))
    case Ast.Repeat0(pos, body) => Ast.Repeat0(pos, expand(body))
    case Ast.Repeat1(pos, body) => Ast.Repeat1(pos, expand(body))
    case Ast.Optional(pos, body) => Ast.Optional(pos, expand(body))
    case Ast.AndPredicate(pos, body) => Ast.AndPredicate(pos, expand(body))
    case Ast.NotPredicate(pos, body) => Ast.NotPredicate(pos, expand(body))
    case Ast.Call(pos, name, args) => Ast.Call(pos, name, args.map(expand))
    case e => e
  }

  private val FUNS: Map[Symbol, Ast.Expression] = {
    grammar.rules.map { r =>
      r.name -> (if(r.args.isEmpty) expand(r.body) else Ast.Function(r.body.pos, r.args, expand(r.body)))
    }.toMap
  }

  private case class FailureInfo(
    offset: Int,
    expected: String,
    pos: Position,
    ruleStack: List[Symbol],
    hint: Option[String] = None
  )
  private case class MemoKey(remainingLength: Int, expression: Ast.Expression, envHash: Int)

  private def envHash(bindings: Map[Symbol, Ast.Expression]): Int = {
    bindings.foldLeft(1) { case (acc, (k, v)) => 31 * acc + k.## + v.## }
  }

  private def expectation(exp: Ast.Expression): String = exp match {
    case Ast.StringLiteral(_, s) => "\"" + s + "\""
    case Ast.Wildcard(_) => "any character"
    case Ast.CharSet(_, true, set) =>
      val sample = set.take(8).mkString(",")
      s"one of [$sample${if(set.size > 8) ",..." else ""}]"
    case Ast.CharSet(_, false, _) => "a character outside a negative character set"
    case Ast.Identifier(_, name) => s"rule `${name.name}`"
    case Ast.Call(_, name, _) => s"call `${name.name}`"
    case Ast.Optional(_, _) => "optional expression"
    case Ast.Repeat0(_, _) => "repetition"
    case Ast.Repeat1(_, _) => "repetition"
    case Ast.AndPredicate(_, _) => "lookahead"
    case Ast.NotPredicate(_, _) => "negative lookahead"
    case Ast.Sequence(_, _, _) => "sequence"
    case Ast.Alternation(_, _, _) => "alternative"
    case Ast.Function(_, _, _) => "function"
    case Ast.Debug(_, _) => "debug expression"
    case Ast.CharClass(_, _, _) => "character class"
  }

  private def lineColFromOffset(input: String, offset: Int): Position = {
    val clamped = math.max(0, math.min(input.length, offset))
    val prefix = input.substring(0, clamped)
    val line = prefix.count(_ == '\n')
    val column = prefix.reverseIterator.takeWhile(_ != '\n').length
    Position(line, column)
  }

  private def evalDetailed(sourceInput: String, start: Symbol, useMemoization: Boolean): Either[Diagnostic, Success] = {
    val bodyOpt = FUNS.get(start)
    if(bodyOpt.isEmpty) {
      return Left(Diagnostic(
        phase = DiagnosticPhase.Evaluation,
        message = s"undefined start rule: ${start.name}",
        hint = Some("define the start rule or pass another start symbol")
      ))
    }

    val memo = mutable.HashMap.empty[MemoKey, EvaluationResult]
    var farthestFailure: Option[FailureInfo] = None

    def registerFailure(currentInput: String, exp: Ast.Expression, ruleStack: List[Symbol], overrideExpected: Option[String] = None, hint: Option[String] = None): Unit = {
      val offset = sourceInput.length - currentInput.length
      val info = FailureInfo(
        offset = offset,
        expected = overrideExpected.getOrElse(expectation(exp)),
        pos = exp.pos,
        ruleStack = ruleStack.reverse,
        hint = hint
      )
      farthestFailure match {
        case Some(prev) if prev.offset > info.offset =>
          ()
        case _ =>
          farthestFailure = Some(info)
      }
    }

    def evaluateIn(currentInput: String, exp: Ast.Expression, bindings: Map[Symbol, Ast.Expression], ruleStack: List[Symbol]): EvaluationResult = {
      if(useMemoization) {
        val key = MemoKey(currentInput.length, exp, envHash(bindings))
        memo.getOrElseUpdate(key, evaluateWithoutMemo(currentInput, exp, bindings, ruleStack))
      } else {
        evaluateWithoutMemo(currentInput, exp, bindings, ruleStack)
      }
    }

    def evaluateWithoutMemo(input: String, exp: Ast.Expression, bindings: Map[Symbol, Ast.Expression], ruleStack: List[Symbol]): EvaluationResult = exp match {
      case Ast.Debug(_, body) =>
        evaluateIn(input, body, bindings, ruleStack)
      case Ast.Alternation(_, l, r) =>
        evaluateIn(input, l, bindings, ruleStack).orElse(evaluateIn(input, r, bindings, ruleStack))
      case Ast.Sequence(_, l, r) =>
        for(in1 <- evaluateIn(input, l, bindings, ruleStack);
            in2 <- evaluateIn(in1, r, bindings, ruleStack)) yield in2
      case Ast.AndPredicate(_, body) =>
        evaluateIn(input, body, bindings, ruleStack).map(_ => input)
      case Ast.NotPredicate(_, body) =>
        evaluateIn(input, body, bindings, ruleStack) match {
          case Success(_) =>
            registerFailure(input, exp, ruleStack, Some("expression should not match"))
            Failure
          case Failure => Success(input)
        }
      case Ast.Call(_, name, params) =>
        val evaluated = bindings.get(name) match {
          case Some(fun: Ast.Function) =>
            val args = fun.args
            val body = fun.body
            if(args.length != params.length) {
              registerFailure(input, exp, ruleStack, Some(s"${name.name} expects ${args.length} arguments"), Some("fix macro/function arity"))
              Left(Failure)
            } else {
              strategy match {
                case EvaluationStrategy.CallByName =>
                  Right(input -> args.zip(params.map(p => extract(p, bindings))).toMap)
                case EvaluationStrategy.CallByValueSeq =>
                  def loop(current: String, ps: List[Ast.Expression], values: List[Ast.Expression]): Either[Failure.type, (String, List[Ast.Expression])] = ps match {
                    case p :: rest =>
                      evaluateIn(current, p, bindings, ruleStack) match {
                        case Success(next) =>
                          val value = Ast.StringLiteral(Position(-1, -1), current.substring(0, current.length - next.length))
                          loop(next, rest, value :: values)
                        case Failure =>
                          Left(Failure)
                      }
                    case Nil =>
                      Right(current -> values.reverse)
                  }
                  loop(input, params, Nil).map { case (nextInput, values) => nextInput -> args.zip(values).toMap }
                case EvaluationStrategy.CallByValuePar =>
                  val results = params.map(p => evaluateIn(input, p, bindings, ruleStack))
                  if(results.forall(_.isSuccess)) {
                    val values = results.map(_.asInstanceOf[Success].get).map { rest =>
                      Ast.StringLiteral(Position(-1, -1), input.substring(0, input.length - rest.length))
                    }
                    Right(input -> args.zip(values).toMap)
                  } else {
                    Left(Failure)
                  }
              }
            }
          case Some(other) =>
            registerFailure(input, exp, ruleStack, Some(s"${name.name} is not callable"), Some(s"use `${name.name}` as identifier instead of call"))
            Left(Failure)
          case None =>
            registerFailure(input, exp, ruleStack, Some(s"undefined function or macro `${name.name}`"), Some("define the function/macro before use"))
            Left(Failure)
        }

        evaluated match {
          case Left(_) =>
            Failure
          case Right((nextInput, nparams)) =>
            val nextStack = name :: ruleStack
            val body = bindings(name).asInstanceOf[Ast.Function].body
            evaluateIn(nextInput, body, bindings ++ nparams, nextStack)
        }

      case Ast.Identifier(_, name) =>
        bindings.get(name) match {
          case Some(body) =>
            evaluateIn(input, body, bindings, name :: ruleStack)
          case None =>
            registerFailure(input, exp, ruleStack, Some(s"undefined identifier `${name.name}`"), Some("define this rule/variable before use"))
            Failure
        }
      case Ast.Optional(_, body) =>
        evaluateIn(input, body, bindings, ruleStack).orElse(Success(input))
      case Ast.Repeat0(_, body) =>
        var current = input
        var result: EvaluationResult = evaluateIn(current, body, bindings, ruleStack)
        while(result != Failure) {
          val next = result.get
          if(next == current) {
            registerFailure(current, exp, ruleStack, Some("nullable repetition detected at runtime"), Some("run GrammarValidator before evaluating this grammar"))
            return Failure
          }
          current = next
          result = evaluateIn(current, body, bindings, ruleStack)
        }
        Success(current)
      case Ast.Repeat1(_, body) =>
        evaluateIn(input, body, bindings, ruleStack) match {
          case Failure => Failure
          case Success(firstNext) =>
            var current = firstNext
            var result: EvaluationResult = evaluateIn(current, body, bindings, ruleStack)
            while(result != Failure) {
              val next = result.get
              if(next == current) {
                registerFailure(current, exp, ruleStack, Some("nullable repetition detected at runtime"), Some("run GrammarValidator before evaluating this grammar"))
                return Failure
              }
              current = next
              result = evaluateIn(current, body, bindings, ruleStack)
            }
            Success(current)
        }
      case Ast.StringLiteral(_, target) =>
        if(input.startsWith(target)) Success(input.substring(target.length))
        else {
          registerFailure(input, exp, ruleStack)
          Failure
        }
      case Ast.CharSet(_, positive, set) =>
        if(input.isEmpty || (positive != set(input(0)))) {
          registerFailure(input, exp, ruleStack)
          Failure
        } else {
          Success(input.substring(1))
        }
      case Ast.Wildcard(_) =>
        if(input.length >= 1) Success(input.substring(1))
        else {
          registerFailure(input, exp, ruleStack)
          Failure
        }
      case Ast.CharClass(_, _, _) =>
        registerFailure(input, exp, ruleStack, Some("unexpected raw CharClass in evaluator"), Some("pre-expand CharClass to CharSet"))
        Failure
      case Ast.Function(_, _, _) =>
        registerFailure(input, exp, ruleStack, Some("function object cannot be evaluated directly"))
        Failure
    }

    evaluateIn(sourceInput, bodyOpt.get, FUNS, start :: Nil) match {
      case success@Success(_) =>
        Right(success)
      case Failure =>
        val diagnostic = farthestFailure match {
          case Some(info) =>
            val snippet = Diagnostic.inputSnippet(sourceInput, info.offset)
            Diagnostic(
              phase = DiagnosticPhase.Evaluation,
              message = s"failed to match input at offset ${info.offset}",
              position = Some(if(info.pos == Ast.DUMMY_POSITION) lineColFromOffset(sourceInput, info.offset) else info.pos),
              inputOffset = Some(info.offset),
              expected = List(info.expected),
              ruleStack = info.ruleStack,
              snippet = Some(snippet),
              hint = info.hint
            )
          case None =>
            Diagnostic(
              phase = DiagnosticPhase.Evaluation,
              message = "failed to evaluate grammar against input",
              hint = Some("enable evaluateEither/evaluateWithDiagnostics to inspect failure details")
            )
        }
        Left(diagnostic)
    }
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

  def evaluateWithDiagnostics(input: String, start: Symbol): Either[Diagnostic, Success] = {
    evalDetailed(input, start, useMemoization = true)
  }

  def evaluateWithoutMemo(input: String, start: Symbol): Either[Diagnostic, Success] = {
    evalDetailed(input, start, useMemoization = false)
  }

  def evaluate(input: String, start: Symbol): EvaluationResult = {
    evaluateWithDiagnostics(input, start) match {
      case Right(success) => success
      case Left(_) => Failure
    }
  }
}
