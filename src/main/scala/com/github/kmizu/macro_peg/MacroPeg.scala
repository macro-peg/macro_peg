package com.github.kmizu.macro_peg

/**
  * Utility class that combines parsing, type checking and evaluation.
  * An instance of this class guarantees that the grammar passed the
  * [[TypeChecker]] before evaluation.
  */
case class MacroPeg private (grammar: Ast.Grammar, strategy: EvaluationStrategy) {
  private val evaluator = Evaluator(grammar, strategy)

  def evaluate(input: String, start: Symbol = Symbol("S")): EvaluationResult =
    evaluator.evaluate(input, start)
}

object MacroPeg {
  /**
    * Parses `source`, type checks it and, if successful, returns a [[MacroPeg]]
    * instance.
    */
  def fromString(source: String, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Either[MacroPegError, MacroPeg] = {
    val grammar =
      try {
        Parser.parse(source)
      } catch {
        case Parser.ParseException(pos, msg) => return Left(ParseError(pos, msg))
      }
    fromGrammar(grammar, strategy)
  }

  /**
    * Type checks the given grammar and, if successful, returns a [[MacroPeg]]
    * instance.
    */
  def fromGrammar(grammar: Ast.Grammar, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Either[MacroPegError, MacroPeg] = {
    val checker = new TypeChecker(grammar)
    checker.check() match {
      case Left(err) => Left(err)
      case Right(_)  => Right(MacroPeg(grammar, strategy))
    }
  }
}
