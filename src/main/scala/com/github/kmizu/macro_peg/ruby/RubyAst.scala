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
  case class Return(value: Option[Expr], span: Span = UnknownSpan) extends Statement
  case class RescueClause(exceptionClasses: List[Expr], variable: Option[String], body: List[Statement], span: Span = UnknownSpan) extends Node
  case class BeginRescue(body: List[Statement], rescues: List[RescueClause], elseBody: List[Statement], ensureBody: List[Statement], span: Span = UnknownSpan) extends Statement
  case class WhenClause(patterns: List[Expr], body: List[Statement], span: Span = UnknownSpan) extends Node
  case class Retry(span: Span = UnknownSpan) extends Statement
  case class ForIn(name: String, iterable: Expr, body: List[Statement], span: Span = UnknownSpan) extends Statement
  case class Def(name: String, params: List[String], body: List[Statement], span: Span = UnknownSpan) extends Statement
  case class ClassDef(name: String, body: List[Statement], span: Span = UnknownSpan, superClass: Option[Expr] = None) extends Statement
  case class SingletonClassDef(receiver: Expr, body: List[Statement], span: Span = UnknownSpan) extends Statement
  case class ModuleDef(name: String, body: List[Statement], span: Span = UnknownSpan) extends Statement

  case class IntLiteral(value: Long, span: Span = UnknownSpan) extends Expr
  case class StringLiteral(value: String, span: Span = UnknownSpan) extends Expr
  case class SymbolLiteral(value: String, span: Span) extends Expr
  case class BoolLiteral(value: Boolean, span: Span = UnknownSpan) extends Expr
  case class NilLiteral(span: Span = UnknownSpan) extends Expr
  case class SelfExpr(span: Span = UnknownSpan) extends Expr
  case class LocalVar(name: String, span: Span = UnknownSpan) extends Expr
  case class InstanceVar(name: String, span: Span = UnknownSpan) extends Expr
  case class ClassVar(name: String, span: Span = UnknownSpan) extends Expr
  case class GlobalVar(name: String, span: Span = UnknownSpan) extends Expr
  case class ConstRef(path: List[String], span: Span = UnknownSpan) extends Expr
  case class ArrayLiteral(elements: List[Expr], span: Span = UnknownSpan) extends Expr
  case class HashLiteral(entries: List[(Expr, Expr)], span: Span = UnknownSpan) extends Expr
  case class Call(receiver: Option[Expr], methodName: String, args: List[Expr], span: Span = UnknownSpan) extends Expr
  case class Block(params: List[String], body: List[Statement], span: Span = UnknownSpan) extends Node
  case class CallWithBlock(call: Expr, block: Block, span: Span = UnknownSpan) extends Expr
  case class RangeExpr(start: Expr, end: Expr, exclusive: Boolean, span: Span = UnknownSpan) extends Expr
  case class UnaryOp(op: String, expr: Expr, span: Span = UnknownSpan) extends Expr
  case class BinaryOp(lhs: Expr, op: String, rhs: Expr, span: Span = UnknownSpan) extends Expr
  case class AssignExpr(name: String, value: Expr, span: Span = UnknownSpan) extends Expr
  case class IfExpr(condition: Expr, thenBody: List[Statement], elseBody: List[Statement], span: Span = UnknownSpan) extends Expr
  case class UnlessExpr(condition: Expr, thenBody: List[Statement], elseBody: List[Statement], span: Span = UnknownSpan) extends Expr
  case class CaseExpr(scrutinee: Option[Expr], whens: List[WhenClause], elseBody: List[Statement], span: Span = UnknownSpan) extends Expr
  case class WhileExpr(condition: Expr, body: List[Statement], span: Span = UnknownSpan) extends Expr
  case class UntilExpr(condition: Expr, body: List[Statement], span: Span = UnknownSpan) extends Expr
}
