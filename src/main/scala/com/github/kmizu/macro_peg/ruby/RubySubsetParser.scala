package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.combinator.MacroParsers._
import com.github.kmizu.macro_peg.ruby.RubyAst._

object RubySubsetParser {
  private type P[+A] = MacroParser[A]

  private lazy val horizontalSpaceChar: P[Unit] =
    range(' ' to ' ', '\t' to '\t', '\r' to '\r').map(_ => ())

  private lazy val newlineChar: P[Unit] =
    "\n".s.void

  private lazy val comment: P[Unit] =
    ("#".s ~ (!"\n".s ~ any).*).map(_ => ())

  private lazy val inlineSpacing: P[Unit] =
    (horizontalSpaceChar / comment).*.void

  private lazy val spacing: P[Unit] =
    (horizontalSpaceChar / newlineChar / comment).*.void

  private lazy val spacing1: P[Unit] =
    (horizontalSpaceChar / comment).+.void

  private def token[A](parser: P[A]): P[A] =
    (parser ~ inlineSpacing).map(_._1)

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
      "begin".s /
      "def".s /
      "class".s /
      "module".s /
      "if".s /
      "elsif".s /
      "else".s /
      "do".s /
      "rescue".s /
      "ensure".s /
      "unless".s /
      "end".s /
      "return".s /
      "self".s /
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

  private lazy val instanceVarName: P[String] =
    token(("@".s ~ identifierRaw).map { case _ ~ name => s"@$name" })

  private lazy val classVarName: P[String] =
    token(("@@".s ~ identifierRaw).map { case _ ~ name => s"@@$name" })

  private lazy val globalVarName: P[String] =
    token(
      ("$".s ~ identifierRaw).map { case _ ~ name => s"$$$name" } /
        ("$".s ~ range('0' to '9').+).map { case _ ~ digits => s"$$${digits.mkString}" } /
        "$!".s /
        "$@".s /
        "$&".s /
        "$`".s /
        "$'".s /
        "$+".s /
        "$~".s /
        "$/".s /
        "$\\".s /
        "$,".s /
        "$.".s /
        "$;".s /
        "$<".s /
        "$>".s /
        "$_".s /
        "$:".s /
        "$?".s /
        "$=".s /
        "$*".s /
        "$$".s
    )

  private lazy val constPathSegments: P[List[String]] =
    (constName ~ (sym("::") ~ constName).*).map {
      case head ~ tail => head :: tail.map(_._2)
    }

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

  private lazy val escapedSingleQuotedChar: P[String] =
    ("\\".s ~ any).map {
      case _ ~ "'" => "'"
      case _ ~ "\\" => "\\"
      case _ ~ c => s"\\$c"
    }

  private lazy val plainSingleQuotedChar: P[String] =
    (!"'".s ~ any).map(_._2)

  private lazy val singleQuotedStringLiteral: P[Expr] =
    token(("'".s ~ (escapedSingleQuotedChar / plainSingleQuotedChar).* ~ "'".s).map {
      case _ ~ chars ~ _ => StringLiteral(chars.mkString)
    })

  private def percentStringLiteral(open: String, close: String): P[Expr] =
    token((("%q".s / "%".s) ~ open.s ~ (!close.s ~ any).* ~ close.s).map {
      case _ ~ _ ~ chars ~ _ => StringLiteral(chars.map(_._2).mkString)
    })

  private lazy val percentQuotedStringLiteral: P[Expr] =
    percentStringLiteral("{", "}") /
      percentStringLiteral("(", ")") /
      percentStringLiteral("[", "]") /
      percentStringLiteral("<", ">")

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

  private lazy val selfExpr: P[Expr] =
    kw("self").map(_ => SelfExpr())

  private lazy val localVar: P[Expr] =
    identifier.map(LocalVar(_))

  private lazy val instanceVar: P[Expr] =
    instanceVarName.map(InstanceVar(_))

  private lazy val classVar: P[Expr] =
    classVarName.map(ClassVar(_))

  private lazy val globalVar: P[Expr] =
    globalVarName.map(GlobalVar(_))

  private lazy val variable: P[Expr] =
    localVar / instanceVar / classVar / globalVar

  private lazy val constRef: P[Expr] =
    constPathSegments.map(path => ConstRef(path))

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

  private lazy val receiverForCommand: P[Expr] =
    constRef / selfExpr / variable

  private lazy val receiverCommandHead: P[Expr ~ String] =
    (receiverForCommand <~ sym(".")) ~ identifierNoSpace

  private lazy val commandCall: P[Expr] =
    ((identifierNoSpace <~ spacing1) ~ commandArgs).map {
      case name ~ args => Call(None, name, args)
    }

  private lazy val receiverCommandCall: P[Expr] =
    ((receiverCommandHead <~ spacing1) ~ commandArgs).map {
      case receiverAndMethod ~ args =>
        val receiver ~ methodName = receiverAndMethod
        Call(Some(receiver), methodName, args)
    }

  private lazy val receiverCommandNoArgs: P[Expr] =
    receiverCommandHead.map {
      case receiver ~ methodName => Call(Some(receiver), methodName, Nil)
    }

  private lazy val primaryNoCall: P[Expr] =
    integerLiteral /
      stringLiteral /
      singleQuotedStringLiteral /
      percentQuotedStringLiteral /
      symbolLiteral /
      boolLiteral /
      nilLiteral /
      selfExpr /
      constRef /
      arrayLiteral /
      hashLiteral /
      variable /
      parenExpr

  private lazy val blockParams: P[List[String]] =
    sym("|") ~> sepBy0(identifier, sym(",")) <~ sym("|")

  private lazy val doBlock: P[Block] =
    (kw("do") ~ blockParams.? ~ statementSep.* ~ blockStatementsUntil(kw("end")) ~ statementSep.* ~ kw("end")).map {
      case _ ~ maybeParams ~ _ ~ body ~ _ ~ _ => Block(maybeParams.getOrElse(Nil), body)
    }

  private lazy val braceBlock: P[Block] =
    (sym("{") ~ blockParams.? ~ statementSep.* ~ blockStatementsUntil(sym("}")) ~ statementSep.* ~ sym("}")).map {
      case _ ~ maybeParams ~ _ ~ body ~ _ ~ _ => Block(maybeParams.getOrElse(Nil), body)
    }

  private lazy val blockLiteral: P[Block] =
    doBlock / braceBlock

  private lazy val lineBreak: P[Unit] =
    ("\n".s ~ inlineSpacing).void

  private lazy val statementSep: P[Unit] =
    sym(";").void / lineBreak.+.void

  private lazy val blockCallExpr: P[Expr] =
    chainedCallExpr /
      receiverCommandNoArgs /
      receiverCommandCall /
      functionCall /
      commandCall

  private lazy val blockCallStmt: P[Statement] =
    ((blockCallExpr <~ spacing) ~ blockLiteral).map {
      case call ~ block => ExprStmt(CallWithBlock(call, block))
    }

  private lazy val methodSuffix: P[Expr => Expr] =
    (sym(".") ~ identifier ~ callArgs.?).map {
      case _ ~ name ~ argsOpt =>
        val args = argsOpt.getOrElse(Nil)
        (receiver: Expr) => Call(Some(receiver), name, args)
    }

  private lazy val indexSuffix: P[Expr => Expr] =
    (sym("[") ~ sepBy0(refer(expr), sym(",")) ~ sym("]")).map {
      case _ ~ args ~ _ =>
        (receiver: Expr) => Call(Some(receiver), "[]", args)
    }

  private lazy val callSuffix: P[Expr => Expr] =
    methodSuffix / indexSuffix

  private lazy val postfixExpr: P[Expr] =
    ((functionCall / primaryNoCall) ~ callSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }

  private lazy val chainedCallExpr: P[Expr] =
    ((functionCall / primaryNoCall) ~ callSuffix.+).map {
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

  private lazy val assignableName: P[String] =
    identifier / instanceVarName / classVarName / globalVarName

  private lazy val assignStmt: P[Statement] =
    (assignableName ~ sym("=") ~ refer(expr)).map {
      case name ~ _ ~ value => Assign(name, value)
    }

  private lazy val returnStmt: P[Statement] =
    (kw("return") ~ refer(expr).?).map {
      case _ ~ value => Return(value)
    }

  private lazy val params: P[List[String]] =
    sym("(") ~> sepBy0(identifier, sym(",")) <~ sym(")")

  private lazy val simpleStatement: P[Statement] =
    ((returnStmt / assignStmt / blockCallStmt / receiverCommandCall.map(ExprStmt(_)) / commandCall.map(ExprStmt(_)) / refer(expr).map(ExprStmt(_))) ~ modifierSuffix.?).map {
      case stmt ~ Some(modifier) => modifier(stmt)
      case stmt ~ None => stmt
    }

  private lazy val statement: P[Statement] =
    refer(beginStmt) /
    refer(defStmt) /
      refer(singletonClassStmt) /
      refer(classStmt) /
      refer(moduleStmt) /
      refer(ifStmt) /
      refer(unlessStmt) /
      simpleStatement

  private lazy val topLevelStatements: P[List[Statement]] =
    sepBy0(refer(statement), statementSep)

  private def blockStatementsUntil(stop: P[Any]): P[List[Statement]] =
    (stop.and ~> success(Nil)) / sepBy1(refer(statement), statementSep)

  private lazy val blockStatements: P[List[Statement]] =
    blockStatementsUntil(kw("end"))

  private lazy val rescueClause: P[RescueClause] =
    (
      kw("rescue") ~
      sepBy1(refer(expr), sym(",")).? ~
      (sym("=>") ~ identifier).?.map(_.map(_._2)) ~
      statementSep.* ~
      blockStatementsUntil(kw("rescue") / kw("else") / kw("ensure") / kw("end"))
    ).map {
      case _ ~ exceptionsOpt ~ variableOpt ~ _ ~ body =>
        RescueClause(exceptionsOpt.getOrElse(Nil), variableOpt, body)
    }

  private lazy val beginStmt: P[Statement] =
    (
      kw("begin") ~ statementSep.* ~
      blockStatementsUntil(kw("rescue") / kw("else") / kw("ensure") / kw("end")) ~
      statementSep.* ~
      rescueClause.* ~
      statementSep.* ~
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("ensure") / kw("end"))).? ~
      statementSep.* ~
      (kw("ensure") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~ kw("end")
    ).map {
      case _ ~ _ ~ body ~ _ ~ rescues ~ _ ~ elseOpt ~ _ ~ ensureOpt ~ _ ~ _ =>
        val elseBody = elseOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val ensureBody = ensureOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        BeginRescue(body, rescues, elseBody, ensureBody)
    }

  private lazy val defStmt: P[Statement] =
    (kw("def") ~ identifier ~ params.? ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ name ~ maybeParams ~ _ ~ body ~ _ ~ _ =>
        Def(name, maybeParams.getOrElse(Nil), body)
    }

  private lazy val singletonClassStmt: P[Statement] =
    (kw("class") ~ sym("<<") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ _ ~ receiver ~ _ ~ body ~ _ ~ _ =>
        SingletonClassDef(receiver, body)
    }

  private lazy val classStmt: P[Statement] =
    (kw("class") ~ constPathSegments ~ (sym("<") ~ refer(expr)).?.map(_.map(_._2)) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ name ~ superClass ~ _ ~ body ~ _ ~ _ =>
        ClassDef(name.mkString("::"), body, UnknownSpan, superClass)
    }

  private lazy val moduleStmt: P[Statement] =
    (kw("module") ~ constPathSegments ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ name ~ _ ~ body ~ _ ~ _ =>
        ModuleDef(name.mkString("::"), body)
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
      kw("elsif") ~ refer(expr) ~ statementSep.* ~ blockStatementsUntil(kw("elsif") / kw("else") / kw("end")) ~ statementSep.* ~ refer(ifTail)
    ).map {
      case _ ~ condition ~ _ ~ body ~ _ ~ tail =>
        List(IfExpr(condition, body, tail))
    } /
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).map {
        case _ ~ _ ~ elseBody => elseBody
      } /
      (kw("end").and ~> success(Nil))

  private lazy val ifStmt: P[Statement] =
    (
      kw("if") ~ refer(expr) ~ statementSep.* ~ blockStatementsUntil(kw("elsif") / kw("else") / kw("end")) ~
      statementSep.* ~ refer(ifTail) ~
      statementSep.* ~ kw("end")
    ).map {
      case _ ~ condition ~ _ ~ thenBody ~ _ ~ elseBody ~ _ ~ _ =>
        IfExpr(condition, thenBody, elseBody)
    }

  private lazy val unlessStmt: P[Statement] =
    (
      kw("unless") ~ refer(expr) ~ statementSep.* ~ blockStatementsUntil(kw("else") / kw("end")) ~
      statementSep.* ~
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~ kw("end")
    ).map {
      case _ ~ condition ~ _ ~ thenBody ~ _ ~ elseBodyOpt ~ _ ~ _ =>
        val elseBody = elseBodyOpt.map { case _ ~ _ ~ body => body }.getOrElse(Nil)
        UnlessExpr(condition, thenBody, elseBody)
    }

  private lazy val program: P[Program] =
    (spacing ~> (topLevelStatements <~ statementSep.*) <~ spacing).map(stmts => Program(stmts))

  def parse(input: String): Either[String, Program] = {
    parseAll(program, input).left.map(f => formatFailure(input, f))
  }
}
