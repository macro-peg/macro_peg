package com.github.kmizu.macro_peg

import Ast._

case class TypeCheckException(pos: Position, message: String) extends Exception(s"${pos.line}, ${pos.column}: ${message}")

object Interpreter {
  def fromSource(source: String, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Interpreter = {
    val grammar = Parser.parse(source)
    fromGrammar(grammar, strategy)
  }

  def fromGrammar(grammar: Grammar, strategy: EvaluationStrategy = EvaluationStrategy.CallByName): Interpreter = {
    val checker = new TypeChecker(grammar)
    checker.check() match {
      case Left(TypeError(pos, msg)) => throw TypeCheckException(pos, msg)
      case Right(_) => new Interpreter(grammar, strategy)
    }
  }
}

class Interpreter private (grammar: Grammar, strategy: EvaluationStrategy) {
  private val evaluator = Evaluator(grammar, strategy)

  def evaluate(input: String, start: Symbol = Symbol("S")): EvaluationResult = {
    evaluator.evaluate(input, start)
  }
}
