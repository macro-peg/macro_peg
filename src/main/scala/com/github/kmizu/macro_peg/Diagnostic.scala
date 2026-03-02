package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.Ast.Position

sealed trait DiagnosticPhase {
  def label: String
}

object DiagnosticPhase {
  case object Parse extends DiagnosticPhase { val label = "parse" }
  case object WellFormedness extends DiagnosticPhase { val label = "well-formedness" }
  case object TypeCheck extends DiagnosticPhase { val label = "type-check" }
  case object Evaluation extends DiagnosticPhase { val label = "evaluation" }
  case object Generation extends DiagnosticPhase { val label = "generation" }
}

case class Diagnostic(
  phase: DiagnosticPhase,
  message: String,
  position: Option[Position] = None,
  inputOffset: Option[Int] = None,
  expected: List[String] = Nil,
  ruleStack: List[Symbol] = Nil,
  snippet: Option[String] = None,
  hint: Option[String] = None
) {
  def format: String = {
    val p = position.map(pos => s"${pos.line + 1}:${pos.column + 1}").getOrElse("?")
    val expectedText = if(expected.isEmpty) "" else s" expected=${expected.mkString("[", ", ", "]")}"
    val stackText = if(ruleStack.isEmpty) "" else s" stack=${ruleStack.map(_.name).mkString(" -> ")}"
    val snippetText = snippet.map(s => s"\n$s").getOrElse("")
    val hintText = hint.map(h => s"\nhint: $h").getOrElse("")
    s"[${phase.label}] $p $message$expectedText$stackText$snippetText$hintText"
  }
}

object Diagnostic {
  def inputSnippet(input: String, offset: Int, radius: Int = 20): String = {
    val safeOffset = math.max(0, math.min(input.length, offset))
    val start = math.max(0, safeOffset - radius)
    val end = math.min(input.length, safeOffset + radius)
    val fragment = input.substring(start, end)
    val pointer = " " * (safeOffset - start) + "^"
    fragment + "\n" + pointer
  }
}

case class DiagnosticException(diagnostic: Diagnostic) extends Exception(diagnostic.format)
case class GrammarValidationException(pos: Position, message: String) extends Exception(s"${pos.line}, ${pos.column}: ${message}")
