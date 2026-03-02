package com.github.kmizu.macro_peg

import scala.quoted.*

object InlineMacroParsers {
  trait CompiledParser {
    def evaluate(input: String): EvaluationResult
    def evaluateEither(input: String): Either[Diagnostic, EvaluationResult.Success]
    final def accepts(input: String): Boolean = evaluate(input).isSuccess
  }

  inline def mpeg(
    inline grammar: String,
    inline start: String = "S",
    strategy: EvaluationStrategy = EvaluationStrategy.CallByName
  ): CompiledParser =
    ${ mpegImpl('grammar, 'start, 'strategy) }

  private def mpegImpl(
    grammarExpr: Expr[String],
    startExpr: Expr[String],
    strategyExpr: Expr[EvaluationStrategy]
  )(using Quotes): Expr[CompiledParser] = {
    val grammar = grammarExpr.valueOrAbort
    val start = startExpr.valueOrAbort
    validateGrammar(grammar, start)
    val grammarLiteral = Expr(grammar)
    val startLiteral = Expr(start)

    '{
      val interpreter = Interpreter.fromSource($grammarLiteral, $strategyExpr)
      val startSymbol = Symbol($startLiteral)
      new CompiledParser {
        override def evaluate(input: String): EvaluationResult =
          interpreter.evaluate(input, startSymbol)

        override def evaluateEither(input: String): Either[Diagnostic, EvaluationResult.Success] =
          interpreter.evaluateEither(input, startSymbol)
      }
    }
  }

  private def validateGrammar(grammar: String, start: String)(using Quotes): Unit = {
    import quotes.reflect.report

    val parsed = try {
      Parser.parse(grammar)
    } catch {
      case Parser.ParseException(pos, msg) =>
        report.errorAndAbort(s"[macro-peg parse] ${pos.line + 1}:${pos.column + 1} $msg")
    }

    GrammarValidator.validate(parsed) match {
      case Left(err) =>
        val hintText = err.hint.map(h => s" hint: $h").getOrElse("")
        report.errorAndAbort(s"[macro-peg well-formedness] ${err.pos.line + 1}:${err.pos.column + 1} ${err.message}$hintText")
      case Right(_) =>
        ()
    }

    val checker = new TypeChecker(parsed)
    checker.check() match {
      case Left(TypeError(pos, message)) =>
        report.errorAndAbort(s"[macro-peg type-check] ${pos.line + 1}:${pos.column + 1} $message")
      case Right(_) =>
        ()
    }

    if(!parsed.rules.exists(_.name.name == start)) {
      report.errorAndAbort(s"[macro-peg start rule] `$start` is not defined in grammar")
    }
  }
}
