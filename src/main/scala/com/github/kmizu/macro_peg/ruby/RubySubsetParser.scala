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

  private case class HeredocResumeState(
    quoteMode: Char = 0,
    percentOpen: Char = 0,
    percentClose: Char = 0,
    percentDepth: Int = 0,
    percentInterpolation: Boolean = false
  )

  private def percentLiteralStart(line: String, index: Int, limit: Int): Option[(Char, Char, Boolean)] = {
    if(index >= limit || line.charAt(index) != '%') None
    else {
      val percentTypes = Set('q', 'Q', 'w', 'W', 'i', 'I', 'r', 'x', 's')
      val (literalType, delimiterIndex) =
        if(index + 1 < limit && percentTypes.contains(line.charAt(index + 1))) {
          (Some(line.charAt(index + 1)), index + 2)
        } else {
          (None, index + 1)
        }
      if(delimiterIndex >= limit) None
      else {
        val delimiter = line.charAt(delimiterIndex)
        val pairedDelimiter = delimiter match {
          case '{' => Some('}')
          case '(' => Some(')')
          case '[' => Some(']')
          case '<' => Some('>')
          case '%' => Some('%')
          case '|' => Some('|')
          case '"' => Some('"')
          case '\'' => Some('\'')
          case '/' => Some('/')
          case _ => None
        }
        pairedDelimiter.map { close =>
          val interpolation =
            literalType match {
              case Some('q' | 'w' | 'i') => false
              case _ => true
            }
          (delimiter, close, interpolation)
        }
      }
    }
  }

  private def isCodePositionForHeredoc(line: String, index: Int): Boolean = {
    var i = 0
    var quoteMode: Char = 0
    var percentOpen: Char = 0
    var percentClose: Char = 0
    var percentDepth = 0
    var percentInterpolation = false
    var interpolationDepth = 0
    var resumeStack = List.empty[HeredocResumeState]

    while(i < index) {
      val ch = line.charAt(i)
      if(quoteMode != 0) {
        if(ch == '\\' && i + 1 < index) {
          i += 1
        } else if((quoteMode == '"' || quoteMode == '`') && ch == '#' && i + 1 < index && line.charAt(i + 1) == '{') {
          resumeStack = HeredocResumeState(quoteMode = quoteMode) :: resumeStack
          quoteMode = 0
          interpolationDepth = 1
          i += 1
        } else if(ch == quoteMode) {
          quoteMode = 0
        }
      } else if(percentClose != 0) {
        if(ch == '\\' && i + 1 < index) {
          i += 1
        } else if(percentInterpolation && ch == '#' && i + 1 < index && line.charAt(i + 1) == '{') {
          resumeStack =
            HeredocResumeState(
              percentOpen = percentOpen,
              percentClose = percentClose,
              percentDepth = percentDepth,
              percentInterpolation = percentInterpolation
            ) :: resumeStack
          percentOpen = 0
          percentClose = 0
          percentDepth = 0
          percentInterpolation = false
          interpolationDepth = 1
          i += 1
        } else if(ch == percentClose) {
          percentDepth -= 1
          if(percentDepth == 0) {
            percentOpen = 0
            percentClose = 0
            percentInterpolation = false
          }
        } else if(percentOpen != percentClose && ch == percentOpen) {
          percentDepth += 1
        }
      } else if(interpolationDepth > 0) {
        percentLiteralStart(line, i, index) match {
          case Some((open, close, interpolation)) =>
            percentOpen = open
            percentClose = close
            percentDepth = 1
            percentInterpolation = interpolation
            i += (if(line.charAt(i + 1) == open) 1 else 2)
          case None =>
            ch match {
              case '#' => return false
              case '\'' => quoteMode = '\''
              case '"' => quoteMode = '"'
              case '`' => quoteMode = '`'
              case '{' => interpolationDepth += 1
              case '}' =>
                interpolationDepth -= 1
                if(interpolationDepth == 0 && resumeStack.nonEmpty) {
                  val resume = resumeStack.head
                  resumeStack = resumeStack.tail
                  quoteMode = resume.quoteMode
                  percentOpen = resume.percentOpen
                  percentClose = resume.percentClose
                  percentDepth = resume.percentDepth
                  percentInterpolation = resume.percentInterpolation
                }
              case _ =>
            }
        }
      } else {
        percentLiteralStart(line, i, index) match {
          case Some((open, close, interpolation)) =>
            percentOpen = open
            percentClose = close
            percentDepth = 1
            percentInterpolation = interpolation
            i += (if(line.charAt(i + 1) == open) 1 else 2)
          case None =>
            ch match {
              case '#' => return false
              case '\'' => quoteMode = '\''
              case '"' => quoteMode = '"'
              case '`' => quoteMode = '`'
              case _ =>
            }
        }
      }
      i += 1
    }

    quoteMode == 0 && percentClose == 0
  }

  // ─── Lexical Primitives ───────────────────────────────────────────

  private lazy val horizontalSpaceChar: P[Unit] =
    range(' ' to ' ', '\t' to '\t', '\r' to '\r').map(_ => ())

  private lazy val newlineChar: P[Unit] =
    "\n".s.void

  private lazy val lineContinuation: P[Unit] =
    "\\\n".s.void

  private lazy val comment: P[Unit] =
    ("#".s ~ (!"\n".s ~ any).*).map(_ => ())

  private lazy val blockComment: P[Unit] =
    ("=begin".s ~ (!"=end".s ~ any).* ~ "=end".s).map(_ => ())

  private lazy val inlineSpacing: P[Unit] =
    (horizontalSpaceChar.+ / lineContinuation / comment / blockComment).*.void

  private lazy val horizontalSpacing: P[Unit] =
    (horizontalSpaceChar / lineContinuation).*.void

  private lazy val spacing: P[Unit] =
    (horizontalSpaceChar.+ / newlineChar / lineContinuation / comment / blockComment).*.void

  private lazy val spacing1: P[Unit] =
    (horizontalSpaceChar / comment / blockComment).+.void

  private def token[A](parser: P[A]): P[A] =
    (parser ~ inlineSpacing).map(_._1)

  private lazy val namedGuardEnabled: Boolean =
    Option(System.getenv("RUBY_PARSER_NAMED_GUARD")).contains("1")

  private def guarded[A](name: String)(parser: => P[A]): P[A] =
    if(namedGuardEnabled) guard(s"ruby.$name")(parser) else parser

  private def kw(name: String): P[String] =
    token(string(name) <~ !identCont).label(s"`$name`")

  private def sym(name: String): P[String] =
    token(string(name))

  // ─── Token Helpers & Operators ────────────────────────────────────

  private lazy val assignEq: P[String] =
    token("=".s <~ !"=".s <~ !">".s <~ !"~".s)

  private lazy val labelColon: P[String] =
    token(":".s <~ !":".s)

  private lazy val symbolPrefix: P[String] =
    ":".s <~ !":".s

  private lazy val asciiChar: P[String] =
    range('\u0000' to '\u007f')

  private lazy val nonAsciiChar: P[String] =
    (!asciiChar.and ~ any).map(_._2)

  // ─── Identifiers & Names ────────────────────────────────────────

  private lazy val identStart: P[String] =
    range('a' to 'z', 'A' to 'Z', '_' to '_') /
      nonAsciiChar

  private lazy val identCont: P[String] =
    range('a' to 'z', 'A' to 'Z', '0' to '9', '_' to '_') /
      nonAsciiChar

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

  private lazy val labelNameNoSpace: P[String] =
    identifierRaw / reservedWord

  private lazy val identifier: P[String] =
    token(identifierNoSpace)

  private lazy val methodSuffixChar: P[String] =
    "?".s /
      ("!".s <~ !"=".s <~ !"~".s) /
      ("=".s <~ !"=".s <~ !">".s <~ !"~".s)

  private lazy val methodIdentifierWithSuffixRaw: P[String] =
    (identifierRaw ~ methodSuffixChar).map {
      case base ~ suffix => base + suffix
    }

  private lazy val symbolLabelNameNoSpace: P[String] =
    methodIdentifierWithSuffixRaw /
      labelNameNoSpace

  private lazy val methodIdentifierRaw: P[String] =
    methodIdentifierWithSuffixRaw /
      (!reservedWord ~ identifierRaw).map(_._2)

  private lazy val methodIdentifierNoSpace: P[String] =
    methodIdentifierRaw

  private lazy val methodIdentifier: P[String] =
    token(methodIdentifierNoSpace)

  private def keywordMethodName(name: String): P[String] =
    string(name) <~ !identCont

  private lazy val bareKeywordMethodNameNoSpace: P[String] =
    keywordMethodName("private") /
      keywordMethodName("public") /
      keywordMethodName("protected") /
      keywordMethodName("ruby2_keywords")

  private lazy val receiverKeywordMethodNameNoSpace: P[String] =
    keywordMethodName("class") /
      keywordMethodName("def") /
      bareKeywordMethodNameNoSpace /
      keywordMethodName("begin") /
      keywordMethodName("end") /
      keywordMethodName("for") /
      keywordMethodName("self")

  private lazy val receiverMethodNameNoSpace: P[String] =
    methodIdentifierNoSpace / receiverKeywordMethodNameNoSpace

  private lazy val punctuatedMethodIdentifierNoSpace: P[String] =
    methodIdentifierWithSuffixRaw

  private lazy val punctuatedMethodIdentifier: P[String] =
    token(punctuatedMethodIdentifierNoSpace)

  private lazy val constStart: P[String] =
    range('A' to 'Z')

  private lazy val constName: P[String] =
    token((constStart ~ identCont.*).map { case h ~ t => h + t.mkString })

  private lazy val constNameNoSpace: P[String] =
    (constStart ~ identCont.*).map { case h ~ t => h + t.mkString }

  private lazy val instanceVarName: P[String] =
    token(("@".s ~ identifierRaw).map { case _ ~ name => s"@$name" })

  private lazy val classVarName: P[String] =
    token(("@@".s ~ identifierRaw).map { case _ ~ name => s"@@$name" })

  private lazy val globalVarName: P[String] =
    token(
      ("$".s ~ identifierRaw).map { case _ ~ name => s"$$$name" } /
        ("$-".s ~ range('0' to '9', 'a' to 'z', 'A' to 'Z', '_' to '_').+).map {
          case _ ~ chars => s"$$-${chars.mkString}"
        } /
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
        "$\"".s /
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

  // ─── Numeric Literals ──────────────────────────────────────────

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

  private lazy val numericSuffix: P[String] =
    "ri".s /
      "ir".s /
      "r".s /
      "i".s

  private def applyNumericSuffix(base: Expr, suffix: Option[String]): Expr =
    suffix match {
      case Some("r") => RationalLiteral(base)
      case Some("i") => ImaginaryLiteral(base)
      case Some("ri") => ImaginaryLiteral(RationalLiteral(base))
      case Some("ir") => RationalLiteral(ImaginaryLiteral(base))
      case _ => base
    }

  private lazy val integerLiteral: P[Expr] =
    token(((integerLiteralRaw <~ !(".".s ~ range('0' to '9'))) ~ numericSuffix.?) <~ !identCont).map {
      case (raw, base) ~ suffix =>
        applyNumericSuffix(IntLiteral(BigInt(raw.replace("_", ""), base)), suffix)
    }.memo

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
    token((floatLiteralRaw ~ numericSuffix.?) <~ !identCont).map {
      case raw ~ suffix =>
        val normalized = raw.replace("_", "")
        applyNumericSuffix(FloatLiteral(Try(normalized.toDouble).getOrElse(Double.NaN)), suffix)
    }.memo

  // ─── String Escapes ────────────────────────────────────────────

  private lazy val escapedChar: P[String] =
    ("\\".s ~ any).map {
      case _ ~ "n" => "\n"
      case _ ~ "t" => "\t"
      case _ ~ "r" => "\r"
      case _ ~ "\"" => "\""
      case _ ~ "\\" => "\\"
      case _ ~ c => c
    }

  private lazy val hexDigit: P[String] =
    range('0' to '9', 'a' to 'f', 'A' to 'F')

  private def codePointString(hex: String): String =
    Try(Integer.parseInt(hex, 16))
      .toOption
      .map(Character.toChars)
      .map(_.mkString)
      .getOrElse("")

  private def codePointStringFromBase(digits: String, base: Int): String =
    Try(Integer.parseInt(digits, base))
      .toOption
      .map(Character.toChars)
      .map(_.mkString)
      .getOrElse("")

  private def controlCharString(char: String): String =
    char.headOption.map(ch => (ch.toInt & 0x1f).toChar.toString).getOrElse("")

  private def metaCharString(char: String): String =
    char.headOption.map(ch => ((ch.toInt | 0x80) & 0xff).toChar.toString).getOrElse("")

  private def metaControlCharString(char: String): String =
    char.headOption.map(ch => (((ch.toInt & 0x1f) | 0x80) & 0xff).toChar.toString).getOrElse("")

  private lazy val unicodeEscapedChar: P[String] =
    ("\\u".s ~ hexDigit ~ hexDigit ~ hexDigit ~ hexDigit).map {
      case _ ~ a ~ b ~ c ~ d => codePointString(a + b + c + d)
    } /
      ("\\".s ~ range('0' to '7') ~ range('0' to '7').? ~ range('0' to '7').?).map {
        case _ ~ first ~ second ~ third =>
          val digits = first + second.getOrElse("") + third.getOrElse("")
          codePointStringFromBase(digits, 8)
      } /
      ("\\x".s ~ hexDigit ~ hexDigit).map {
        case _ ~ a ~ b => codePointString(a + b)
      } /
      ("\\u{".s ~ hexDigit.+ ~ "}".s).map {
        case _ ~ digits ~ _ => codePointString(digits.mkString)
      }

  private lazy val escapedCharLiteralChar: P[String] =
    ("\\M-\\C-".s ~ any).map { case _ ~ char => metaControlCharString(char) } /
      ("\\C-".s ~ any).map { case _ ~ char => controlCharString(char) } /
      ("\\M-".s ~ any).map { case _ ~ char => metaCharString(char) } /
      unicodeEscapedChar /
      escapedChar /
      any

  private lazy val escapedAnyChar: P[String] =
    ("\\".s ~ any).map { case _ ~ c => s"\\$c" }

  private def quotedInInterpolation(delim: String): P[String] =
    (delim.s ~ (escapedAnyChar / (!delim.s ~ any).map(_._2)).* ~ delim.s).map {
      case open ~ chars ~ close => open + chars.mkString + close
    }

  // ─── String & Backtick Literals ────────────────────────────────

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

  private lazy val bulkStringChars: P[String] = takeUntil(Set('"', '\\', '#'))

  private lazy val stringLiteral: P[Expr] =
    token(("\"".s ~ (bulkStringChars / escapedChar / interpolationSegment / plainStringChar).* ~ "\"".s).map {
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

  private lazy val bulkSingleQuotedChars: P[String] = takeUntil(Set('\'', '\\'))

  private lazy val singleQuotedStringLiteral: P[Expr] =
    token(("'".s ~ (bulkSingleQuotedChars / escapedSingleQuotedChar / plainSingleQuotedChar).* ~ "'".s).map {
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

  // ─── Percent Literals (%q, %w, %i, %s, %x) ────────────────────

  private def percentBody(open: String, close: String): P[String] = {
    val stopChars = Set(open.charAt(0), close.charAt(0), '\\')
    lazy val bulk: P[String] = takeUntil(stopChars)
    lazy val escaped: P[String] = ("\\".s ~ any).map { case _ ~ c => s"\\$c" }
    lazy val nested: P[String] =
      (open.s ~ refer(percentChunk).* ~ close.s).map {
        case openText ~ inner ~ closeText => openText + inner.mkString + closeText
      }
    lazy val single: P[String] =
      (!open.s ~ !close.s ~ any).map { case _ ~ _ ~ char => char }
    lazy val percentChunk: P[String] = bulk / escaped / nested / single
    (open.s ~ percentChunk.* ~ close.s).map {
      case _ ~ chars ~ _ => chars.mkString
    }
  }

  private def percentBodySimple(delim: String): P[String] = {
    val stopChars = Set(delim.charAt(0), '\\')
    lazy val bulk: P[String] = takeUntil(stopChars)
    lazy val escaped: P[String] =
      ("\\".s ~ any).map { case _ ~ c => s"\\$c" }
    lazy val plain: P[String] =
      (!delim.s ~ any).map(_._2)
    (delim.s ~ (bulk / escaped / plain).* ~ delim.s).map {
      case _ ~ chars ~ _ => chars.mkString
    }
  }

  private def percentStringLiteral(open: String, close: String): P[Expr] =
    token((("%q".s / "%Q".s / "%".s) ~ percentBody(open, close)).map {
      case _ ~ body => StringLiteral(body)
    })

  private def percentStringLiteralSimple(delim: String): P[Expr] =
    token((("%q".s / "%Q".s / "%".s) ~ percentBodySimple(delim)).map {
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

  private def percentWordArrayLiteralSimple(delim: String): P[Expr] =
    token((("%w".s / "%W".s) ~ percentBodySimple(delim)).map {
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

  private def percentSymbolArrayLiteralSimple(delim: String): P[Expr] =
    token((("%i".s / "%I".s) ~ percentBodySimple(delim)).map {
      case _ ~ body =>
        val symbols =
          body
            .split("\\s+")
            .toList
            .filter(_.nonEmpty)
            .map(value => SymbolLiteral(value, UnknownSpan))
        ArrayLiteral(symbols)
    })

  private def percentSymbolLiteral(open: String, close: String): P[Expr] =
    token(("%s".s ~ percentBody(open, close)).map {
      case _ ~ body => SymbolLiteral(body, UnknownSpan)
    })

  private def percentSymbolLiteralSimple(delim: String): P[Expr] =
    token(("%s".s ~ percentBodySimple(delim)).map {
      case _ ~ body => SymbolLiteral(body, UnknownSpan)
    })

  private lazy val percentQuotedStringLiteral: P[Expr] =
    percentStringLiteral("{", "}") /
      percentStringLiteral("(", ")") /
      percentStringLiteral("[", "]") /
      percentStringLiteral("<", ">") /
      percentStringLiteralSimple("%") /
      percentStringLiteralSimple("|") /
      percentStringLiteralSimple("\"") /
      percentStringLiteralSimple("'") /
      percentStringLiteralSimple("/")

  private lazy val stringLikeLiteral: P[Expr] =
    stringLiteral / singleQuotedStringLiteral / percentQuotedStringLiteral

  private def concatStringExpr(lhs: Expr, rhs: Expr): Expr =
    (lhs, rhs) match {
      case (StringLiteral(left, _), StringLiteral(right, _)) =>
        StringLiteral(left + right, UnknownSpan)
      case _ =>
        BinaryOp(lhs, "+", rhs)
    }

  private lazy val adjacentStringLiteral: P[Expr] =
    (stringLikeLiteral ~ stringLikeLiteral.+).map {
      case first ~ rest =>
        rest.foldLeft(first)(concatStringExpr)
    }

  private lazy val percentWordArray: P[Expr] =
    percentWordArrayLiteral("{", "}") /
      percentWordArrayLiteral("(", ")") /
      percentWordArrayLiteral("[", "]") /
      percentWordArrayLiteral("<", ">") /
      percentWordArrayLiteralSimple("%") /
      percentWordArrayLiteralSimple("|") /
      percentWordArrayLiteralSimple("\"") /
      percentWordArrayLiteralSimple("'") /
      percentWordArrayLiteralSimple("/")

  private lazy val percentSymbolArray: P[Expr] =
    percentSymbolArrayLiteral("{", "}") /
      percentSymbolArrayLiteral("(", ")") /
      percentSymbolArrayLiteral("[", "]") /
      percentSymbolArrayLiteral("<", ">") /
      percentSymbolArrayLiteralSimple("%") /
      percentSymbolArrayLiteralSimple("|") /
      percentSymbolArrayLiteralSimple("\"") /
      percentSymbolArrayLiteralSimple("'") /
      percentSymbolArrayLiteralSimple("/")

  private lazy val percentSymbolLiteralExpr: P[Expr] =
    percentSymbolLiteral("{", "}") /
      percentSymbolLiteral("(", ")") /
      percentSymbolLiteral("[", "]") /
      percentSymbolLiteral("<", ">") /
      percentSymbolLiteralSimple("%") /
      percentSymbolLiteralSimple("|") /
      percentSymbolLiteralSimple("\"") /
      percentSymbolLiteralSimple("'") /
      percentSymbolLiteralSimple("/")

  private def percentRegexLiteral(open: String, close: String): P[Expr] =
    token(("%r".s ~ percentBody(open, close) ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ body ~ _ => StringLiteral(body)
    })

  private def percentRegexLiteralSimple(delim: String): P[Expr] =
    token(("%r".s ~ percentBodySimple(delim) ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ body ~ _ => StringLiteral(body)
    })

  // ─── Regex Literals ────────────────────────────────────────────

  private lazy val percentRegex: P[Expr] =
    percentRegexLiteral("{", "}") /
      percentRegexLiteral("(", ")") /
      percentRegexLiteral("[", "]") /
      percentRegexLiteral("<", ">") /
      percentRegexLiteralSimple(":") /
      percentRegexLiteralSimple("%") /
      percentRegexLiteralSimple("|") /
      percentRegexLiteralSimple("\"") /
      percentRegexLiteralSimple("'") /
      percentRegexLiteralSimple("/")

  private def percentCommandLiteral(open: String, close: String): P[Expr] =
    token(("%x".s ~ percentBody(open, close)).map {
      case _ ~ body => StringLiteral(body)
    })

  private def percentCommandLiteralSimple(delim: String): P[Expr] =
    token(("%x".s ~ percentBodySimple(delim)).map {
      case _ ~ body => StringLiteral(body)
    })

  private lazy val percentCommand: P[Expr] =
    percentCommandLiteral("{", "}") /
      percentCommandLiteral("(", ")") /
      percentCommandLiteral("[", "]") /
      percentCommandLiteral("<", ">") /
      percentCommandLiteralSimple("%") /
      percentCommandLiteralSimple("|") /
      percentCommandLiteralSimple("\"") /
      percentCommandLiteralSimple("'") /
      percentCommandLiteralSimple("/")

  private lazy val escapedRegexChar: P[String] =
    ("\\".s ~ any).map { case _ ~ c => s"\\$c" }

  private lazy val plainRegexChar: P[String] =
    (!"/".s ~ !"#{".s ~ any).map {
      case _ ~ _ ~ c => c
    }

  private lazy val regexSpaceOnlyBody: P[String] =
    (range(' ' to ' ', '\t' to '\t').+.map(_.mkString) <~ "/".s.and)

  private lazy val regexSpaceOnlyLiteral: P[Expr] =
    token(("/".s ~ regexSpaceOnlyBody ~ "/".s ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ body ~ _ ~ _ => StringLiteral(body)
    })

  private lazy val regexLeadingSpaceEscapedBody: P[List[String]] =
    (range(' ' to ' ', '\t' to '\t').+.map(_.mkString) ~ (escapedRegexChar / interpolationSegment) ~ (escapedRegexChar / interpolationSegment / plainRegexChar).*).map {
      case spaces ~ first ~ rest => spaces :: first :: rest
    }

  private lazy val bulkRegexChars: P[String] = takeUntil(Set('/', '\\', '#'))

  private lazy val regexBodyChars: P[List[String]] =
    (bulkRegexChars / escapedRegexChar / interpolationSegment / plainRegexChar).*

  private lazy val argRegexLiteral: P[Expr] =
    token(("/".s ~ regexBodyChars ~ "/".s ~ range('a' to 'z', 'A' to 'Z').*).map {
      case _ ~ chars ~ _ ~ _ => StringLiteral(chars.mkString)
    })

  private lazy val regexLiteral: P[Expr] =
    percentRegex /
      regexSpaceOnlyLiteral /
      token(("/".s ~ (regexLeadingSpaceEscapedBody / ((!horizontalSpaceChar.and ~ regexBodyChars).map(_._2))) ~ "/".s ~ range('a' to 'z', 'A' to 'Z').*).map {
        case _ ~ chars ~ _ ~ _ => StringLiteral(chars.mkString)
      })

  // ─── Symbol Literals ───────────────────────────────────────────

  private lazy val symbolOperatorNameNoSpace: P[String] =
    "**".s /
    "===".s /
      "<=>".s /
      "=~".s /
      "!~".s /
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
      "+@".s /
      "-@".s /
      "+".s /
      "-".s /
      "*".s /
      "/".s /
      "%".s /
      "&".s /
      "|".s /
      "^".s /
      "~".s /
      "`".s /
      "!".s

  private lazy val unicodeSymbolNameNoSpace: P[String] =
    range('\u0080' to '\uffff').+.map(_.mkString)

  private lazy val symbolLiteral: P[Expr] =
    (symbolPrefix ~ token("**".s / "*".s / "&".s)).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) } /
      (symbolPrefix ~ token(symbolOperatorNameNoSpace / methodIdentifierRaw / reservedWord / unicodeSymbolNameNoSpace)).map {
        case _ ~ name => SymbolLiteral(name, UnknownSpan)
      } /
      (symbolPrefix ~ constName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) } /
      (symbolPrefix ~ classVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (symbolPrefix ~ instanceVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (symbolPrefix ~ globalVarName).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }
      /
      (symbolPrefix ~ token(("\"".s ~ (escapedChar / interpolationSegment / plainStringChar).* ~ "\"".s).map {
        case _ ~ chars ~ _ => chars.mkString
      })).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) } /
      (symbolPrefix ~ token(("'".s ~ (escapedSingleQuotedChar / plainSingleQuotedChar).* ~ "'".s).map {
        case _ ~ chars ~ _ => chars.mkString
      })).map { case _ ~ name => SymbolLiteral(name, UnknownSpan) }

  // ─── Atomic Expressions (bool, nil, self, variables) ──────────

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
    (localVar / instanceVar / classVar / globalVar).memo

  private lazy val constRef: P[Expr] =
    constPathSegments.map(path => ConstRef(path)).memo

  // ─── Parenthesized & Postfix-Modified Expressions ─────────────

  private lazy val exprPostfixModifierSuffix: P[Expr => Expr] =
    (kw("if") ~ refer(expr)).map {
      case _ ~ condition =>
        (target: Expr) => IfExpr(condition, List(ExprStmt(target)), Nil)
    } /
      (kw("unless") ~ refer(expr)).map {
        case _ ~ condition =>
          (target: Expr) => UnlessExpr(condition, List(ExprStmt(target)), Nil)
      } /
      (kw("rescue") ~ refer(expr)).map {
        case _ ~ fallback =>
          (target: Expr) =>
            BeginRescue(
              List(ExprStmt(target)),
              List(RescueClause(Nil, None, List(ExprStmt(fallback)))),
              Nil,
              Nil
            )
      } /
      (kw("while") ~ refer(expr)).map {
        case _ ~ condition =>
          (target: Expr) => WhileExpr(condition, List(ExprStmt(target)))
      } /
      (kw("until") ~ refer(expr)).map {
        case _ ~ condition =>
          (target: Expr) => UntilExpr(condition, List(ExprStmt(target)))
      }

  private lazy val exprWithPostfixModifier: P[Expr] =
    (refer(expr) ~ (inlineSpacing ~> exprPostfixModifierSuffix).*).map {
      case target ~ modifiers =>
        modifiers.foldLeft(target)((current, modifier) => modifier(current))
    }

  private lazy val parenExpr: P[Expr] =
    (sym("(") ~ (spacing ~> parenExprBody <~ spacing).? ~ sym(")")).map {
      case _ ~ Some(e) ~ _ => e
      case _ ~ None ~ _ => NilLiteral()
    }

  private lazy val parenExprBody: P[Expr] =
    ((statementSep.* ~ sepBy1(inlineSpacing ~> exprWithPostfixModifier <~ inlineSpacing, statementSep) ~ statementSep.*)).map {
      case _ ~ exprs ~ _ =>
        exprs.last
    }

  private lazy val spacedExpr: P[Expr] =
    spacing ~> refer(expr) <~ spacing

  private lazy val anonymousForwardingTail: P[Any] =
    sym(",").and /
      sym(")").and /
      sym("]").and /
      sym("}").and /
      sym(";").and /
      newlineChar.and

  private lazy val forwardingArgTail: P[Any] =
    sym(",").and /
      sym(")").and /
      sym("]").and /
      sym("}").and /
      sym(";").and /
      newlineChar.and

  private lazy val anonymousKeywordSplatExpr: P[Expr] =
    (sym("**") <~ anonymousForwardingTail).map(_ => LocalVar("**"))

  // ─── Array & Hash Literals ─────────────────────────────────────

  private lazy val arrayElementExpr: P[Expr] =
    anonymousKeywordSplatExpr /
      (sym("*") ~ refer(expr)).map(_._2) /
      ((assignableName <~ assignEq.and).and ~> callArgAssignExpr) /
      arrayHashElement /
      refer(expr)

  private lazy val arrayHashElement: P[Expr] =
    (arrayHashEntry ~ (sym(",") ~ spacing ~> arrayHashEntry).*).map {
      case first ~ rest => HashLiteral(first :: rest)
    }.memo

  private lazy val arrayHashEntry: P[(Expr, Expr)] =
    (quotedLabelHashEntry /
      labelHashEntry /
      shorthandLabelHashEntry /
      (hashRocketArgKeyExpr ~ sym("=>") ~ refer(expr)).map {
        case key ~ _ ~ value => key -> value
      }).memo

  private lazy val arrayLiteral: P[Expr] =
    (sym("[") ~> spacing ~> sepBy0(spacing ~> arrayElementExpr <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("]")).map {
      case values ~ _ => ArrayLiteral(values)
    }.memo

  private lazy val labelHashEntry: P[(Expr, Expr)] =
    ((symbolLabelNameNoSpace <~ labelColon) ~ (spacing ~> refer(expr))).map {
      case name ~ value => SymbolLiteral(name, UnknownSpan) -> value
    }.memo

  private lazy val quotedLabelHashEntry: P[(Expr, Expr)] =
    (((stringLiteral / symbolLiteral) <~ labelColon) ~ (spacing ~> refer(expr))).map {
      case key ~ value => key -> value
    }.memo

  private lazy val shorthandLabelHashTail: P[Any] =
    sym(",").and /
      sym("}").and /
      newlineChar.and

  private lazy val shorthandLabelHashEntry: P[(Expr, Expr)] =
    ((labelNameNoSpace <~ labelColon) <~ (inlineSpacing ~> shorthandLabelHashTail)).map { name =>
      SymbolLiteral(name, UnknownSpan) -> LocalVar(name, UnknownSpan)
    }.memo

  private lazy val hashEntry: P[(Expr, Expr)] =
    (anonymousKeywordSplatExpr.map(value => SymbolLiteral("**", UnknownSpan) -> value) /
      (sym("**") ~ refer(expr)).map { case _ ~ value => SymbolLiteral("**", UnknownSpan) -> value } /
      quotedLabelHashEntry /
      labelHashEntry /
      shorthandLabelHashEntry /
      (refer(expr) ~ sym("=>") ~ refer(expr)).map { case key ~ _ ~ value => key -> value }).memo

  private lazy val hashLiteral: P[Expr] =
    (sym("{") ~> spacing ~> sepBy0(spacing ~> hashEntry <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("}")).map {
      case entries ~ _ => HashLiteral(entries)
    }.memo

  // ─── Call Arguments ─────────────────────────────────────────────

  private lazy val callArgListExpr: P[Expr] =
    callArgExpr / argRegexLiteral

  private lazy val callArgs: P[List[Expr]] =
    (sym("(") ~>
      (
        spacing ~>
          sepBy0(spacing ~> callArgListExpr <~ spacing, sym(",")) ~
          ((sym(",") <~ spacing).?) <~
          spacing <~
          sym(")")
      ).cut).map {
      case args ~ _ => args
    }.memo

  private lazy val commandArgSep: P[Unit] =
    (inlineSpacing ~> sym(",") <~ spacing).void

  private lazy val postfixModifierHeadKeyword: P[Any] =
    kw("if") /
      kw("unless") /
      kw("while") /
      kw("until") /
      kw("rescue")

  private lazy val commandArgHeadGuard: P[Unit] =
    (!postfixModifierHeadKeyword).void

  private lazy val commandArgs: P[List[Expr]] =
    (callArgExpr ~ (commandArgSep ~> callArgExpr).* ~ (inlineSpacing ~> sym(",")).?).map {
      case first ~ rest ~ _ => first :: rest
    }.memo

  private lazy val blockPassArgExpr: P[Expr] =
    (
      sym("&") ~
      (identifier <~
        !sym("(").and <~
        !sym("[").and <~
        !sym(".").and <~
        !sym("&.").and <~
        !sym("::").and <~
        !(inlineSpacing ~> (sym("{") / kw("do"))).and)
    ).map { case _ ~ name => LocalVar(s"&$name") } /
      (sym("&") ~ symbolLiteral).map { case _ ~ symbol => symbol } /
      (sym("&") ~ refer(expr)).map(_._2)

  private lazy val doubleSplatArgExpr: P[Expr] =
    (sym("**") ~ refer(expr)).map(_._2)

  private lazy val anonymousSplatArgExpr: P[Expr] =
    (sym("*") <~ anonymousForwardingTail).map(_ => LocalVar("*"))

  private lazy val splatArgExpr: P[Expr] =
    (sym("*") ~ refer(expr)).map(_._2)

  private lazy val keywordArgExpr: P[Expr] =
    ((symbolLabelNameNoSpace <~ labelColon) ~ (spacing ~> refer(expr))).map {
      case name ~ value =>
        HashLiteral(List(SymbolLiteral(name, UnknownSpan) -> value))
    }

  private lazy val quotedKeywordArgExpr: P[Expr] =
    (((stringLiteral / symbolLiteral) <~ labelColon) ~ (spacing ~> refer(expr))).map {
      case key ~ value =>
        HashLiteral(List(key -> value))
    }

  private lazy val shorthandKeywordArgTail: P[Any] =
    sym(",").and /
      sym(")").and /
      sym("]").and /
      newlineChar.and

  private lazy val shorthandKeywordArgExpr: P[Expr] =
    ((labelNameNoSpace <~ labelColon) <~ (inlineSpacing ~> shorthandKeywordArgTail)).map { name =>
      HashLiteral(List(SymbolLiteral(name, UnknownSpan) -> LocalVar(name, UnknownSpan)))
    }

  private lazy val hashRocketKeyArrayLiteral: P[Expr] =
    (sym("[") ~>
      (
        spacing ~>
          sepBy0(spacing ~> refer(expr) <~ spacing, sym(",")) ~
          ((sym(",") <~ spacing).?) <~
          spacing <~
          sym("]")
      ).cut).map {
      case values ~ _ => ArrayLiteral(values)
    }.memo

  private lazy val relaxedHashRocketKeyArrayLiteral: P[Expr] =
    (sym("[") ~>
      (
        spacing ~>
          sepBy0(spacing ~> refer(expr) <~ spacing, sym(",")) ~
          ((sym(",") <~ spacing).?) <~
          spacing <~
          sym("]")
      )).map {
      case values ~ _ => ArrayLiteral(values)
    }.memo

  private lazy val hashRocketUnaryNumericKeyExpr: P[Expr] =
    (sym("-") ~ (integerLiteral / floatLiteral / parenExpr)).map {
      case _ ~ value => UnaryOp("-", value)
    } /
      (sym("+") ~ (integerLiteral / floatLiteral / parenExpr)).map {
        case _ ~ value => UnaryOp("+", value)
      }

  private lazy val hashRocketKeyAtom: P[Expr] =
    (symbolLiteral /
      adjacentStringLiteral /
      stringLiteral /
      singleQuotedStringLiteral /
      selfExpr /
      boolLiteral /
      nilLiteral /
      hashRocketUnaryNumericKeyExpr /
      hashRocketKeyArrayLiteral /
      variable /
      constRef /
      integerLiteral /
      floatLiteral /
      parenExpr).memo

  private lazy val hashRocketNoArgReceiverKeyExpr: P[Expr] =
    ((hashRocketKeyAtom ~ (memberAccessSeparator ~ receiverMethodNameNoSpace).+).map {
      case base ~ suffixes =>
        suffixes.foldLeft(base) { case (current, _ ~ name) => Call(Some(current), name, Nil) }
    } <~ inlineSpacing).memo

  private lazy val hashRocketArgKeyExpr: P[Expr] =
    (hashRocketNoArgReceiverKeyExpr /
      hashRocketKeyAtom).memo

  private lazy val hashRocketArgExpr: P[Expr] =
    (((hashRocketArgKeyExpr <~ sym("=>").and).and ~> hashRocketArgKeyExpr) ~ sym("=>") ~ refer(expr)).map {
      case key ~ _ ~ value => HashLiteral(List(key -> value))
    }.memo

  private lazy val arrayKeyHashRocketArgExpr: P[Expr] =
    (hashRocketKeyArrayLiteral ~ sym("=>") ~ refer(expr)).map {
      case key ~ _ ~ value => HashLiteral(List(key -> value))
    }.memo

  private lazy val relaxedArrayKeyHashRocketArgExpr: P[Expr] =
    (relaxedHashRocketKeyArrayLiteral ~ sym("=>") ~ refer(expr)).map {
      case key ~ _ ~ value => HashLiteral(List(key -> value))
    }.memo

  private lazy val forwardingArgExpr: P[Expr] =
    (sym("...") <~ forwardingArgTail).map(_ => LocalVar("..."))

  private lazy val callArgAssignTail: P[Any] =
    sym(",").and /
      sym(")").and /
      sym("]").and /
      sym("}").and /
      sym(";").and /
      newlineChar.and

  private lazy val callArgAssignExpr: P[Expr] =
    (((assignableName <~ assignEq.and).and ~> assignableName) ~
      (assignEq ~> spacing ~> (refer(chainedAssignRhsExpr) <~ (inlineSpacing ~> callArgAssignTail).and)).cut).map {
      case name ~ value => AssignExpr(name, value)
    }.memo

  private lazy val callArgExpr: P[Expr] =
    (
        (sym("...").and ~> forwardingArgExpr) /
        (sym("&").and ~> blockPassArgExpr) /
        (sym("**").and ~> (anonymousKeywordSplatExpr / doubleSplatArgExpr)) /
        (sym("*").and ~> (anonymousSplatArgExpr / splatArgExpr)) /
        (((stringLiteral / symbolLiteral) <~ labelColon).and ~> quotedKeywordArgExpr) /
        ((symbolLabelNameNoSpace <~ labelColon).and ~> (keywordArgExpr / shorthandKeywordArgExpr)) /
        (sym("[").and ~> relaxedArrayKeyHashRocketArgExpr) /
        ((!(sym("[").and / sym("{").and) ~> ((hashRocketArgKeyExpr <~ sym("=>").and).and)) ~> hashRocketArgExpr) /
        callArgAssignExpr /
        refer(expr)
    ).memo

  private lazy val bracketArgExpr: P[Expr] =
    (
      (sym("&").and ~> blockPassArgExpr) /
        (sym("**").and ~> (anonymousKeywordSplatExpr / doubleSplatArgExpr)) /
        (sym("*").and ~> (anonymousSplatArgExpr / splatArgExpr)) /
        (((stringLiteral / symbolLiteral) <~ labelColon).and ~> quotedKeywordArgExpr) /
        ((symbolLabelNameNoSpace <~ labelColon).and ~> (keywordArgExpr / shorthandKeywordArgExpr)) /
        (sym("[").and ~> arrayKeyHashRocketArgExpr) /
        ((!(sym("[").and / sym("{").and) ~> ((hashRocketArgKeyExpr <~ sym("=>").and).and)) ~> hashRocketArgExpr) /
        callArgAssignExpr /
        refer(expr)
    ).memo

  // NOTE: keep this tight (`foo(` only). If whitespace is allowed here, command-style
  // forms like `assert_equal (+x), y` are misread as `assert_equal(+x)`.
  // ─── Function & Command Calls ──────────────────────────────────

  private lazy val functionCall: P[Expr] =
    ((methodIdentifierNoSpace ~ callArgs).map { case name ~ args => Call(None, name, args) }).memo

  private lazy val receiverForCommand: P[Expr] =
    constRef /
      selfExpr /
      variable /
      adjacentStringLiteral /
      stringLiteral /
      singleQuotedStringLiteral /
      parenExpr

  private lazy val receiverCommandHead: P[Expr ~ String] =
    (receiverForCommand <~ sym(".")) ~ receiverMethodNameNoSpace

  private lazy val commandCall: P[Expr] =
    (((methodIdentifierNoSpace <~ spacing1) ~ (commandArgHeadGuard ~> commandArgs))).map {
      case name ~ args => Call(None, name, args)
    }

  private lazy val noSpaceSymbolCommandCall: P[Expr] =
    (methodIdentifierNoSpace ~ symbolLiteral).map {
      case name ~ arg => Call(None, name, List(arg))
    }.memo

  private lazy val noSpaceLambdaCommandCall: P[Expr] =
    (methodIdentifierNoSpace ~ lambdaLiteral).map {
      case name ~ lambda => Call(None, name, List(lambda))
    }

  private lazy val receiverCommandCall: P[Expr] =
    (((receiverCommandHead <~ spacing1) ~ (commandArgHeadGuard ~> commandArgs))).map {
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
    (((chainedCallExpr <~ spacing1) ~ (commandArgHeadGuard ~> commandArgs))).map {
      case call ~ args => appendCommandArgs(call, args)
    }

  // ─── Primary Expressions ───────────────────────────────────────

  private lazy val primaryNoCall: P[Expr] =
    (
      lambdaLiteral /
        singletonClassExpr /
        refer(beginStmt).map(_.asInstanceOf[Expr]) /
        defExpr /
        refer(returnStmt).map(_.asInstanceOf[Expr]) /
        refer(ifStmt).map(_.asInstanceOf[Expr]) /
        refer(unlessStmt).map(_.asInstanceOf[Expr]) /
        refer(caseStmt).map(_.asInstanceOf[Expr]) /
        refer(classStmt).map(_ => NilLiteral()) /
        refer(moduleStmt).map(_ => NilLiteral()) /
        // NOTE: whileStmt/untilStmt NOT here — their `do` conflicts with blockAttachSuffix.cut
        selfExpr /
        boolLiteral /
        nilLiteral /
        constRef /
        variable /
        integerLiteral /
        floatLiteral /
        token(("?".s ~ !horizontalSpaceChar.and ~ !newlineChar.and ~ escapedCharLiteralChar).map {
          case _ ~ _ ~ _ ~ char => StringLiteral(char)
        }) /
        adjacentStringLiteral /
        stringLiteral /
        singleQuotedStringLiteral /
        backtickLiteral /
        percentCommand /
        percentQuotedStringLiteral /
        percentSymbolLiteralExpr /
        percentWordArray /
        percentSymbolArray /
        regexLiteral /
        symbolLiteral /
        arrayLiteral /
        hashLiteral /
        parenExpr
    ).memo

  private lazy val commandExpr: P[Expr] =
    (receiverCommandCall / noSpaceLambdaCommandCall / noSpaceSymbolCommandCall / commandCall).memo

  private lazy val bareNoArgPunctuatedCall: P[Expr] =
    punctuatedMethodIdentifier.map(name => Call(None, name, Nil))

  private lazy val bareNoArgKeywordCall: P[Expr] =
    token(bareKeywordMethodNameNoSpace).map(name => Call(None, name, Nil))

  // Keep block parameter defaults conservative to avoid consuming the closing `|`
  // as a bitwise-or operator (e.g. `|b, c=42|`).
  // ─── Block Parameters & Blocks ─────────────────────────────────

  private lazy val blockParamDefaultExpr: P[Expr] =
    integerLiteral /
      floatLiteral /
      stringLiteral /
      singleQuotedStringLiteral /
      symbolLiteral /
      arrayLiteral /
      hashLiteral /
      boolLiteral /
      nilLiteral /
      variable /
      constRef

  private lazy val positionalParam: P[String] =
    (identifier ~ (sym("=") ~ refer(expr)).?).map(_._1)

  private lazy val blockPositionalParam: P[String] =
    (identifier ~ (sym("=") ~ blockParamDefaultExpr).?).map(_._1)

  private lazy val keywordParam: P[String] =
    ((labelNameNoSpace <~ labelColon) ~ refer(expr).?).map {
      case name ~ _ => s"$name:"
    }

  private lazy val blockKeywordParam: P[String] =
    ((labelNameNoSpace <~ labelColon) ~ blockParamDefaultExpr.?).map {
      case name ~ _ => s"$name:"
    }

  private lazy val formalParam: P[String] =
    sym("...").map(_ => "...") /
    (sym("(") ~> sepBy0(refer(formalParam), sym(",")) <~ sym(")")).map { parts =>
      s"(${parts.mkString(",")})"
    } /
      (sym("**") ~ identifier).map { case _ ~ name => s"**$name" } /
      (sym("**") ~ kw("nil")).map(_ => "**nil") /
      sym("**").map(_ => "**") /
      (sym("*") ~ identifier).map { case _ ~ name => s"*$name" } /
      (sym("*") ~ kw("nil")).map(_ => "*nil") /
      sym("*").map(_ => "*") /
      (sym("&") ~ identifier).map { case _ ~ name => s"&$name" } /
      (sym("&") ~ kw("nil")).map(_ => "&nil") /
      sym("&").map(_ => "&") /
      keywordParam /
      positionalParam

  private lazy val blockFormalParam: P[String] =
    sym("...").map(_ => "...") /
      (sym("(") ~> sepBy0(refer(blockFormalParam), sym(",")) <~ sym(")")).map { parts =>
        s"(${parts.mkString(",")})"
      } /
      (sym("**") ~ identifier).map { case _ ~ name => s"**$name" } /
      (sym("**") ~ kw("nil")).map(_ => "**nil") /
      sym("**").map(_ => "**") /
      (sym("*") ~ identifier).map { case _ ~ name => s"*$name" } /
      (sym("*") ~ kw("nil")).map(_ => "*nil") /
      sym("*").map(_ => "*") /
      (sym("&") ~ identifier).map { case _ ~ name => s"&$name" } /
      (sym("&") ~ kw("nil")).map(_ => "&nil") /
      sym("&").map(_ => "&") /
      blockKeywordParam /
      blockPositionalParam

  private lazy val blockParamSep: P[Unit] =
    (spacing ~> sym(",") <~ spacing).void

  private lazy val blockParamList: P[List[String]] =
    sepBy0(spacing ~> (destructuredBlockParam / blockFormalParam) <~ spacing, blockParamSep)

  private lazy val blockLocalList: P[List[String]] =
    sepBy0(spacing ~> identifier <~ spacing, blockParamSep)

  private lazy val destructuredBlockParam: P[String] =
    (sym("(") ~> sepBy0(blockFormalParam, sym(",")) <~ sym(")")).map { parts =>
      s"(${parts.mkString(",")})"
    }

  private lazy val blockParams: P[List[String]] =
    (sym("|") ~>
      ((spacing ~> blockParamList ~
        ((sym(";") ~> blockLocalList)).? <~
        (sym(",") <~ spacing).? <~
        spacing).map {
        case params ~ blockLocals =>
          params ++ blockLocals.getOrElse(Nil).map(name => s";$name")
      }) <~
      sym("|"))

  private lazy val doBlock: P[Block] =
    (
      kw("do") ~
      statementSep.* ~
      blockParams.? ~
      statementSep.* ~
      blockStatementsUntil(kw("rescue") / kw("else") / kw("ensure") / kw("end")) ~
      statementSep.* ~
      refer(rescueClause).* ~
      statementSep.* ~
      (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("ensure") / kw("end"))).? ~
      statementSep.* ~
      (kw("ensure") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
      statementSep.* ~
      kw("end")
    ).map {
      case _ ~ _ ~ maybeParams ~ _ ~ body ~ _ ~ rescues ~ _ ~ elseOpt ~ _ ~ ensureOpt ~ _ ~ _ =>
        val elseBody = elseOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val ensureBody = ensureOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val statements =
          if(rescues.nonEmpty || elseBody.nonEmpty || ensureBody.nonEmpty) {
            List(BeginRescue(body, rescues, elseBody, ensureBody))
          } else {
            body
          }
        Block(maybeParams.getOrElse(Nil), statements)
    }

  private lazy val braceBlock: P[Block] =
    (sym("{") ~ statementSep.* ~ blockParams.? ~ statementSep.* ~ blockStatementsUntil(sym("}")) ~ statementSep.* ~ spacing ~ sym("}")).map {
      case _ ~ _ ~ maybeParams ~ _ ~ body ~ _ ~ _ ~ _ => Block(maybeParams.getOrElse(Nil), body)
    }

  private lazy val blockLiteral: P[Block] =
    doBlock / braceBlock

  private lazy val chainableKeywordExprStmt: P[Statement] =
    (((refer(beginStmt).map(_.asInstanceOf[Expr]) /
      refer(ifStmt).map(_.asInstanceOf[Expr]) /
      refer(unlessStmt).map(_.asInstanceOf[Expr]) /
      refer(caseStmt).map(_.asInstanceOf[Expr])) ~ callSuffix.+).map {
      case base ~ suffixes =>
        exprAsStatement(suffixes.foldLeft(base) { (current, suffix) => suffix(current) })
    })

  // ─── Lambda Literals & Statement Separators ────────────────────

  private lazy val lambdaLiteral: P[Expr] =
    (sym("->") ~ (params / bareParams).? ~ blockLiteral).map {
      case _ ~ maybeParams ~ block =>
        val lambdaParams = maybeParams.getOrElse(block.params)
        LambdaLiteral(lambdaParams, block.body)
    }

  private lazy val lineBreak: P[Unit] =
    (inlineSpacing ~> "\n".s ~ inlineSpacing).void

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

  // ─── Call Suffixes & Method Chaining ───────────────────────────

  private lazy val operatorMethodName: P[String] =
    "**".s /
    "<<".s /
      ">>".s /
      "[]=".s /
      "[]".s /
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

  private lazy val memberAccessSeparator: P[String] =
    ((sym(".") / sym("&.") / sym("::")) <~ lineBreak.?) /
      (((lineBreak ~> sym(".")) / (lineBreak ~> sym("&.")) / (lineBreak ~> sym("::"))) <~ lineBreak.?)

  private lazy val dotLikeSeparator: P[String] =
    ((sym(".") / sym("&.")) <~ lineBreak.?) /
      (((lineBreak ~> sym(".")) / (lineBreak ~> sym("&."))) <~ lineBreak.?)

  private lazy val methodSuffix: P[Expr => Expr] =
    (memberAccessSeparator ~ suffixMethodName ~ callArgs.?).map {
      case _ ~ name ~ argsOpt =>
        val args = argsOpt.getOrElse(Nil)
        (receiver: Expr) => Call(Some(receiver), name, args)
    }

  private lazy val methodCommandSuffix: P[Expr => Expr] =
    ((memberAccessSeparator ~ suffixMethodName <~ spacing1) ~ (commandArgHeadGuard ~> commandArgs)).map {
      case _ ~ name ~ args =>
        (receiver: Expr) => Call(Some(receiver), name, args)
    }

  private lazy val dotCallSuffix: P[Expr => Expr] =
    ((dotLikeSeparator ~ callArgs).map {
      case _ ~ args =>
        (receiver: Expr) => Call(Some(receiver), "call", args)
    })

  private lazy val bracketArgListExpr: P[Expr] =
    bracketArgExpr / argRegexLiteral

  private lazy val bracketCallArgs: P[List[Expr]] =
    (sym("[") ~>
      (
        spacing ~>
          sepBy0(spacing ~> bracketArgListExpr <~ spacing, sym(",")) ~
          ((sym(",") <~ spacing).?) <~
          spacing <~
          sym("]")
      ).cut).map {
      case args ~ _ => args
    }

  private lazy val indexSuffix: P[Expr => Expr] =
    bracketCallArgs.map { args =>
      (receiver: Expr) => Call(Some(receiver), "[]", args)
    }

  private lazy val callSuffix: P[Expr => Expr] =
    methodCommandSuffix / dotCallSuffix / methodSuffix / indexSuffix / blockAttachSuffix

  private lazy val blockAttachSuffix: P[Expr => Expr] =
    ((inlineSpacing ~> (kw("do").and / sym("{").and)) ~> refer(blockLiteral).cut).map { block =>
      (receiver: Expr) => CallWithBlock(receiver, block)
    }

  private lazy val braceBlockAttachSuffix: P[Expr => Expr] =
    ((inlineSpacing ~> sym("{").and) ~> braceBlock.cut).map { block =>
      (receiver: Expr) => CallWithBlock(receiver, block)
    }

  private lazy val nonIndexCallSuffix: P[Expr => Expr] =
    methodCommandSuffix / dotCallSuffix / methodSuffix / blockAttachSuffix

  private lazy val postfixNoIndexExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / bareNoArgKeywordCall / primaryNoCall) ~ nonIndexCallSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }.memo

  private lazy val indexTarget: P[(Expr, List[Expr])] =
    (postfixNoIndexExpr ~ bracketCallArgs ~ bracketCallArgs.*).map {
      case receiver ~ firstArgs ~ restArgs =>
        val allArgs = firstArgs :: restArgs
        val targetReceiver =
          allArgs.dropRight(1).foldLeft(receiver) { case (current, args) =>
            Call(Some(current), "[]", args)
          }
        targetReceiver -> allArgs.last
    }

  // ─── Postfix Expressions ──────────────────────────────────────

  private lazy val postfixExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / bareNoArgKeywordCall / primaryNoCall) ~ callSuffix.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }.memo

  private lazy val chainedCallExpr: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / bareNoArgKeywordCall / primaryNoCall) ~ callSuffix.+).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }.memo

  private def infix(op: String): P[(Expr, Expr) => Expr] =
    ((string(op) <~ spacing)).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private def infixKeyword(op: String): P[(Expr, Expr) => Expr] =
    kw(op).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private def infixLogicalKeyword(op: String): P[(Expr, Expr) => Expr] =
    ((op.s <~ !identCont) <~ spacing).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private def infixLogicalSymbol(op: String): P[(Expr, Expr) => Expr] =
    ((string(op) <~ !"=".s) <~ spacing).map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, op, rhs))

  private lazy val binaryAssignRhsExpr: P[Expr] =
    (((assignableName <~ assignEq.and).and ~> assignableName) ~ assignEq ~ spacing ~ refer(chainedAssignRhsExpr)).map {
      case name ~ _ ~ _ ~ value => AssignExpr(name, value)
    }

  private lazy val shiftAssignExpr: P[Expr] =
    (addSubExpr ~ (("<<".s / ">>".s) <~ spacing) ~ binaryAssignRhsExpr).map {
      case lhs ~ op ~ rhs => BinaryOp(lhs, op, rhs)
    }

  private lazy val shiftAssignExprNoBlock: P[Expr] =
    (addSubExprNoBlock ~ (("<<".s / ">>".s) <~ spacing) ~ binaryAssignRhsExpr).map {
      case lhs ~ op ~ rhs => BinaryOp(lhs, op, rhs)
    }

  // ─── Operator Precedence Hierarchy ─────────────────────────────

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
      (sym("*") ~ refer(unaryExpr)).map {
        case _ ~ target => UnaryOp("*", target)
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
    shiftAssignExpr /
      chainl(addSubExpr)(infix("<<") / infix(">>"))

  private lazy val bitAndExpr: P[Expr] =
    chainl(shiftExpr)(infix("&"))

  private lazy val bitOrExpr: P[Expr] =
    chainl(bitAndExpr)(infix("|") / infix("^"))

  private lazy val relationalExpr: P[Expr] =
    chainl(bitOrExpr)(infix("<=") / infix(">=") / infix("<") / infix(">"))

  private lazy val equalityExpr: P[Expr] =
    chainl(relationalExpr)(infix("===") / infix("<=>") / infix("==") / infix("!=") / infix("=~") / infix("!~"))

  private lazy val rangeOp: P[String] =
    sym("...") / sym("..")

  private lazy val rangeExpr: P[Expr] =
    (equalityExpr ~ (rangeOp ~ equalityExpr.?).?).map {
      case start ~ Some(op ~ endOpt) =>
        RangeExpr(start, endOpt.getOrElse(NilLiteral()), exclusive = op == "...")
      case start ~ None =>
        start
    } /
      (rangeOp ~ equalityExpr).map {
        case op ~ end =>
          RangeExpr(NilLiteral(), end, exclusive = op == "...")
      }

  private lazy val andExpr: P[Expr] =
    chainl(rangeExpr)(infixLogicalSymbol("&&"))

  private lazy val orExpr: P[Expr] =
    chainl(andExpr)(infixLogicalSymbol("||"))

  private lazy val inMatchExpr: P[Expr] =
    (orExpr ~ (kw("in") ~ inClausePatterns).?).map {
      case value ~ Some(_ ~ patterns) => BinaryOp(value, "in", collapsePatternList(patterns))
      case value ~ None => value
    }

  private lazy val conditionalExpr: P[Expr] =
    (inMatchExpr ~ ((spacing ~> sym("?")) ~ (refer(expr) <~ (spacing ~> ":".s).and) ~ ((spacing ~> ":".s <~ !":".s) ~> spacing ~> refer(expr))).?).map {
      case condition ~ Some(_ ~ thenExpr ~ elseExpr) =>
        IfExpr(
          condition,
          List(ExprStmt(thenExpr)),
          List(ExprStmt(elseExpr))
        )
      case condition ~ None =>
        condition
    }

  // ===== NoBlock expression hierarchy for loop/for conditions =====
  // Prevents blockAttachSuffix from stealing "do" keyword in while/until/for conditions.
  private lazy val callSuffixNoBlock: P[Expr => Expr] =
    methodCommandSuffix / dotCallSuffix / methodSuffix / indexSuffix / braceBlockAttachSuffix

  private lazy val postfixExprNoBlock: P[Expr] =
    ((functionCall / commandExpr / bareNoArgPunctuatedCall / bareNoArgKeywordCall / primaryNoCall) ~ callSuffixNoBlock.*).map {
      case base ~ suffixes => suffixes.foldLeft(base)((current, suffix) => suffix(current))
    }.memo

  private lazy val powerExprNoBlock: P[Expr] =
    (postfixExprNoBlock ~ (sym("**") ~> refer(powerExprNoBlock)).?).map {
      case base ~ Some(exp) => BinaryOp(base, "**", exp)
      case base ~ None => base
    }

  private lazy val unaryExprNoBlock: P[Expr] =
    (kw("not") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("not", target)
    } /
    (sym("!") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("!", target)
    } /
    (sym("~") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("~", target)
    } /
    (sym("*") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("*", target)
    } /
    (sym("-") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("-", target)
    } /
    (sym("+") ~ refer(unaryExprNoBlock)).map {
      case _ ~ target => UnaryOp("+", target)
    } /
    (powerExprNoBlock <~ horizontalSpacing)

  private lazy val mulDivExprNoBlock: P[Expr] =
    chainl(unaryExprNoBlock)(infix("*") / infix("/") / infix("%"))

  private lazy val addSubExprNoBlock: P[Expr] =
    chainl(mulDivExprNoBlock)(infix("+") / infix("-"))

  private lazy val shiftExprNoBlock: P[Expr] =
    shiftAssignExprNoBlock /
      chainl(addSubExprNoBlock)(infix("<<") / infix(">>"))

  private lazy val bitAndExprNoBlock: P[Expr] =
    chainl(shiftExprNoBlock)(infix("&"))

  private lazy val bitOrExprNoBlock: P[Expr] =
    chainl(bitAndExprNoBlock)(infix("|") / infix("^"))

  private lazy val relationalExprNoBlock: P[Expr] =
    chainl(bitOrExprNoBlock)(infix("<=") / infix(">=") / infix("<") / infix(">"))

  private lazy val equalityExprNoBlock: P[Expr] =
    chainl(relationalExprNoBlock)(infix("===") / infix("<=>") / infix("==") / infix("!=") / infix("=~") / infix("!~"))

  private lazy val rangeExprNoBlock: P[Expr] =
    (equalityExprNoBlock ~ (rangeOp ~ equalityExprNoBlock.?).?).map {
      case start ~ Some(op ~ endOpt) =>
        RangeExpr(start, endOpt.getOrElse(NilLiteral()), exclusive = op == "...")
      case start ~ None =>
        start
    } /
    (rangeOp ~ equalityExprNoBlock).map {
      case op ~ end =>
        RangeExpr(NilLiteral(), end, exclusive = op == "...")
    }

  private lazy val andExprNoBlock: P[Expr] =
    chainl(rangeExprNoBlock)(infixLogicalSymbol("&&"))

  private lazy val orExprNoBlock: P[Expr] =
    chainl(andExprNoBlock)(infixLogicalSymbol("||"))

  private lazy val inMatchExprNoBlock: P[Expr] =
    (orExprNoBlock ~ (kw("in") ~ inClausePatterns).?).map {
      case value ~ Some(_ ~ patterns) => BinaryOp(value, "in", collapsePatternList(patterns))
      case value ~ None => value
    }

  // conditionExpr: used as the condition of while/until/for — excludes do-blocks
  // ─── Condition Expressions ─────────────────────────────────────

  private lazy val conditionAssignExpr: P[Expr] =
    (((assignableName <~ assignEq.and).and ~> assignableName) ~ assignEq ~ spacing ~ refer(conditionExpr)).map {
      case name ~ _ ~ _ ~ value => AssignExpr(name, value)
    }

  private lazy val conditionBaseExpr: P[Expr] =
    (conditionAssignExpr /
      (inMatchExprNoBlock ~ ((spacing ~> sym("?")) ~ (refer(conditionExpr) <~ (spacing ~> ":".s).and) ~ ((spacing ~> ":".s <~ !":".s) ~> spacing ~> refer(conditionExpr))).?).map {
      case condition ~ Some(_ ~ thenExpr ~ elseExpr) =>
        IfExpr(condition, List(ExprStmt(thenExpr)), List(ExprStmt(elseExpr)))
      case condition ~ None =>
        condition
    })

  private lazy val conditionExpr: P[Expr] =
    chainl(conditionBaseExpr)(infixLogicalKeyword("and") / infixLogicalKeyword("or")).memo

  // ─── Assignment Expressions ────────────────────────────────────

  private lazy val chainedAssignRhsExpr: P[Expr] =
    (((chainedReceiverAssignableHead <~ assignEq.and).and ~> chainedReceiverAssignableHead) ~ assignEq ~ spacing ~ refer(chainedAssignRhsExpr)).map {
      case receiver ~ name ~ _ ~ _ ~ value =>
        Call(Some(receiver), s"${name}=", List(value))
    } /
    (((receiverAssignableHead <~ assignEq.and).and ~> receiverAssignableHead) ~ assignEq ~ spacing ~ refer(chainedAssignRhsExpr)).map {
      case receiver ~ name ~ _ ~ _ ~ value =>
        Call(Some(receiver), s"${name}=", List(value))
    } /
      (((indexTarget <~ assignEq.and).and ~> indexTarget) ~ assignEq ~ spacing ~ refer(chainedAssignRhsExpr)).map {
        case (receiver, args) ~ _ ~ _ ~ value =>
          Call(Some(receiver), "[]=", args :+ value)
      } /
    (((assignableName <~ assignEq.and).and ~> assignableName) ~ assignEq ~ spacing ~ refer(chainedAssignRhsExpr)).map {
      case name ~ _ ~ _ ~ value => AssignExpr(name, value)
    } /
      conditionalExpr

  private lazy val assignValueExpr: P[Expr] =
    ((splatArgExpr / chainedAssignRhsExpr) ~ ((sym(",") ~ spacing ~> (splatArgExpr / chainedAssignRhsExpr)).*) ~ ((sym(",") <~ spacing).?)).map {
      case first ~ rest ~ _ =>
        val values = first :: rest
        values match {
          case value :: Nil => value
          case many => ArrayLiteral(many)
        }
    }

  private lazy val assignExpr: P[Expr] =
    (((assignableName <~ assignEq.and).and ~> assignableName) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case name ~ _ ~ _ ~ value => AssignExpr(name, value)
    }

  private lazy val multiAssignExpr: P[Expr] =
    (((multiAssignNames <~ assignEq.and).and ~> multiAssignNames) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case names ~ _ ~ _ ~ value => MultiAssignExpr(names, value)
    }

  private lazy val receiverAssignableHead: P[Expr ~ String] =
    (receiverForCommand <~ receiverAssignableSeparator) ~ receiverAssignableMethodName

  private lazy val chainedReceiverSegment: P[String] =
    (receiverAssignableSeparator ~ receiverAssignableMethodName).map(_._2)

  private lazy val receiverAssignableSeparator: P[String] =
    sym(".") / sym("&.") / sym("::")

  private lazy val receiverAssignableMethodName: P[String] =
    token(identifierNoSpace / constNameNoSpace / receiverKeywordMethodNameNoSpace)

  private lazy val chainedReceiverAssignableHead: P[Expr ~ String] =
    (receiverForCommand ~ chainedReceiverSegment ~ chainedReceiverSegment.+).map {
      case base ~ first ~ rest =>
        val segments = first :: rest
        val receiverExpr =
          segments.dropRight(1).foldLeft(base) { (receiver, method) =>
            Call(Some(receiver), method, Nil)
          }
        new ~(receiverExpr, segments.last)
    }

  private lazy val chainedReceiverAssignExpr: P[Expr] =
    (((chainedReceiverAssignableHead <~ assignEq.and).and ~> chainedReceiverAssignableHead) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case receiver ~ name ~ _ ~ _ ~ value =>
        Call(Some(receiver), s"${name}=", List(value))
    }

  private lazy val receiverAssignExpr: P[Expr] =
    (((receiverAssignableHead <~ assignEq.and).and ~> receiverAssignableHead) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case receiver ~ name ~ _ ~ _ ~ value =>
        Call(Some(receiver), s"${name}=", List(value))
    }

  private lazy val receiverLogicalAssignExpr: P[Expr] =
    (((chainedReceiverAssignableHead <~ (sym("||=") / sym("&&=")).and).and ~> chainedReceiverAssignableHead) ~ (sym("||=") / sym("&&=")) ~ spacing ~ refer(expr)).map {
      case receiver ~ name ~ op ~ _ ~ value =>
        val lhs = Call(Some(receiver), name, Nil)
        val underlying = if(op == "||=") "||" else "&&"
        Call(Some(receiver), s"${name}=", List(BinaryOp(lhs, underlying, value)))
    } /
    (((receiverAssignableHead <~ (sym("||=") / sym("&&=")).and).and ~> receiverAssignableHead) ~ (sym("||=") / sym("&&=")) ~ spacing ~ refer(expr)).map {
      case receiver ~ name ~ op ~ _ ~ value =>
        val lhs = Call(Some(receiver), name, Nil)
        val underlying = if(op == "||=") "||" else "&&"
        BinaryOp(lhs, underlying, value)
    }

  private lazy val indexAssignExpr: P[Expr] =
    (((indexTarget <~ assignEq.and).and ~> indexTarget) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case (receiver, args) ~ _ ~ _ ~ value =>
        Call(Some(receiver), "[]=", args :+ value)
    }

  private lazy val indexLogicalAssignExpr: P[Expr] =
    (((indexTarget <~ (sym("||=") / sym("&&=")).and).and ~> indexTarget) ~ (sym("||=") / sym("&&=")) ~ spacing ~ refer(expr)).map {
      case (receiver, args) ~ op ~ _ ~ value =>
        val lhs = Call(Some(receiver), "[]", args)
        val underlying = if(op == "||=") "||" else "&&"
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), "[]=", args :+ rhs)
    }

  private lazy val indexCompoundAssignExpr: P[Expr] =
    (((indexTarget <~ compoundAssignOperator.and).and ~> indexTarget) ~ compoundAssignOperator ~ spacing ~ refer(expr)).map {
      case (receiver, args) ~ op ~ _ ~ value =>
        val underlying = op.dropRight(1)
        val lhs = Call(Some(receiver), "[]", args)
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), "[]=", args :+ rhs)
    }

  private lazy val receiverCompoundAssignExpr: P[Expr] =
    (((chainedReceiverAssignableHead <~ compoundAssignOperator.and).and ~> chainedReceiverAssignableHead) ~ compoundAssignOperator ~ spacing ~ refer(expr)).map {
      case receiver ~ name ~ op ~ _ ~ value =>
        val underlying = op.dropRight(1)
        val lhs = Call(Some(receiver), name, Nil)
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), s"${name}=", List(rhs))
    } /
    (((receiverAssignableHead <~ compoundAssignOperator.and).and ~> receiverAssignableHead) ~ compoundAssignOperator ~ spacing ~ refer(expr)).map {
      case receiver ~ name ~ op ~ _ ~ value =>
        val underlying = op.dropRight(1)
        val lhs = Call(Some(receiver), name, Nil)
        val rhs = BinaryOp(lhs, underlying, value)
        Call(Some(receiver), s"${name}=", List(rhs))
    }

  private lazy val logicalAssignExpr: P[Expr] =
    (((assignableName <~ (sym("||=") / sym("&&=")).and).and ~> assignableName) ~ (sym("||=") / sym("&&=")) ~ spacing ~ refer(expr)).map {
      case name ~ op ~ _ ~ value =>
        val underlying = if(op == "||=") "||" else "&&"
        AssignExpr(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val compoundAssignExpr: P[Expr] =
    (((assignableName <~ compoundAssignOperator.and).and ~> assignableName) ~ compoundAssignOperator ~ spacing ~ refer(expr)).map {
      case name ~ op ~ _ ~ value =>
        val underlying = op.dropRight(1)
        AssignExpr(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  // Receiver-based assignments require "expr.name =" pattern
  private lazy val receiverAssignGuard: P[Any] =
    (receiverForCommand ~ receiverAssignableSeparator).and

  private lazy val receiverAssignmentExprs: P[Expr] =
    chainedReceiverAssignExpr /
      receiverAssignExpr /
      receiverLogicalAssignExpr /
      receiverCompoundAssignExpr

  // Index-based assignments require "expr[" pattern
  private lazy val indexAssignmentExprs: P[Expr] =
    indexLogicalAssignExpr /
      indexCompoundAssignExpr /
      indexAssignExpr

  // Simple name-based assignments: name =, name +=, etc.
  // Guard: identifier/ivar/cvar/gvar followed by = or , ; or ( or * for grouped/splatted multi-assign
  private lazy val nameAssignGuard: P[Any] =
    (assignableName ~ (sym("=") / sym("||=") / sym("&&=") / sym(",") / compoundAssignOperator).and).and /
      sym("(").and /  // grouped multi-assign: (a, b) = ...
      sym("*").and    // splatted multi-assign: *a = ...

  private lazy val nameAssignmentExprs: P[Expr] =
    logicalAssignExpr /
      compoundAssignExpr /
      multiAssignExpr /
      assignExpr

  private lazy val assignmentCapableExpr: P[Expr] =
    (receiverAssignGuard ~> receiverAssignmentExprs) /
      indexAssignmentExprs /
      multiAssignExpr /
      (nameAssignGuard ~> nameAssignmentExprs) /
      conditionalExpr

  private lazy val expr: P[Expr] =
    chainl(assignmentCapableExpr)(infixLogicalKeyword("and") / infixLogicalKeyword("or")).memo

  // ─── Statement-Level Assignments & Multi-Assign ────────────────

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
    (assignableName ~ compoundAssignOperator ~ spacing ~ refer(expr)).map {
      case name ~ op ~ _ ~ value =>
        val underlying = op.dropRight(1)
        Assign(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val logicalAssignStmt: P[Statement] =
    (assignableName ~ (sym("||=") / sym("&&=")) ~ spacing ~ refer(expr)).map {
      case name ~ op ~ _ ~ value =>
        val underlying = if(op == "||=") "||" else "&&"
        Assign(name, BinaryOp(assignableAsExpr(name), underlying, value))
    }

  private lazy val assignStmt: P[Statement] =
    (assignableName ~ assignEq ~ spacing ~ assignValueExpr).map {
      case name ~ _ ~ _ ~ value => Assign(name, value)
    }

  private lazy val multiAssignTargetExpr: P[Expr] =
    indexTarget.map { case (receiver, args) => Call(Some(receiver), "[]", args) } /
      chainedReceiverAssignableHead.map { case receiver ~ name => Call(Some(receiver), name, Nil) } /
      receiverAssignableHead.map { case receiver ~ name => Call(Some(receiver), name, Nil) } /
      assignableName.map(assignableAsExpr)

  private def multiAssignTargetName(target: Expr): String =
    target match {
      case LocalVar(name, _) => name
      case InstanceVar(name, _) => name
      case ClassVar(name, _) => name
      case GlobalVar(name, _) => name
      case SelfExpr(_) => "self"
      case ConstRef(path, _) => path.mkString("::")
      case Call(Some(receiver), "[]", _, _) => s"${multiAssignTargetName(receiver)}[]"
      case Call(Some(receiver), name, Nil, _) => s"${multiAssignTargetName(receiver)}.$name"
      case other => other.toString
    }

  private lazy val multiAssignFlatElement: P[String] =
    (sym("*") ~ multiAssignTargetExpr.?).map {
      case _ ~ Some(target) => s"*${multiAssignTargetName(target)}"
      case _ ~ None => "*"
    } /
      multiAssignTargetExpr.map(multiAssignTargetName)

  private lazy val multiAssignNestedElement: P[String] =
    (refer(multiAssignGroupedElement) /
      multiAssignFlatElement).memo

  private lazy val multiAssignGroupedElement: P[String] =
    (sym("(") ~>
      (
        spacing ~>
          multiAssignNestedElement ~
          (sym(",") ~ spacing ~> multiAssignNestedElement).+ ~
          ((sym(",") <~ spacing).?) <~
          spacing <~
          sym(")")
      )).map {
      case first ~ rest ~ _ => s"(${(first :: rest).mkString(",")})"
    }.memo

  private lazy val multiAssignElement: P[String] =
    multiAssignGroupedElement /
      multiAssignFlatElement

  private lazy val multiAssignNames: P[List[String]] =
    (multiAssignElement ~ (sym(",") ~ spacing ~> multiAssignElement).+ ~ (sym(",") <~ spacing).?).map {
      case first ~ rest ~ _ => first :: rest
    } /
      (multiAssignElement ~ (sym(",") ~ spacing ~> multiAssignElement).* ~ (sym(",") <~ spacing)).map {
        case first ~ rest ~ _ => first :: rest
      } /
      multiAssignGroupedElement.map(List(_)) /
      (sym("*") ~ multiAssignTargetExpr.?).map {
        case _ ~ Some(target) => List(s"*${multiAssignTargetName(target)}")
        case _ ~ None => List("*")
      }

  private lazy val multiAssignStmt: P[Statement] =
    (((multiAssignNames <~ assignEq.and).and ~> multiAssignNames) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case names ~ _ ~ _ ~ value =>
        MultiAssign(names, value)
    }

  // ─── Constant & Def Assignments ────────────────────────────────

  private lazy val constAssignName: P[String] =
    constPathSegments.map(_.mkString("::"))

  private lazy val constAssignStmt: P[Statement] =
    (((constAssignName <~ assignEq.and).and ~> constAssignName) ~ assignEq ~ spacing ~ assignValueExpr).map {
      case name ~ _ ~ _ ~ value => Assign(name, value)
    }

  private lazy val defExpr: P[Expr] =
    refer(defStmt).map {
      case Def(name, _, _, _) =>
        SymbolLiteral(name.split('.').lastOption.getOrElse(name), UnknownSpan)
      case _ =>
        NilLiteral()
    }

  private lazy val assignDefStmt: P[Statement] =
    (((assignableName <~ assignEq.and).and ~> assignableName) ~ assignEq ~ spacing ~ defExpr).map {
      case name ~ _ ~ _ ~ value => Assign(name, value)
    }

  // ─── Return, Retry, Alias ─────────────────────────────────────

  private lazy val returnValueExpr: P[Expr] =
    sepBy1(refer(expr), sym(",")).map {
      case value :: Nil => value
      case values => ArrayLiteral(values)
    }

  private lazy val returnValueHeadGuard: P[Unit] =
    (!postfixModifierHeadKeyword).void

  private lazy val returnStmt: P[Statement] =
    (kw("return") ~ ((returnValueHeadGuard ~> returnValueExpr).?)).map {
      case _ ~ value => Return(value)
    }

  private lazy val retryStmt: P[Statement] =
    kw("retry").map(_ => Retry())

  private lazy val aliasNameExpr: P[Expr] =
    symbolLiteral /
      token(symbolOperatorNameNoSpace / methodIdentifierRaw / constName).map(name => SymbolLiteral(name, UnknownSpan)) /
      globalVarName.map(name => SymbolLiteral(name, UnknownSpan)) /
      instanceVarName.map(name => SymbolLiteral(name, UnknownSpan)) /
      classVarName.map(name => SymbolLiteral(name, UnknownSpan))

  private lazy val aliasStmt: P[Statement] =
    (kw("alias") ~ aliasNameExpr ~ aliasNameExpr).map {
      case _ ~ newName ~ oldName =>
        ExprStmt(Call(None, "alias", List(newName, oldName)))
    }

  // ─── Method Definition (def) ───────────────────────────────────

  private lazy val params: P[List[String]] =
    (sym("(") ~>
      (spacing ~>
        sepBy0(spacing ~> formalParam <~ spacing, (spacing ~> sym(",") <~ spacing).void) <~
        spacing) <~
      sym(")"))

  private lazy val bareParams: P[List[String]] =
    sepBy1(formalParam, (sym(",") ~ spacing).void)

  private lazy val defReceiverName: P[String] =
    (sym("(") ~> refer(expr) <~ sym(")")).map(_ => "(expr)") /
      constPathSegments.map(_.mkString("::")) /
      kw("self").map(_ => "self") /
      kw("nil").map(_ => "nil") /
      kw("true").map(_ => "true") /
      kw("false").map(_ => "false") /
      instanceVarName /
      classVarName /
      globalVarName /
      identifierNoSpace

  private lazy val defMethodName: P[String] =
    token(
      ("`".s ~ (escapedAnyChar / (!"`".s ~ !"\n".s ~ any).map {
        case _ ~ _ ~ ch => ch
      }).* ~ "`".s).map {
        case _ ~ chars ~ _ => s"`${chars.mkString}`"
      } /
        methodIdentifierNoSpace /
        bareKeywordMethodNameNoSpace /
        receiverKeywordMethodNameNoSpace /
        symbolOperatorNameNoSpace
    )

  private lazy val defName: P[String] =
    ((defReceiverName <~ sym(".")) ~ defMethodName).map {
      case receiver ~ methodName => s"$receiver.$methodName"
    } /
      defMethodName

  private lazy val defDecorator: P[String] =
    kw("private") /
      kw("public") /
      kw("protected") /
      kw("ruby2_keywords")

  private lazy val decoratedDefStmt: P[Statement] =
    (defDecorator ~> refer(defStmt))

  private def exprAsStatement(expr: Expr): Statement =
    expr match {
      case AssignExpr(name, value, span) => Assign(name, value, span)
      case MultiAssignExpr(names, value, span) => MultiAssign(names, value, span)
      case other => ExprStmt(other)
    }

  private lazy val exprStatement: P[Statement] =
    (refer(expr) ~ ((spacing1 ~> (commandArgHeadGuard ~> commandArgs)).?)).map {
      case value ~ Some(args) => ExprStmt(appendCommandArgs(value, args))
      case value ~ None => exprAsStatement(value)
    }

  private lazy val rightwardAssignStmt: P[Statement] =
  // ─── Simple & Keyword Statements ─────────────────────────────

    ((conditionalExpr <~ sym("=>").and) ~ sym("=>") ~ rightwardAssignPattern).map {
      case value ~ _ ~ pattern => ExprStmt(BinaryOp(value, "=>", pattern))
    }

  private lazy val simpleStatement: P[Statement] =
    (kw("return").and ~> returnStmt) /
      (kw("retry").and ~> retryStmt) /
      constAssignStmt /
      assignDefStmt /
      rightwardAssignStmt /
      exprStatement

  // Guard: first character matches keyword statement starters (b/w/u/f/c/d/m/i/a)
  private lazy val keywordStmtGuard: P[String] =
    range('b' to 'b', 'w' to 'w', 'u' to 'u', 'f' to 'f', 'c' to 'c', 'd' to 'd', 'm' to 'm', 'i' to 'i', 'a' to 'a')

  private lazy val keywordStatements: P[Statement] =
    chainableKeywordExprStmt /
      refer(beginStmt) /
      refer(whileStmt) /
      refer(untilStmt) /
      refer(forStmt) /
      refer(caseStmt) /
      decoratedDefStmt /
      refer(defStmt) /
      refer(singletonClassStmt) /
      refer(classStmt) /
      refer(moduleStmt) /
      refer(ifStmt) /
      refer(unlessStmt) /
      aliasStmt

  private lazy val statementBase: P[Statement] =
    (keywordStmtGuard.and ~> keywordStatements) / simpleStatement

  private lazy val statement: P[Statement] =
    guarded("statement") {
      (statementBase ~ (inlineSpacing ~> modifierSuffix).*).map {
        case stmt ~ modifiers =>
          modifiers.foldLeft(stmt)((current, modifier) => modifier(current))
      }
    }.memo

  // ─── Block Statements & Rescue ─────────────────────────────────

  private lazy val topLevelStatements: P[List[Statement]] =
    sepBy0(refer(statement), statementSep)

  private def blockStatementsUntil(stop: P[Any]): P[List[Statement]] =
    guarded("blockStatementsUntil") {
      ((statementSep.* ~> stop.and) ~> success(Nil)) /
        ((refer(statement) ~ (statementSep.+ ~> refer(statement)).* ~ statementSep.* <~ stop.and).map {
          case first ~ rest ~ _ => first :: rest
        })
    }.memo

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

  // ─── Begin / Rescue / Ensure ───────────────────────────────────

  private lazy val beginStmt: P[Statement] =
    (
      kw("begin") ~ (
        statementSep.* ~
        blockStatementsUntil(kw("rescue") / kw("else") / kw("ensure") / kw("end")) ~
        statementSep.* ~
        rescueClause.* ~
        statementSep.* ~
        (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("ensure") / kw("end"))).? ~
        statementSep.* ~
        (kw("ensure") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
        statementSep.* ~ kw("end")
      ).cut
    ).map {
      case _ ~ (_ ~ body ~ _ ~ rescues ~ _ ~ elseOpt ~ _ ~ ensureOpt ~ _ ~ _) =>
        val elseBody = elseOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val ensureBody = ensureOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        BeginRescue(body, rescues, elseBody, ensureBody)
    }

  private sealed trait DefBodyVariant
  private case class EndlessDefBody(expr: Expr) extends DefBodyVariant
  private case class RegularDefBody(
    body: List[Statement],
    rescues: List[RescueClause],
    elseBody: List[Statement],
    ensureBody: List[Statement]
  ) extends DefBodyVariant

  private lazy val regularDefBody: P[DefBodyVariant] =
    (
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
    ).cut.map {
      case _ ~ body ~ _ ~ rescues ~ _ ~ elseOpt ~ _ ~ ensureOpt ~ _ ~ _ =>
        val elseBody = elseOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        val ensureBody = ensureOpt.map { case _ ~ _ ~ statements => statements }.getOrElse(Nil)
        RegularDefBody(body, rescues, elseBody, ensureBody)
    }

  private lazy val defStmt: P[Statement] =
    (
      kw("def") ~ (
        defName ~
        (params / bareParams).? ~
        (
          (assignEq ~ spacing ~ refer(expr)).map { case _ ~ _ ~ bodyExpr => EndlessDefBody(bodyExpr) } /
            regularDefBody
        )
      ).cut
    ).map {
      case _ ~ (name ~ maybeParams ~ EndlessDefBody(bodyExpr)) =>
        Def(name, maybeParams.getOrElse(Nil), List(ExprStmt(bodyExpr)))
      case _ ~ (name ~ maybeParams ~ RegularDefBody(body, rescues, elseBody, ensureBody)) =>
        val statements =
          if(rescues.nonEmpty || elseBody.nonEmpty || ensureBody.nonEmpty) {
            List(BeginRescue(body, rescues, elseBody, ensureBody))
          } else {
            body
          }
        Def(name, maybeParams.getOrElse(Nil), statements)
    }

  private lazy val singletonClassExpr: P[Expr] =
    (kw("class") ~ sym("<<") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).map {
      case _ ~ _ ~ receiver ~ _ ~ body ~ _ ~ _ =>
        SingletonClassExpr(receiver, body)
    }

  private lazy val singletonClassStmt: P[Statement] =
    (kw("class") ~ sym("<<") ~ refer(expr) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end") ~ callSuffix.*).map {
      case _ ~ _ ~ receiver ~ _ ~ body ~ _ ~ _ ~ suffixes =>
        if(suffixes.isEmpty) {
          SingletonClassDef(receiver, body)
        } else {
          val base: Expr = SingletonClassExpr(receiver, body)
          ExprStmt(suffixes.foldLeft(base)((current, suffix) => suffix(current)))
        }
    }

  // ─── For / When / Case-In (Pattern Matching) ──────────────────

  private lazy val forBindingNames: P[String] =
    (identifier ~ (sym(",") ~ identifier).*).map {
      case first ~ rest => (first :: rest.map(_._2)).mkString(",")
    }

  private lazy val forStmt: P[Statement] =
    (kw("for") ~ (forBindingNames ~ kw("in") ~ refer(conditionExpr) ~ kw("do").? ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).cut).map {
      case _ ~ (name ~ _ ~ iterable ~ _ ~ _ ~ body ~ _ ~ _) =>
        ForIn(name, iterable, body)
    }

  private lazy val whenClause: P[WhenClause] =
    (
      kw("when") ~ sepBy1(refer(expr), sym(",")) ~ (kw("then") / sym(";")).? ~ statementSep.* ~ blockStatementsUntil(kw("when") / kw("in") / kw("else") / kw("end")) ~ statementSep.*
    ).map {
      case _ ~ patterns ~ _ ~ _ ~ body ~ _ => WhenClause(patterns, body)
    } /
      (
        kw("in") ~ inClausePatterns ~ (kw("then") / sym(";")).? ~ statementSep.* ~ blockStatementsUntil(kw("when") / kw("in") / kw("else") / kw("end")) ~ statementSep.*
      ).map {
        case _ ~ patterns ~ _ ~ _ ~ body ~ _ => WhenClause(patterns, body)
      }

  private def implicitPatternBinding(key: Expr): Expr =
    key match {
      case SymbolLiteral(name, _) => LocalVar(name)
      case StringLiteral(name, _) if name.matches("[A-Za-z_][A-Za-z0-9_]*") => LocalVar(name)
      case _ => NilLiteral()
    }

  private lazy val inPatternSplatTarget: P[Expr] =
    kw("nil").map(_ => NilLiteral()) /
      identifier.map(LocalVar(_))

  private lazy val inPatternSplatExpr: P[Expr] =
    (sym("*") ~ inPatternSplatTarget.?).map {
      case _ ~ targetOpt => Call(None, "*", targetOpt.toList)
    }

  private lazy val inPatternHashRestEntry: P[(Expr, Expr)] =
    (sym("**") ~ inPatternSplatTarget.?).map {
      case _ ~ targetOpt => SymbolLiteral("**", UnknownSpan) -> targetOpt.getOrElse(NilLiteral())
    }

  private lazy val inPatternDeconstructTarget: P[Expr] =
    constRef

  private lazy val inPatternParenArgs: P[List[Expr]] =
    (sym("(") ~>
      (
        (spacing ~> inClausePatterns.? <~ spacing <~ sym(")"))
      ).cut).map {
      _.getOrElse(Nil)
    }

  private lazy val inPatternBracketArgs: P[List[Expr]] =
    (sym("[") ~>
      (
        (spacing ~> inClausePatterns.? <~ spacing <~ sym("]"))
      ).cut).map {
      _.getOrElse(Nil)
    }

  private lazy val inPatternDeconstructExpr: P[Expr] =
    (inPatternDeconstructTarget ~ inPatternParenArgs).map {
      case target ~ args => Call(Some(target), "call", args)
    } /
      (inPatternDeconstructTarget ~ inPatternBracketArgs).map {
        case target ~ args => Call(Some(target), "[]", args)
      }

  private lazy val inPatternValueExpr: P[Expr] =
    refer(inPatternPrimaryExpr)

  private lazy val inPatternShorthandLabelTail: P[Any] =
    sym(",").and /
      sym("]").and /
      sym(")").and /
      sym("}").and /
      kw("if").and /
      kw("unless").and /
      sym(";").and /
      newlineChar.and /
      !any

  private lazy val inPatternShorthandLabelEntry: P[(Expr, Expr)] =
    ((labelNameNoSpace <~ labelColon) <~ (inlineSpacing ~> inPatternShorthandLabelTail)).map { name =>
      SymbolLiteral(name, UnknownSpan) -> LocalVar(name, UnknownSpan)
    }

  private lazy val inPatternQuotedShorthandLabelEntry: P[(Expr, Expr)] =
    (((stringLiteral / symbolLiteral) <~ labelColon) <~ (inlineSpacing ~> inPatternShorthandLabelTail)).map { key =>
      key -> implicitPatternBinding(key)
    }

  private lazy val inPatternHashLabelEntry: P[(Expr, Expr)] =
    (((stringLiteral / symbolLiteral) <~ labelColon) ~ (spacing ~> inPatternValueExpr)).map {
      case key ~ value => key -> value
    }

  private lazy val inPatternHashEntry: P[(Expr, Expr)] =
    inPatternHashRestEntry /
      inPatternHashLabelEntry /
      ((symbolLabelNameNoSpace <~ labelColon) ~ (spacing ~> inPatternValueExpr)).map {
        case name ~ value => SymbolLiteral(name, UnknownSpan) -> value
      } /
      inPatternQuotedShorthandLabelEntry /
      inPatternShorthandLabelEntry /
      (hashRocketArgKeyExpr ~ sym("=>") ~ inPatternValueExpr).map {
        case key ~ _ ~ value => key -> value
      }

  private lazy val inPatternHashLiteral: P[Expr] =
    (sym("{") ~> spacing ~> sepBy0(spacing ~> inPatternHashEntry <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("}")).map {
      case entries ~ _ => HashLiteral(entries)
    }

  private lazy val inPatternPrimaryExpr: P[Expr] =
    inPatternDeconstructExpr /
      inPatternHashLiteral /
      refer(inPatternArrayLiteral) /
      ((sym("^") ~ refer(expr)).map { case _ ~ value => UnaryOp("^", value) }) /
      exprWithPostfixModifier

  private lazy val inPatternGuardSuffix: P[Expr => Expr] =
    (kw("if") ~ refer(expr)).map {
      case _ ~ condition =>
        (target: Expr) => IfExpr(condition, List(ExprStmt(target)), Nil)
    } /
      (kw("unless") ~ refer(expr)).map {
        case _ ~ condition =>
          (target: Expr) => UnlessExpr(condition, List(ExprStmt(target)), Nil)
      }

  private lazy val inPatternOrExpr: P[Expr] =
    chainl(refer(inPatternPrimaryExpr))(sym("|").map(_ => (lhs: Expr, rhs: Expr) => BinaryOp(lhs, "|", rhs)))

  private lazy val inPatternExpr: P[Expr] =
    ((inPatternOrExpr ~ (sym("=>") ~ refer(expr)).?).map {
      case lhs ~ Some(_ ~ rhs) => BinaryOp(lhs, "=>", rhs)
      case lhs ~ None => lhs
    } ~ (inlineSpacing ~> inPatternGuardSuffix).?).map {
      case target ~ Some(guard) => guard(target)
      case target ~ None => target
    }

  private lazy val inPatternListElement: P[Expr] =
    inPatternSplatExpr /
      refer(inPatternExpr)

  private lazy val inTopLevelHashPattern: P[List[Expr]] =
    (sepBy1(inPatternHashEntry, sym(",")) ~ (sym(",").?)).map {
      case entries ~ _ => List(HashLiteral(entries))
    }

  private lazy val inPatternArrayLiteral: P[Expr] =
    (sym("[") ~> spacing ~> sepBy0(spacing ~> refer(inPatternListElement) <~ spacing, sym(",")) ~ ((sym(",") <~ spacing).?) <~ spacing <~ sym("]")).map {
      case values ~ _ => ArrayLiteral(values)
    }

  private lazy val inTopLevelHashHead: P[Any] =
    sym("**").and /
      ((stringLiteral / symbolLiteral) <~ labelColon).and /
      (symbolLabelNameNoSpace <~ labelColon).and

  private lazy val inClausePatterns: P[List[Expr]] =
    ((inTopLevelHashHead.and ~> inTopLevelHashPattern) /
      ((sepBy1(inPatternListElement, sym(",")) ~ (sym(",").?)).map {
        case patterns ~ _ => patterns
      }))

  private lazy val rightwardAssignPattern: P[Expr] =
    (inTopLevelHashHead.and ~> inTopLevelHashPattern).map(collapsePatternList) /
      ((sepBy1(inPatternListElement, sym(",")) ~ (sym(",").?)).map {
        case patterns ~ _ => collapsePatternList(patterns)
      }) /
      refer(inPatternExpr)

  private def collapsePatternList(patterns: List[Expr]): Expr =
    patterns match {
      case pattern :: Nil => pattern
      case many => ArrayLiteral(many)
    }

  private lazy val caseStmt: P[Statement] =
    (
      kw("case") ~ (
        refer(expr).? ~ statementSep.* ~ whenClause.+ ~
        statementSep.* ~
        (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
        statementSep.* ~ kw("end")
      ).cut
    ).map {
      case _ ~ (scrutinee ~ _ ~ whens ~ _ ~ elseOpt ~ _ ~ _) =>
        val elseBody = elseOpt.map { case _ ~ _ ~ body => body }.getOrElse(Nil)
        CaseExpr(scrutinee, whens, elseBody)
    }

  // ─── While / Until / Class / Module ──────────────────────────

  private lazy val whileStmt: P[Statement] =
    (kw("while") ~ (refer(conditionExpr) ~ (kw("do") / kw("then")).? ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).cut).map {
      case _ ~ (condition ~ _ ~ _ ~ body ~ _ ~ _) =>
        WhileExpr(condition, body)
    }

  private lazy val untilStmt: P[Statement] =
    (kw("until") ~ (refer(conditionExpr) ~ (kw("do") / kw("then")).? ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).cut).map {
      case _ ~ (condition ~ _ ~ _ ~ body ~ _ ~ _) =>
        UntilExpr(condition, body)
    }

  private lazy val classStmt: P[Statement] =
    (kw("class") ~ constPathSegments ~ ((sym("<") ~ refer(expr)).?.map(_.map(_._2)) ~ statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).cut).map {
      case _ ~ name ~ (superClass ~ _ ~ body ~ _ ~ _) =>
        ClassDef(name.mkString("::"), body, UnknownSpan, superClass)
    }

  private lazy val moduleStmt: P[Statement] =
    (kw("module") ~ constPathSegments ~ (statementSep.* ~ blockStatements ~ statementSep.* ~ kw("end")).cut).map {
      case _ ~ name ~ (_ ~ body ~ _ ~ _) =>
        ModuleDef(name.mkString("::"), body)
    }

  // ─── Modifier Suffixes & If / Unless ──────────────────────────

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
    guarded("ifTail") {
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
    }

  private lazy val ifStmt: P[Statement] =
    (
      kw("if") ~ (
        refer(expr) ~ kw("then").? ~ statementSep.* ~ blockStatementsUntil(kw("elsif") / kw("else") / kw("end")) ~
        statementSep.* ~ refer(ifTail) ~
        statementSep.* ~ kw("end")
      ).cut
    ).map {
      case _ ~ (condition ~ _ ~ _ ~ thenBody ~ _ ~ elseBody ~ _ ~ _) =>
        IfExpr(condition, thenBody, elseBody)
    }

  private lazy val unlessStmt: P[Statement] =
    (
      kw("unless") ~ (
        refer(expr) ~ kw("then").? ~ statementSep.* ~ blockStatementsUntil(kw("else") / kw("end")) ~
        statementSep.* ~
        (kw("else") ~ statementSep.* ~ blockStatementsUntil(kw("end"))).? ~
        statementSep.* ~ kw("end")
      ).cut
    ).map {
      case _ ~ (condition ~ _ ~ _ ~ thenBody ~ _ ~ elseBodyOpt ~ _ ~ _) =>
        val elseBody = elseBodyOpt.map { case _ ~ _ ~ body => body }.getOrElse(Nil)
        UnlessExpr(condition, thenBody, elseBody)
    }

  // ─── Top-Level Program ─────────────────────────────────────────

  private lazy val program: P[Program] =
    (spacing ~> (topLevelStatements <~ statementSep.*) <~ spacing).map(stmts => Program(stmts))

  // ─── Preprocessing Utilities ───────────────────────────────────

  private def encodeDoubleQuoted(value: String): String = {
    val builder = new StringBuilder("\"")
    var index = 0
    while(index < value.length) {
      val c = value.charAt(index)
      c match {
        case '\\' => builder.append("\\\\")
        case '"' => builder.append("\\\"")
        case '\n' => builder.append("\\n")
        case '\r' => builder.append("\\r")
        case '\t' => builder.append("\\t")
        // Keep heredoc payload literal after normalization.
        case '#' if index + 1 < value.length && value.charAt(index + 1) == '{' =>
          builder.append("\\#")
        case _ =>
          builder.append(c)
      }
      index += 1
    }
    builder.append("\"").toString
  }

  private def normalizeHeredoc(input: String): String = {
    if(!input.contains("<<")) return input
    val heredocPattern = raw"""(?<![\w\)\]\}'\x22\x60\\])<<([~-]?)(?:'([^'\n]*)'|"([^"\n]*)"|`([^`\n]*)`|([A-Za-z_][A-Za-z0-9_]*))""".r
    val heredocTokenPattern = raw"""__MACROPEG_HEREDOC_\d+__""".r
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
        val matches = heredocPattern.findAllMatchIn(line).filter(m => isCodePositionForHeredoc(line, m.start)).toList
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
      val normalized = outputLines.mkString("\n")
      if(replacements.isEmpty) normalized
      else {
        val replacementMap = replacements.toMap
        heredocTokenPattern.replaceAllIn(
          normalized,
          m => java.util.regex.Matcher.quoteReplacement(replacementMap.getOrElse(m.matched, m.matched))
        )
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

  private def stripEndMarker(input: String): String = {
    if(!input.contains("__END__")) return input
    val lines = input.split("\n", -1)
    val endIdx = lines.indexWhere(_ == "__END__")
    if(endIdx >= 0) lines.take(endIdx).mkString("\n") else input
  }

  def parse(input: String): Either[String, Program] = {
    val normalized = stripEndMarker(normalizeHeredoc(stripXOptionPreamble(input)))
    parseAll(program, normalized).left.map(f => formatFailure(normalized, f))
  }
}
