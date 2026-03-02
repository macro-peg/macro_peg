package com.github.kmizu.macro_peg

import Ast._

case class TypeCheckException(pos: Position, message: String) extends Exception(s"${pos.line}, ${pos.column}: ${message}")

object Interpreter {
  def fromSourceEither(source: String, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Either[Diagnostic, Interpreter] = {
    val parsed = try {
      Right(Parser.parse(source))
    } catch {
      case Parser.ParseException(pos, msg) =>
        Left(Diagnostic(
          phase = DiagnosticPhase.Parse,
          message = msg,
          position = Some(pos),
          hint = Some("check grammar syntax near this location")
        ))
    }
    parsed.flatMap(grammar => fromGrammarEither(grammar, strategy))
  }

  def fromSource(source: String, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Interpreter = {
    fromSourceEither(source, strategy) match {
      case Right(interpreter) => interpreter
      case Left(diagnostic) =>
        throw (diagnostic.phase match {
          case DiagnosticPhase.TypeCheck =>
            TypeCheckException(diagnostic.position.getOrElse(DUMMY_POSITION), diagnostic.message)
          case DiagnosticPhase.WellFormedness =>
            GrammarValidationException(diagnostic.position.getOrElse(DUMMY_POSITION), diagnostic.message)
          case _ =>
            DiagnosticException(diagnostic)
        })
    }
  }

  def fromGrammarEither(grammar: Grammar, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Either[Diagnostic, Interpreter] = {
    val validated = GrammarValidator.validate(grammar).left.map { err =>
      Diagnostic(
        phase = DiagnosticPhase.WellFormedness,
        message = err.message,
        position = Some(err.pos),
        hint = err.hint
      )
    }
    validated.flatMap { _ =>
      val checker = new TypeChecker(grammar)
      checker.check() match {
        case Left(TypeError(pos, msg)) =>
          Left(Diagnostic(
            phase = DiagnosticPhase.TypeCheck,
            message = msg,
            position = Some(pos),
            hint = Some("fix type annotations or function arguments")
          ))
        case Right(_) =>
          Right(new Interpreter(grammar, strategy))
      }
    }
  }

  def fromGrammar(grammar: Grammar, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Interpreter = {
    fromGrammarEither(grammar, strategy) match {
      case Right(interpreter) => interpreter
      case Left(diagnostic) =>
        throw (diagnostic.phase match {
          case DiagnosticPhase.TypeCheck =>
            TypeCheckException(diagnostic.position.getOrElse(DUMMY_POSITION), diagnostic.message)
          case DiagnosticPhase.WellFormedness =>
            GrammarValidationException(diagnostic.position.getOrElse(DUMMY_POSITION), diagnostic.message)
          case _ =>
            DiagnosticException(diagnostic)
        })
    }
  }
}

class Interpreter private (grammar: Grammar, strategy: EvaluationStrategy) {
  private val evaluator = Evaluator(grammar, strategy)

  def evaluateEither(input: String): Either[Diagnostic, EvaluationResult.Success] =
    evaluateEither(input, Symbol("S"))

  def evaluateEither(input: String, start: Symbol): Either[Diagnostic, EvaluationResult.Success] = {
    evaluator.evaluateWithDiagnostics(input, start)
  }

  def evaluate(input: String): EvaluationResult =
    evaluate(input, Symbol("S"))

  def evaluate(input: String, start: Symbol): EvaluationResult = {
    evaluator.evaluate(input, start)
  }
}
