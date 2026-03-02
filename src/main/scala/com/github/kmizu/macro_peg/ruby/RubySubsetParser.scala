package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.combinator.MacroParsers._
import com.github.kmizu.macro_peg.ruby.RubyAst._

object RubySubsetParser {
  private type P[+A] = MacroParser[A]

  private lazy val spaceChar: P[Unit] =
    range(' ' to ' ', '\t' to '\t', '\r' to '\r', '\n' to '\n').map(_ => ())

  private lazy val comment: P[Unit] =
    ("#".s ~ (!"\n".s ~ any).* ~ ("\n".s).?).map(_ => ())

  private lazy val spacing: P[Unit] =
    (spaceChar / comment).*.map(_ => ())

  private lazy val spacing1: P[Unit] =
    (spaceChar / comment).+.void

  private def token[A](parser: P[A]): P[A] =
    (parser ~ spacing).map(_._1)

  private def kw(name: String): P[String] =
    token(string(name)).label(s"`$name`")

  private def sym(name: String): P[String] =
    token(string(name))

  private lazy val identStart: P[String] =
    range('a' to 'z', 'A' to 'Z', '_' to '_')

  private lazy val identCont: P[String] =
    range('a' to 'z', 'A' to 'Z', '0' to '9', '_' to '_')

  private lazy val identifierRaw: P[String] =
    (identStart ~ identCont.*).map { case h ~ t => h + t.mkString }

  private lazy val reservedWord: P[String] =
    (
      "def".s /
      "class".s /
      "module".s /
      "if".s /
      "elsif".s /
      "else".s /
      "unless".s /
      "end".s /
      "true".s /
      "false".s /
      "nil".s
    ) <~ !identCont

  private lazy val identifierNoSpace: P[String] =
    (!reservedWord ~ identifierRaw).map(_._2)

  private lazy val identifier: P[String] =
    token(identifierNoSpace)

  private lazy val constStart: P[String] =
    range('A' to 'Z')

  private lazy val constName: P[String] =
    token((constStart ~ identCont.*).map { case h ~ t => h + t.mkString })

  private lazy val integerLiteral: P[Expr] =
    token(range('0' to '9').+).map(ds => IntLiteral(ds.mkString.toLong))

  private lazy val escapedChar: P[String] =
    ("\\".s ~ any).map {
      case _ ~ "n" => "\n"
      case _ ~ "t" => "\t"
      case _ ~ "r" => "\r"
      case _ ~ "\"" => "\""
      case _ ~ "\\" => "\\"
      case _ ~ c => c
    }

  private lazy val plainStringChar: P[String] =
    (!"\"".s ~ any).map(_._2)

  private lazy val stringLiteral: P[Expr] =
    token(("\"".s ~ (escapedChar / plainStringChar).* ~ "\"".s).map {
      case _ ~ chars ~ _ => StringLiteral(chars.mkString)
    })

  private lazy val symbolLiteral: P[Expr] =
    (
      (sym(":") ~ token(identifierRaw)).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
    ) / (
      (sym(":") ~ token(("\"".s ~ (escapedChar / plainStringChar).* ~ "\"".s).map {
        case _ ~ chars ~ _ => chars.mkString
      })).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
    )

  private lazy val boolLiteral: P[Expr] =
    kw("true").map(_ => BoolLiteral(true)) /
      kw("false").map(_ => BoolLiteral(false))

  private lazy val nilLiteral: P[Expr] =
    kw("nil").map(_ => NilLiteral())

  private lazy val variable: P[Expr] =
    identifier.map(LocalVar(_))

  private lazy val parenExpr: P[Expr] =
    (sym("(") ~ refer(expr) ~ sym(")")).map { case _ ~ e ~ _ => e }

  private lazy val arrayLiteral: P[Expr] =
    (sym("[") ~ sepBy0(refer(expr), sym(",")) ~ sym("]")).map {
      case _ ~ elements ~ _ => ArrayLiteral(elements)
    }

  private lazy val hashEntry: P[(Expr, Expr)] =
    (refer(expr) ~ sym("=>") ~ refer(expr)).map { case key ~ _ ~ value => key -> value }

  private lazy val hashLiteral: P[Expr] =
    (sym("{") ~ sepBy0(hashEntry, sym(",")) ~ sym("}")).map {
      case _ ~ entries ~ _ => HashLiteral(entries)
    }

  private lazy val callArgs: P[List[Expr]] =
    sym("(") ~> sepBy0(refer(expr), sym(",")) <~ sym(")")

  private lazy val commandArgs: P[List[Expr]] =
    sepBy1(refer(expr), sym(","))

  private lazy val functionCall: P[Expr] =
    (identifier ~ callArgs).map { case name ~ args => Call(None, name, args) }

  private lazy val commandCall: P[Expr] =
    ((identifierNoSpace <~ spacing1) ~ commandArgs).map {
      case name ~ args => Call(None, name, args)
    }

  private lazy val primaryNoCall: P[Expr] =
    integerLiteral /
      stringLiteral /
      symbolLiteral /
      boolLiteral /
      nilLiteral /
      arrayLiteral /
      hashLiteral /
      variable /
      parenExpr

  private lazy val methodSuffix: P[Expr => Expr] =
    (sym(".") ~ identifier ~ callArgs).map {
      case _ ~ name ~ args =>
        (receiver: Expr) => Call(Some(receiver), name, args)
    }

  private lazy val postfixExpr: P[Expr] =
    ((functionCall / primaryNoCall) ~ methodSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }

  private def infix(op: String): P[(Expr, Expr) => Expr] =
    sym(op).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private lazy val mulDivExpr: P[Expr] =
    chainl(postfixExpr)(infix("*") / infix("/"))

  private lazy val addSubExpr: P[Expr] =
    chainl(mulDivExpr)(infix("+") / infix("-"))

  private lazy val expr: P[Expr] =
    addSubExpr

  private lazy val assignStmt: P[Statement] =
    (identifier ~ sym("=") ~ refer(expr)).map {
      case name ~ _ ~ value => Assign(name, value)
    }

  private lazy val params: P[List[String]] =
    sym("(") ~> sepBy0(identifier, sym(",")) <~ sym(")")

  private lazy val simpleStatement: P[Statement] =
    ((assignStmt / commandCall.map(ExprStmt(_)) / refer(expr).map(ExprStmt(_))) ~ modifierSuffix.?).map {
      case stmt ~ Some(modifier) => modifier(stmt)
      case stmt ~ None => stmt
    }

  private lazy val statement: P[Statement] =
    refer(defStmt) /
      refer(classStmt) /
      refer(moduleStmt) /
      refer(ifStmt) /
      refer(unlessStmt) /
      simpleStatement

  private lazy val topLevelStatements: P[List[Statement]] =
    sepBy0(statement, sym(";"))

  private def blockStatementsUntil(stop: P[Any]): P[List[Statement]] =
    (stop.and ~> success(Nil)) / sepBy1(statement, sym(";"))

  private lazy val blockStatements: P[List[Statement]] =
    blockStatementsUntil(kw("end"))

  private lazy val defStmt: P[Statement] =
    (kw("def") ~ identifier ~ params.? ~ sym(";").* ~ blockStatements ~ sym(";").* ~ kw("end")).map {
      case _ ~ name ~ maybeParams ~ _ ~ body ~ _ ~ _ =>
        Def(name, maybeParams.getOrElse(Nil), body)
    }

  private lazy val classStmt: P[Statement] =
    (kw("class") ~ constName ~ sym(";").* ~ blockStatements ~ sym(";").* ~ kw("end")).map {
      case _ ~ name ~ _ ~ body ~ _ ~ _ =>
        ClassDef(name, body)
    }

  private lazy val moduleStmt: P[Statement] =
    (kw("module") ~ constName ~ sym(";").* ~ blockStatements ~ sym(";").* ~ kw("end")).map {
      case _ ~ name ~ _ ~ body ~ _ ~ _ =>
        ModuleDef(name, body)
    }

  private lazy val modifierSuffix: P[Statement => Statement] =
    (kw("if") ~ refer(expr)).map {
      case _ ~ condition =>
        (stmt: Statement) => IfExpr(condition, List(stmt), Nil)
    } /
      (kw("unless") ~ refer(expr)).map {
        case _ ~ condition =>
          (stmt: Statement) => UnlessExpr(condition, List(stmt), Nil)
      }

  private lazy val ifTail: P[List[Statement]] =
    (
      kw("elsif") ~ refer(expr) ~ sym(";").* ~ blockStatementsUntil(kw("elsif") / kw("else") / kw("end")) ~ sym(";").* ~ refer(ifTail)
    ).map {
      case _ ~ condition ~ _ ~ body ~ _ ~ tail =>
        List(IfExpr(condition, body, tail))
    } /
      (kw("else") ~ sym(";").* ~ blockStatementsUntil(kw("end"))).map {
        case _ ~ _ ~ elseBody => elseBody
      } /
      (kw("end").and ~> success(Nil))

  private lazy val ifStmt: P[Statement] =
    (
      kw("if") ~ refer(expr) ~ sym(";").* ~ blockStatementsUntil(kw("elsif") / kw("else") / kw("end")) ~
      sym(";").* ~ refer(ifTail) ~
      sym(";").* ~ kw("end")
    ).map {
      case _ ~ condition ~ _ ~ thenBody ~ _ ~ elseBody ~ _ ~ _ =>
        IfExpr(condition, thenBody, elseBody)
    }

  private lazy val unlessStmt: P[Statement] =
    (
      kw("unless") ~ refer(expr) ~ sym(";").* ~ blockStatementsUntil(kw("else") / kw("end")) ~
      sym(";").* ~
      (kw("else") ~ sym(";").* ~ blockStatementsUntil(kw("end"))).? ~
      sym(";").* ~ kw("end")
    ).map {
      case _ ~ condition ~ _ ~ thenBody ~ _ ~ elseBodyOpt ~ _ ~ _ =>
        val elseBody = elseBodyOpt.map { case _ ~ _ ~ body => body }.getOrElse(Nil)
        UnlessExpr(condition, thenBody, elseBody)
    }

  private lazy val program: P[Program] =
    (spacing ~> (topLevelStatements <~ sym(";").*) <~ spacing).map(stmts => Program(stmts))

  def parse(input: String): Either[String, Program] = {
    parseAll(program, input).left.map(f => formatFailure(input, f))
  }
}
