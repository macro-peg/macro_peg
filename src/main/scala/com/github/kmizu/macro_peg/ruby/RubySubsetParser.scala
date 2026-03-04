package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.combinator.MacroParsers._
import com.github.kmizu.macro_peg.ruby.RubyAst._
import scala.util.Try

object RubySubsetParser {
  private type P[+A] = MacroParser[A]
  private case class PendingHeredoc(
    token: String,
    terminator: String,
    allowIndentedTerminator: Boolean,
    lines: scala.collection.mutable.ArrayBuffer[String]
  )

  private lazy val horizontalSpaceChar: P[Unit] =
    range(' ' to ' ', '\t' to '\t', '\r' to '\r').map(_ => ())

  private lazy val newlineChar: P[Unit] =
    "\n".s.void

  private lazy val comment: P[Unit] =
    ("#".s ~ (!"\n".s ~ any).*).map(_ => ())

  private lazy val blockComment: P[Unit] =
    ("=begin".s ~ (!"=end".s ~ any).* ~ "=end".s).map(_ => ())

  private lazy val inlineSpacing: P[Unit] =
    (horizontalSpaceChar / comment / blockComment).*.void

  private lazy val horizontalSpacing: P[Unit] =
    horizontalSpaceChar.*.void

  private lazy val spacing: P[Unit] =
    (horizontalSpaceChar / newlineChar / comment / blockComment).*.void

  private lazy val spacing1: P[Unit] =
    (horizontalSpaceChar / comment / blockComment).+.void

  private def token[A](parser: P[A]): P[A] =
    (parser ~ inlineSpacing).map(_._1)

  private def kw(name: String): P[String] =
    token(string(name) <~ !identCont).label(s"`$name`")

  private def sym(name: String): P[String] =
    token(string(name))

  private lazy val labelColon: P[String] =
    token(":".s <~ !":".s)

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
      "while".s /
      "until".s /
      "for".s /
      "in".s /
      "case".s /
      "when".s /
      "if".s /
      "elsif".s /
      "else".s /
      "and".s /
      "not".s /
      "or".s /
      "do".s /
      "rescue".s /
      "ensure".s /
      "retry".s /
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

  private lazy val methodSuffixChar: P[String] =
    "?".s / "!".s / "=".s

  private lazy val methodIdentifierWithSuffixRaw: P[String] =
    (identifierRaw ~ methodSuffixChar).map {
      case base ~ suffix => base + suffix
    }

  private lazy val methodIdentifierRaw: P[String] =
    methodIdentifierWithSuffixRaw /
      (!reservedWord ~ identifierRaw).map(_._2)

  private lazy val methodIdentifierNoSpace: P[String] =
    methodIdentifierRaw

  private lazy val methodIdentifier: P[String] =
    token(methodIdentifierNoSpace)

  private lazy val keywordMethodNameNoSpace: P[String] =
    "class".s

  private lazy val receiverMethodNameNoSpace: P[String] =
    methodIdentifierNoSpace / keywordMethodNameNoSpace

  private lazy val punctuatedMethodIdentifierNoSpace: P[String] =
    methodIdentifierWithSuffixRaw

  private lazy val punctuatedMethodIdentifier: P[String] =
    token(punctuatedMethodIdentifierNoSpace)

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
        "$?".s /
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
    (sym("::").? ~ constName ~ (sym("::") ~ constName).*).map {
      case _ ~ head ~ tail => head :: tail.map(_._2)
    }

  private def digitsWithUnderscore(digit: P[String]): P[String] =
    (digit.+ ~ ("_".s ~ digit.+).*).map {
      case head ~ tail =>
        head.mkString + tail.map { case _ ~ ds => "_" + ds.mkString }.mkString
    }

  private lazy val decimalDigitsRaw: P[String] =
    digitsWithUnderscore(range('0' to '9'))

  private lazy val binaryDigitsRaw: P[String] =
    digitsWithUnderscore(range('0' to '1'))

  private lazy val octalDigitsRaw: P[String] =
    digitsWithUnderscore(range('0' to '7'))

  private lazy val hexDigitsRaw: P[String] =
    digitsWithUnderscore(range('0' to '9', 'a' to 'f', 'A' to 'F'))

  private lazy val integerLiteralRaw: P[(String, Int)] =
    ("0".s ~ ("b".s / "B".s) ~ binaryDigitsRaw).map { case _ ~ _ ~ ds => ds -> 2 } /
      ("0".s ~ ("o".s / "O".s) ~ octalDigitsRaw).map { case _ ~ _ ~ ds => ds -> 8 } /
      ("0".s ~ ("d".s / "D".s) ~ decimalDigitsRaw).map { case _ ~ _ ~ ds => ds -> 10 } /
      ("0".s ~ ("x".s / "X".s) ~ hexDigitsRaw).map { case _ ~ _ ~ ds => ds -> 16 } /
      decimalDigitsRaw.map(_ -> 10)

  private lazy val integerLiteral: P[Expr] =
    token((integerLiteralRaw <~ !(".".s ~ range('0' to '9')) <~ !identCont)).map {
      case (raw, base) =>
        IntLiteral(BigInt(raw.replace("_", ""), base))
    }

  private lazy val floatExponentRaw: P[String] =
    (range('e' to 'e', 'E' to 'E') ~ ("+".s / "-".s).? ~ range('0' to '9').+).map {
      case e ~ signOpt ~ digits =>
        e + signOpt.getOrElse("") + digits.mkString
    }

  private lazy val floatLiteralRaw: P[String] =
    (decimalDigitsRaw ~ ".".s ~ decimalDigitsRaw ~ floatExponentRaw.?).map {
      case intPart ~ _ ~ fracPart ~ exp =>
        intPart + "." + fracPart + exp.getOrElse("")
    } /
      (decimalDigitsRaw ~ floatExponentRaw).map {
        case intPart ~ exp => intPart + exp
      }

  private lazy val floatLiteral: P[Expr] =
    token((floatLiteralRaw <~ !identCont)).map { raw =>
      val normalized = raw.replace("_", "")
      FloatLiteral(Try(normalized.toDouble).getOrElse(Double.NaN))
    }

  private lazy val escapedChar: P[String] =
    ("\\".s ~ any).map {
      case _ ~ "n" => "\n"
      case _ ~ "t" => "\t"
      case _ ~ "r" => "\r"
      case _ ~ "\"" => "\""
      case _ ~ "\\" => "\\"
      case _ ~ c => c
    }

  private lazy val escapedAnyChar: P[String] =
    ("\\".s ~ any).map { case _ ~ c => s"\\$c" }

  private def quotedInInterpolation(delim: String): P[String] =
    (delim.s ~ (escapedAnyChar / (!delim.s ~ any).map(_._2)).* ~ delim.s).map {
      case open ~ chars ~ close => open + chars.mkString + close
    }

  private lazy val interpolationSegment: P[String] = {
    lazy val interpolationChunk: P[String] =
      quotedInInterpolation("\"") /
        quotedInInterpolation("'") /
        ("{".s ~ refer(interpolationChunk).* ~ "}".s).map {
          case open ~ inner ~ close => open + inner.mkString + close
        } /
        (!"}".s ~ any).map(_._2)
    ("#{".s ~ interpolationChunk.* ~ "}".s).map {
      case open ~ chunks ~ close => open + chunks.mkString + close
    }
  }

  private lazy val plainStringChar: P[String] =
    (!"\"".s ~ !"\\".s ~ !"#{".s ~ any).map {
      case _ ~ _ ~ _ ~ char => char
    }

  private lazy val stringLiteral: P[Expr] =
    token(("\"".s ~ (escapedChar / interpolationSegment / plainStringChar).* ~ "\"".s).map {
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

  private lazy val plainBacktickChar: P[String] =
    (!"`".s ~ !"\\".s ~ !"#{".s ~ any).map {
      case _ ~ _ ~ _ ~ char => char
    }

  private lazy val backtickLiteral: P[Expr] =
    token(("`".s ~ (escapedChar / interpolationSegment / plainBacktickChar).* ~ "`".s).map {
      case _ ~ chars ~ _ => StringLiteral(chars.mkString)
    })

  private def percentBody(open: String, close: String): P[String] = {
    lazy val percentChunk: P[String] =
      ((open.s ~ refer(percentChunk).* ~ close.s).map {
        case openText ~ inner ~ closeText => openText + inner.mkString + closeText
      }) /
        ((!open.s ~ !close.s ~ any).map {
          case _ ~ _ ~ char => char
        })
    (open.s ~ percentChunk.* ~ close.s).map {
      case _ ~ chars ~ _ => chars.mkString
    }
  }

  private def percentBodySimple(delim: String): P[String] = {
    lazy val escaped: P[String] =
      ("\\".s ~ any).map { case _ ~ c => s"\\$c" }
    lazy val plain: P[String] =
      (!delim.s ~ any).map(_._2)
    (delim.s ~ (escaped / plain).* ~ delim.s).map {
      case _ ~ chars ~ _ => chars.mkString
    }
  }

  private def percentStringLiteral(open: String, close: String): P[Expr] =
    token((("%q".s / "%Q".s / "%".s) ~ percentBody(open, close)).map {
      case _ ~ body => StringLiteral(body)
    })

  private def percentWordArrayLiteral(open: String, close: String): P[Expr] =
    token((("%w".s / "%W".s) ~ percentBody(open, close)).map {
      case _ ~ body =>
        val words =
          body
            .split("\\s+")
            .toList
            .filter(_.nonEmpty)
            .map(word => StringLiteral(word))
        ArrayLiteral(words)
    })

  private def percentSymbolArrayLiteral(open: String, close: String): P[Expr] =
    token((("%i".s / "%I".s) ~ percentBody(open, close)).map {
      case _ ~ body =>
        val symbols =
          body
            .split("\\s+")
            .toList
            .filter(_.nonEmpty)
            .map(value => SymbolLiteral(value, UnknownSpan))
        ArrayLiteral(symbols)
    })

  private lazy val percentQuotedStringLiteral: P[Expr] =
    percentStringLiteral("{", "}") /
      percentStringLiteral("(", ")") /
      percentStringLiteral("[", "]") /
      percentStringLiteral("<", ">")

  private lazy val percentWordArray: P[Expr] =
    percentWordArrayLiteral("{", "}") /
      percentWordArrayLiteral("(", ")") /
      percentWordArrayLiteral("[", "]") /
      percentWordArrayLiteral("<", ">")

  private lazy val percentSymbolArray: P[Expr] =
    percentSymbolArrayLiteral("{", "}") /
      percentSymbolArrayLiteral("(", ")") /
      percentSymbolArrayLiteral("[", "]") /
      percentSymbolArrayLiteral("<", ">")

  private def percentRegexLiteral(open: String, close: String): P[Expr] =
    token(("%r".s ~ percentBody(open, close) ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ body ~ _ => StringLiteral(body)
    })

  private def percentRegexLiteralSimple(delim: String): P[Expr] =
    token(("%r".s ~ percentBodySimple(delim) ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ body ~ _ => StringLiteral(body)
    })

  private lazy val percentRegex: P[Expr] =
    percentRegexLiteral("{", "}") /
      percentRegexLiteral("(", ")") /
      percentRegexLiteral("[", "]") /
      percentRegexLiteral("<", ">") /
      percentRegexLiteralSimple("\"") /
      percentRegexLiteralSimple("'") /
      percentRegexLiteralSimple("/")

  private lazy val escapedRegexChar: P[String] =
    ("\\".s ~ any).map { case _ ~ c => s"\\$c" }

  private lazy val plainRegexChar: P[String] =
    (!"/".s ~ any).map(_._2)

  private lazy val regexLiteral: P[Expr] =
    percentRegex /
      token(("/".s ~ (escapedRegexChar / plainRegexChar).* ~ "/".s ~ range('a' to 'z', 'A' to 'Z').*).map {
        case _ ~ chars ~ _ ~ _ => StringLiteral(chars.mkString)
      })

  private lazy val symbolOperatorNameNoSpace: P[String] =
    "===".s /
      "<=>".s /
      "!=".s /
      "==".s /
      "<=".s /
      ">=".s /
      "<<".s /
      ">>".s /
      "[]=".s /
      "[]".s /
      "<".s /
      ">".s /
      "+".s /
      "-".s /
      "*".s /
      "/".s /
      "%".s /
      "&".s /
      "|".s /
      "^".s /
      "~".s

  private lazy val symbolLiteral: P[Expr] =
    (sym(":") ~ token("**".s / "*".s / "&".s)).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) } /
      (sym(":") ~ token(symbolOperatorNameNoSpace / methodIdentifierRaw)).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) } /
      (sym(":") ~ classVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (sym(":") ~ instanceVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (sym(":") ~ globalVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (sym(":") ~ token(("\"".s ~ (escapedChar / interpolationSegment / plainStringChar).* ~ "\"".s).map {
        case _ ~ chars ~ _ => chars.mkString
      })).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }

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

  private lazy val spacedExpr: P[Expr] =
    spacing ~> refer(expr) <~ spacing

  private lazy val arrayLiteral: P[Expr] =
    (sym("[") ~> spacing ~> sepBy0(spacedExpr, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("]")).map {
      case values ~ _ => ArrayLiteral(values)
    }

  private lazy val labelHashEntry: P[(Expr, Expr)] =
    ((identifierNoSpace <~ labelColon) ~ refer(expr)).map {
      case name ~ value => SymbolLiteral(name, UnknownSpan) -> value
    }

  private lazy val hashEntry: P[(Expr, Expr)] =
    labelHashEntry /
      (refer(expr) ~ sym("=>") ~ refer(expr)).map { case key ~ _ ~ value => key -> value }

  private lazy val hashLiteral: P[Expr] =
    (sym("{") ~> spacing ~> sepBy0(spacing ~> hashEntry <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("}")).map {
      case entries ~ _ => HashLiteral(entries)
    }

  private lazy val callArgs: P[List[Expr]] =
    (sym("(") ~> spacing ~> sepBy0(spacing ~> callArgExpr <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym(")")).map {
      case args ~ _ => args
    }

  private lazy val commandArgSep: P[Unit] =
    (inlineSpacing ~> sym(",") <~ spacing).void

  private lazy val commandArgs: P[List[Expr]] =
    (callArgExpr ~ (commandArgSep ~> callArgExpr).* ~ (inlineSpacing ~> sym(",")).?).map {
      case first ~ rest ~ _ => first :: rest
    }

  private lazy val blockPassArgExpr: P[Expr] =
    (sym("&") ~ identifier).map { case _ ~ name => LocalVar(s"&$name") } /
      (sym("&") ~ symbolLiteral).map { case _ ~ symbol => symbol }

  private lazy val doubleSplatArgExpr: P[Expr] =
    (sym("**") ~ refer(expr)).map(_._2)

  private lazy val splatArgExpr: P[Expr] =
    (sym("*") ~ refer(expr)).map(_._2)

  private lazy val keywordArgExpr: P[Expr] =
    ((identifierNoSpace <~ labelColon) ~ refer(expr)).map {
      case name ~ value =>
        HashLiteral(List(SymbolLiteral(name, UnknownSpan) -> value))
    }

  private lazy val hashRocketArgKeyExpr: P[Expr] =
    symbolLiteral / stringLiteral / variable / constRef

  private lazy val hashRocketArgExpr: P[Expr] =
    (((hashRocketArgKeyExpr <~ sym("=>").and).and ~> hashRocketArgKeyExpr) ~ sym("=>") ~ refer(expr)).map {
      case key ~ _ ~ value => HashLiteral(List(key -> value))
    }

  private lazy val callArgExpr: P[Expr] =
    blockPassArgExpr / doubleSplatArgExpr / splatArgExpr / keywordArgExpr / hashRocketArgExpr / refer(expr)

  private lazy val functionCall: P[Expr] =
    (methodIdentifier ~ callArgs).map { case name ~ args => Call(None, name, args) }

  private lazy val receiverForCommand: P[Expr] =
    constRef / selfExpr / variable

  private lazy val receiverCommandHead: P[Expr ~ String] =
    (receiverForCommand <~ sym(".")) ~ receiverMethodNameNoSpace

  private lazy val commandCall: P[Expr] =
    ((((methodIdentifierNoSpace <~ spacing1) ~ commandArgs.and).and ~> (methodIdentifierNoSpace <~ spacing1)) ~ commandArgs).map {
      case name ~ args => Call(None, name, args)
    }

  private lazy val receiverCommandCall: P[Expr] =
    ((((receiverCommandHead <~ spacing1) ~ commandArgs.and).and ~> (receiverCommandHead <~ spacing1)) ~ commandArgs).map {
      case receiverAndMethod ~ args =>
        val receiver ~ methodName = receiverAndMethod
        Call(Some(receiver), methodName, args)
    }

  private lazy val receiverCommandNoArgs: P[Expr] =
    receiverCommandHead.map {
      case receiver ~ methodName => Call(Some(receiver), methodName, Nil)
    }

  private def appendCommandArgs(target: Expr, args: List[Expr]): Expr =
    target match {
      case call @ Call(receiver, methodName, existingArgs, span) =>
        if(existingArgs.isEmpty) Call(receiver, methodName, args, span)
        else Call(Some(call), "call", args)
      case other =>
        Call(Some(other), "call", args)
    }

  private lazy val chainedCommandCall: P[Expr] =
    ((((chainedCallExpr <~ spacing1) ~ commandArgs.and).and ~> (chainedCallExpr <~ spacing1)) ~ commandArgs).map {
      case call ~ args => appendCommandArgs(call, args)
    }

  private lazy val primaryNoCall: P[Expr] =
    lambdaLiteral /
      integerLiteral /
      floatLiteral /
      stringLiteral /
      singleQuotedStringLiteral /
      backtickLiteral /
      percentQuotedStringLiteral /
      percentWordArray /
      percentSymbolArray /
      regexLiteral /
      symbolLiteral /
      boolLiteral /
      nilLiteral /
      selfExpr /
      constRef /
      arrayLiteral /
      hashLiteral /
      variable /
      parenExpr

  private lazy val commandExpr: P[Expr] =
    receiverCommandCall / commandCall

  private lazy val bareNoArgPunctuatedCall: P[Expr] =
    punctuatedMethodIdentifier.map(name => Call(None, name, Nil))

  private lazy val positionalParam: P[String] =
    (identifier ~ (sym("=") ~ refer(expr)).?).map(_._1)

  private lazy val keywordParam: P[String] =
    ((identifierNoSpace <~ labelColon) ~ refer(expr).?).map {
      case name ~ _ => s"$name:"
    }

  private lazy val formalParam: P[String] =
    (sym("**") ~ identifier).map { case _ ~ name => s"**$name" } /
      (sym("*") ~ identifier).map { case _ ~ name => s"*$name" } /
      (sym("&") ~ identifier).map { case _ ~ name => s"&$name" } /
      keywordParam /
      positionalParam

  private lazy val destructuredBlockParam: P[String] =
    (sym("(") ~> sepBy0(formalParam, sym(",")) <~ sym(")")).map { parts =>
      s"(${parts.mkString(",")})"
    }

  private lazy val blockParams: P[List[String]] =
    sym("|") ~> sepBy0(destructuredBlockParam / formalParam, sym(",")) <~ sym("|")

  private lazy val doBlock: P[Block] =
    (
      kw("do") ~
      blockParams.? ~
      statementSep.* ~
      blockStatementsUntil(kw("ensure") / kw("end")) ~
      statementSep.* ~
      (kw("ensure") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~
      kw("end")
    ).map {
      case _ ~ maybeParams ~ _ ~ body ~ _ ~ ensureOpt ~ _ ~ _ =>
        val statements = ensureOpt match {
          case Some(_ ~ _ ~ ensureBody) => List(BeginRescue(body, Nil, Nil, ensureBody))
          case None => body
        }
        Block(maybeParams.getOrElse(Nil), statements)
    }

  private lazy val braceBlock: P[Block] =
    (sym("{") ~ blockParams.? ~ statementSep.* ~ blockStatementsUntil(sym("}")) ~ statementSep.* ~ spacing ~ sym("}")).map {
      case _ ~ maybeParams ~ _ ~ body ~ _ ~ _ ~ _ => Block(maybeParams.getOrElse(Nil), body)
    }

  private lazy val blockLiteral: P[Block] =
    doBlock / braceBlock

  private lazy val lambdaLiteral: P[Expr] =
    (sym("->") ~ (params / bareParams).? ~ blockLiteral).map {
      case _ ~ maybeParams ~ block =>
        val lambdaParams = maybeParams.getOrElse(block.params)
        LambdaLiteral(lambdaParams, block.body)
    }

  private lazy val lineBreak: P[Unit] =
    ("\n".s ~ inlineSpacing).void

  private lazy val statementSep: P[Unit] =
    sym(";").void / lineBreak.+.void

  private lazy val blockCallExpr: P[Expr] =
    chainedCallExpr /
      chainedCommandCall /
      receiverCommandNoArgs /
      receiverCommandCall /
      functionCall /
      commandCall

  private lazy val blockCallStmt: P[Statement] =
    ((((blockCallExpr <~ spacing) ~ blockLiteral.and).and ~> (blockCallExpr <~ spacing)) ~ blockLiteral).map {
      case call ~ block => ExprStmt(CallWithBlock(call, block))
    }

  private lazy val operatorMethodName: P[String] =
    "**".s /
    "<<".s /
      ">>".s /
      "*".s /
      "+".s /
      "-".s /
      "/".s /
      "%".s /
      "|".s /
      "&".s /
      "^".s

  private lazy val suffixMethodName: P[String] =
    receiverMethodNameNoSpace / operatorMethodName

  private lazy val methodSuffix: P[Expr => Expr] =
    ((sym(".") / sym("&.")) ~ suffixMethodName ~ callArgs.?).map {
      case _ ~ name ~ argsOpt =>
        val args = argsOpt.getOrElse(Nil)
        (receiver: Expr) => Call(Some(receiver), name, args)
    }

  private lazy val indexSuffix: P[Expr => Expr] =
    (sym("[") ~ spacing ~ sepBy0(spacedExpr, sym(",")) ~ spacing ~ sym("]")).map {
      case _ ~ _ ~ args ~ _ ~ _ =>
        (receiver: Expr) => Call(Some(receiver), "[]", args)
    }

  private lazy val callSuffix: P[Expr => Expr] =
    methodSuffix / indexSuffix / blockAttachSuffix

  private lazy val blockAttachSuffix: P[Expr => Expr] =
    (inlineSpacing ~> refer(blockLiteral)).map { block =>
      (receiver: Expr) => CallWithBlock(receiver, block)
    }

  private lazy val nonIndexCallSuffix: P[Expr => Expr] =
    methodSuffix / blockAttachSuffix

  private lazy val postfixNoIndexExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / primaryNoCall) ~ nonIndexCallSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }

  private lazy val indexTarget: P[(Expr, List[Expr])] =
    (postfixNoIndexExpr ~ sym("[") ~ spacing ~ sepBy0(spacedExpr, sym(",")) ~ spacing ~ sym("]")).map {
      case receiver ~ _ ~ _ ~ args ~ _ ~ _ => receiver -> args
    }

  private lazy val postfixExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / primaryNoCall) ~ callSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }

  private lazy val chainedCallExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / primaryNoCall) ~ callSuffix.+).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }

  private def infix(op: String): P[(Expr, Expr) => Expr] =
    sym(op).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private def infixKeyword(op: String): P[(Expr, Expr) => Expr] =
    kw(op).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private def infixLogicalKeyword(op: String): P[(Expr, Expr) => Expr] =
    ((op.s <~ !identCont) <~ spacing).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private lazy val powerExpr: P[Expr] =
    (postfixExpr ~ (sym("**") ~> refer(powerExpr)).?).map {
      case base ~ Some(exp) => BinaryOp(base, "**", exp)
      case base ~ None => base
    }

  private lazy val unaryExpr: P[Expr] =
    (kw("not") ~ refer(unaryExpr)).map {
      case _ ~ target => UnaryOp("not", target)
    } /
    (sym("!") ~ refer(unaryExpr)).map {
      case _ ~ target => UnaryOp("!", target)
    } /
      (sym("~") ~ refer(unaryExpr)).map {
        case _ ~ target => UnaryOp("~", target)
      } /
      (sym("-") ~ refer(unaryExpr)).map {
        case _ ~ target => UnaryOp("-", target)
      } /
      (sym("+") ~ refer(unaryExpr)).map {
        case _ ~ target => UnaryOp("+", target)
      } /
      (powerExpr <~ horizontalSpacing)

  private lazy val mulDivExpr: P[Expr] =
    chainl(unaryExpr)(infix("*") / infix("/") / infix("%"))

  private lazy val addSubExpr: P[Expr] =
    chainl(mulDivExpr)(infix("+") / infix("-"))

  private lazy val shiftExpr: P[Expr] =
    chainl(addSubExpr)(infix("<<") / infix(">>"))

  private lazy val bitAndExpr: P[Expr] =
    chainl(shiftExpr)(infix("&"))

  private lazy val bitOrExpr: P[Expr] =
    chainl(bitAndExpr)(infix("|") / infix("^"))

  private lazy val relationalExpr: P[Expr] =
    chainl(bitOrExpr)(infix("<=") / infix(">=") / infix("<") / infix(">"))

  private lazy val equalityExpr: P[Expr] =
    chainl(relationalExpr)(infix("==") / infix("===") / infix("!=") / infix("<=>") / infix("=~") / infix("!~"))

  private lazy val rangeExpr: P[Expr] =
    (equalityExpr ~ ((sym("..") / sym("...")) ~ equalityExpr).?).map {
      case start ~ Some(op ~ end) => RangeExpr(start, end, exclusive = op == "...")
      case start ~ None => start
    }

  private lazy val andExpr: P[Expr] =
    chainl(rangeExpr)(infix("&&") / infixLogicalKeyword("and"))

  private lazy val orExpr: P[Expr] =
    chainl(andExpr)(infix("||") / infixLogicalKeyword("or"))

  private lazy val conditionalExpr: P[Expr] =
    (orExpr ~ (sym("?") ~ refer(expr) ~ sym(":") ~ refer(expr)).?).map {
      case condition ~ Some(_ ~ thenExpr ~ _ ~ elseExpr) =>
        IfExpr(
          condition,
          List(ExprStmt(thenExpr)),
          List(ExprStmt(elseExpr))
        )
      case condition ~ None =>
        condition
    }

  private lazy val assignExpr: P[Expr] =
    (((assignableName <~ sym("=").and).and ~> assignableName) ~ sym("=") ~ refer(expr)).map {
      case name ~ _ ~ value => AssignExpr(name, value)
    }

  private lazy val receiverAssignableHead: P[Expr ~ String] =
    (receiverForCommand <~ sym(".")) ~ identifier

  private lazy val receiverAssignExpr: P[Expr] =
    (((receiverAssignableHead <~ sym("=").and).and ~> receiverAssignableHead) ~ sym("=") ~ refer(expr)).map {
      case receiver ~ name ~ _ ~ value =>
        Call(Some(receiver), s"${name}=", List(value))
    }

  private lazy val receiverLogicalAssignExpr: P[Expr] =
    (((receiverAssignableHead <~ (sym("||=") / sym("&&=")).and).and ~> receiverAssignableHead) ~ (sym("||=") / sym("&&=")) ~ refer(expr)).map {
      case receiver ~ name ~ op ~ value =>
        val lhs = Call(Some(receiver), name, Nil)
        val underlying = if(op == "||=") "||" else "&&"
        BinaryOp(lhs, underlying, value)
    }

  private lazy val indexAssignExpr: P[Expr] =
    (((indexTarget <~ sym("=").and).and ~> indexTarget) ~ sym("=") ~ refer(expr)).map {
      case (receiver, args) ~ _ ~ value =>
        Call(Some(receiver), "[]=", args :+ value)
    }

  private lazy val indexLogicalAssignExpr: P[Expr] =
    (((indexTarget <~ (sym("||=") / sym("&&=")).and).and ~> indexTarget) ~ (sym("||=") / sym("&&=")) ~ refer(expr)).map {
      case (receiver, args) ~ op ~ value =>
        val lhs = Call(Some(receiver), "[]", args)
        val underlying = if(op == "||=") "||" else "&&"
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), "[]=", args :+ rhs)
    }

  private lazy val receiverCompoundAssignExpr: P[Expr] =
    (((receiverAssignableHead <~ compoundAssignOperator.and).and ~> receiverAssignableHead) ~ compoundAssignOperator ~ refer(expr)).map {
      case receiver ~ name ~ op ~ value =>
        val underlying = op.dropRight(1)
        val lhs = Call(Some(receiver), name, Nil)
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), s"${name}=", List(rhs))
    }

  private lazy val logicalAssignExpr: P[Expr] =
    (((assignableName <~ (sym("||=") / sym("&&=")).and).and ~> assignableName) ~ (sym("||=") / sym("&&=")) ~ refer(expr)).map {
      case name ~ op ~ value =>
        val underlying = if(op == "||=") "||" else "&&"
        AssignExpr(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val compoundAssignExpr: P[Expr] =
    (((assignableName <~ compoundAssignOperator.and).and ~> assignableName) ~ compoundAssignOperator ~ refer(expr)).map {
      case name ~ op ~ value =>
        val underlying = op.dropRight(1)
        AssignExpr(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val expr: P[Expr] =
    receiverAssignExpr /
      receiverLogicalAssignExpr /
      receiverCompoundAssignExpr /
      indexLogicalAssignExpr /
      indexAssignExpr /
      logicalAssignExpr /
      compoundAssignExpr /
      assignExpr /
      conditionalExpr

  private lazy val assignableName: P[String] =
    identifier / instanceVarName / classVarName / globalVarName

  private def assignableAsExpr(name: String): Expr =
    if(name.startsWith("@@")) ClassVar(name)
    else if(name.startsWith("@")) InstanceVar(name)
    else if(name.startsWith("$")) GlobalVar(name)
    else LocalVar(name)

  private lazy val compoundAssignOperator: P[String] =
    sym("<<=") /
      sym(">>=") /
      sym("+=") /
      sym("-=") /
      sym("*=") /
      sym("/=") /
      sym("%=") /
      sym("|=") /
      sym("&=") /
      sym("^=")

  private lazy val compoundAssignStmt: P[Statement] =
    (assignableName ~ compoundAssignOperator ~ refer(expr)).map {
      case name ~ op ~ value =>
        val underlying = op.dropRight(1)
        Assign(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val logicalAssignStmt: P[Statement] =
    (assignableName ~ (sym("||=") / sym("&&=")) ~ refer(expr)).map {
      case name ~ op ~ value =>
        val underlying = if(op == "||=") "||" else "&&"
        Assign(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val assignStmt: P[Statement] =
    (assignableName ~ sym("=") ~ refer(expr)).map {
      case name ~ _ ~ value => Assign(name, value)
    }

  private lazy val multiAssignStmt: P[Statement] =
    (assignableName ~ (sym(",") ~ assignableName).+ ~ sym("=") ~ refer(expr)).map {
      case first ~ rest ~ _ ~ value =>
        val names = first :: rest.map(_._2)
        MultiAssign(names, value)
    }

  private lazy val constAssignName: P[String] =
    constPathSegments.map(_.mkString("::"))

  private lazy val constAssignStmt: P[Statement] =
    (((constAssignName <~ sym("=").and).and ~> constAssignName) ~ sym("=") ~ refer(expr)).map {
      case name ~ _ ~ value => Assign(name, value)
    }

  private lazy val returnValueExpr: P[Expr] =
    sepBy1(refer(expr), sym(",")).map {
      case value :: Nil => value
      case values => ArrayLiteral(values)
    }

  private lazy val returnStmt: P[Statement] =
    (kw("return") ~ returnValueExpr.?).map {
      case _ ~ value => Return(value)
    }

  private lazy val retryStmt: P[Statement] =
    kw("retry").map(_ => Retry())

  private lazy val params: P[List[String]] =
    sym("(") ~> sepBy0(formalParam, sym(",")) <~ sym(")")

  private lazy val bareParams: P[List[String]] =
    sepBy1(formalParam, sym(","))

  private lazy val defReceiverName: P[String] =
    constPathSegments.map(_.mkString("::")) /
      kw("self").map(_ => "self") /
      identifierNoSpace

  private lazy val defName: P[String] =
    ((defReceiverName <~ sym(".")) ~ methodIdentifier).map {
      case receiver ~ methodName => s"$receiver.$methodName"
    } /
      methodIdentifier

  private lazy val simpleStatement: P[Statement] =
    returnStmt /
      retryStmt /
      logicalAssignStmt /
      compoundAssignStmt /
      constAssignStmt /
      multiAssignStmt /
      assignStmt /
      chainedCommandCall.map(ExprStmt(_)) /
      refer(expr).map(ExprStmt(_))

  private lazy val statementBase: P[Statement] =
    (
      refer(beginStmt) /
      refer(whileStmt) /
      refer(untilStmt) /
      refer(forStmt) /
      refer(caseStmt) /
      refer(defStmt) /
      refer(singletonClassStmt) /
      refer(classStmt) /
      refer(moduleStmt) /
      refer(ifStmt) /
      refer(unlessStmt) /
      simpleStatement
    )

  private lazy val statement: P[Statement] =
    (statementBase ~ (inlineSpacing ~> modifierSuffix).?).map {
      case stmt ~ Some(modifier) => modifier(stmt)
      case stmt ~ None => stmt
    }

  private lazy val topLevelStatements: P[List[Statement]] =
    sepBy0(refer(statement), statementSep)

  private def blockStatementsUntil(stop: P[Any]): P[List[Statement]] =
    (stop.and ~> success(Nil)) /
      ((refer(statement) ~ (statementSep.+ ~> refer(blockStatementsUntil(stop))).?).map {
        case stmt ~ Some(rest) => stmt :: rest
        case stmt ~ None => List(stmt)
      })

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
    (
      kw("def") ~
      defName ~
      (params / bareParams).? ~
      statementSep.* ~
      blockStatementsUntil(kw("rescue") / kw("else") / kw("ensure") / kw("end")) ~
      statementSep.* ~
      rescueClause.* ~
      statementSep.* ~
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("ensure") / kw("end"))).? ~
      statementSep.* ~
      (kw("ensure") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~
      kw("end")
    ).map {
      case _ ~ name ~ maybeParams ~ _ ~ body ~ _ ~ rescues ~ _ ~ elseOpt ~ _ ~ ensureOpt ~ _ ~ _ =>
        val elseBody = elseOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val ensureBody = ensureOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val statements =
          if(rescues.nonEmpty || elseBody.nonEmpty || ensureBody.nonEmpty) {
            List(BeginRescue(body, rescues, elseBody, ensureBody))
          } else {
            body
          }
        Def(name, maybeParams.getOrElse(Nil), statements)
    }

  private lazy val singletonClassStmt: P[Statement] =
    (kw("class") ~ sym("<<") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ _ ~ receiver ~ _ ~ body ~ _ ~ _ =>
        SingletonClassDef(receiver, body)
    }

  private lazy val forStmt: P[Statement] =
    (kw("for") ~ identifier ~ kw("in") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ name ~ _ ~ iterable ~ _ ~ body ~ _ ~ _ =>
        ForIn(name, iterable, body)
    }

  private lazy val whenClause: P[WhenClause] =
    (kw("when") ~ sepBy1(refer(expr), sym(",")) ~ statementSep.* ~ blockStatementsUntil(kw("when") / kw("else") / kw("end")) ~ statementSep.*).map {
      case _ ~ patterns ~ _ ~ body ~ _ => WhenClause(patterns, body)
    }

  private lazy val caseStmt: P[Statement] =
    (
      kw("case") ~ refer(expr).? ~ statementSep.* ~ whenClause.+ ~
      statementSep.* ~
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~ kw("end")
    ).map {
      case _ ~ scrutinee ~ _ ~ whens ~ _ ~ elseOpt ~ _ ~ _ =>
        val elseBody = elseOpt.map { case _ ~ _ ~ body => body }.getOrElse(Nil)
        CaseExpr(scrutinee, whens, elseBody)
    }

  private lazy val whileStmt: P[Statement] =
    (kw("while") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ condition ~ _ ~ body ~ _ ~ _ =>
        WhileExpr(condition, body)
    }

  private lazy val untilStmt: P[Statement] =
    (kw("until") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ condition ~ _ ~ body ~ _ ~ _ =>
        UntilExpr(condition, body)
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
      } /
      (kw("rescue") ~ refer(expr)).map {
        case _ ~ fallback =>
          (stmt: Statement) =>
            BeginRescue(
              List(stmt),
              List(RescueClause(Nil, None, List(ExprStmt(fallback)))),
              Nil,
              Nil
            )
      } /
      (kw("while") ~ refer(expr)).map {
        case _ ~ condition =>
          (stmt: Statement) => WhileExpr(condition, List(stmt))
      } /
      (kw("until") ~ refer(expr)).map {
        case _ ~ condition =>
          (stmt: Statement) => UntilExpr(condition, List(stmt))
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

  private def encodeDoubleQuoted(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    } + "\""

  private def normalizeHeredoc(input: String): String = {
    val heredocPattern = raw"""(?<![\w\)\]\}])<<([~-]?)(?:'([^'\n]+)'|"([^"\n]+)"|`([^`\n]+)`|([A-Za-z_][A-Za-z0-9_]*))""".r
    val outputLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val replacements = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    val pending = scala.collection.mutable.Queue.empty[PendingHeredoc]
    var nextId = 0
    val lines = input.split("\n", -1)

    lines.foreach { line =>
      if(pending.nonEmpty) {
        val current = pending.front
        val isTerminator =
          if(current.allowIndentedTerminator) line.trim == current.terminator
          else line == current.terminator
        if(isTerminator) {
          pending.dequeue()
          val content =
            if(current.lines.isEmpty) ""
            else current.lines.mkString("\n") + "\n"
          replacements += current.token -> encodeDoubleQuoted(content)
        } else {
          current.lines += line
        }
      } else {
        val matches = heredocPattern.findAllMatchIn(line).toList
        if(matches.isEmpty) {
          outputLines += line
        } else {
          val tokens = matches.map { m =>
            val token = s"__MACROPEG_HEREDOC_${nextId}__"
            nextId += 1
            val marker = m.group(1)
            val terminator =
              Option(m.group(2))
                .orElse(Option(m.group(3)))
                .orElse(Option(m.group(4)))
                .orElse(Option(m.group(5)))
                .getOrElse("")
            val allowIndented = marker == "-" || marker == "~"
            pending.enqueue(
              PendingHeredoc(token, terminator, allowIndented, scala.collection.mutable.ArrayBuffer.empty[String])
            )
            token
          }
          val replacedLine = (matches zip tokens).reverse.foldLeft(line) { case (acc, (m, token)) =>
            acc.substring(0, m.start) + token + acc.substring(m.end)
          }
          outputLines += replacedLine
        }
      }
    }

    if(pending.nonEmpty) input
    else {
      replacements.foldLeft(outputLines.mkString("\n")) { case (text, (token, value)) =>
        text.replace(token, value)
      }
    }
  }

  private def stripXOptionPreamble(input: String): String = {
    val lines = input.split("\n", -1).toList
    val shebangIndex = lines.indexWhere(_.startsWith("#!"))
    if(shebangIndex > 0 && lines.take(shebangIndex).exists(_.contains("-x"))) {
      lines.drop(shebangIndex + 1).mkString("\n")
    } else input
  }

  def parse(input: String): Either[String, Program] = {
    val normalized = normalizeHeredoc(stripXOptionPreamble(input))
    parseAll(program, normalized).left.map(f => formatFailure(normalized, f))
  }
}
