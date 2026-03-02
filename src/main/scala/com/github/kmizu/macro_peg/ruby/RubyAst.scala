package com.github.kmizu.macro_peg.ruby

object RubyAst {
  case class Span(start: Int, end: Int)
  val UnknownSpan: Span = Span(-1, -1)

  sealed trait Node {
    def span: Span
  }

  sealed trait Statement extends Node
  sealed trait Expr extends Statement

  case class Program(statements: List[Statement], span: Span = UnknownSpan) extends Node

  case class ExprStmt(expr: Expr, span: Span = UnknownSpan) extends Statement
  case class Assign(name: String, value: Expr, span: Span = UnknownSpan) extends Statement
  case class Def(name: String, params: List[String], body: List[Statement], span: Span = UnknownSpan) extends Statement
  case class ClassDef(name: String, body: List[Statement], span: Span = UnknownSpan) extends Statement

  case class IntLiteral(value: Long, span: Span = UnknownSpan) extends Expr
  case class StringLiteral(value: String, span: Span = UnknownSpan) extends Expr
  case class BoolLiteral(value: Boolean, span: Span = UnknownSpan) extends Expr
  case class NilLiteral(span: Span = UnknownSpan) extends Expr
  case class LocalVar(name: String, span: Span = UnknownSpan) extends Expr
  case class ArrayLiteral(elements: List[Expr], span: Span = UnknownSpan) extends Expr
  case class HashLiteral(entries: List[(Expr, Expr)], span: Span = UnknownSpan) extends Expr
  case class Call(receiver: Option[Expr], methodName: String, args: List[Expr], span: Span = UnknownSpan) extends Expr
  case class BinaryOp(lhs: Expr, op: String, rhs: Expr, span: Span = UnknownSpan) extends Expr
}
