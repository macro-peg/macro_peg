package com.github.kmizu.macro_peg

import com.github.kmizu.scomb._
import java.io._

import Ast._
/**
  * This object provides a parser that parses strings in Macro PEG and translates
  * them into ASTs of Macro PEG (which is like PEGs).
  * @author Kota Mizushima
  *
  */
object Parser {

  /**
   * This exception is thrown in the case of a parsing failure
 *
   * @param pos the position where the parsing failed
   * @param msg error message
   */
  case class ParseException(pos: Ast.Position, msg: String) extends Exception(pos.line + ", " + pos.column + ":" + msg)
  
  private object ParserCore extends SCombinator {
    implicit class RichParse[A](self: Parser[A]) {
      def <~[B](rhs: Parser[B]): Parser[A] = self << rhs
      def ~>[B](rhs: Parser[B]): Parser[B] = self >> rhs
    }
    private def chr(c: Char): Parser[Char] = any.filter(_ == c,s"expected ${c}")
    private def crange(f: Char, t: Char): Parser[Char] = set(f to t).map{_.charAt(0)}
    private def cset(cs: Char*): Parser[Char] = set(cs).map{_.charAt(0)}
    private val escape: Map[Char, Char] = Map(
      'n' -> '\n', 'r' -> '\r', 't' -> '\t', 'f' -> '\f'
    )
    def root: Parser[Grammar] = GRAMMAR
    lazy val GRAMMAR: Parser[Grammar] = rule((loc <~ Spacing) ~ HeadBlock.? ~ Directive.* ~ Definition.* <~ EndOfFile) ^^ {
      case pos ~ preambleOpt ~ directives ~ rules => Grammar(Position(pos.line, pos.column), rules, directives, preambleOpt.getOrElse(""))
    }
    lazy val HeadBlock: Parser[String] = rule {
      (string("head") <~ Spacing) ~> LBRACE ~> ActionBody <~ RBRACE <~ Spacing
    }

    // ── Directives ──────────────────────────────────────────────────────────
    lazy val Directive: Parser[Ast.Directive] = rule(
      PercentPackage | PercentImport | PercentObject | PercentStart | PercentHelper | PercentPreprocess | PercentRaw
    )
    private def percentKeyword(kw: String): Parser[Unit] =
      (chr('%') ~ string(kw) ~ Spacing).map(_ => ())
    lazy val PercentPackage: Parser[Ast.PackageDirective] = rule(
      percentKeyword("package") ~> (not(chr(';')) ~> any).* <~ chr(';') <~ Spacing
    ) ^^ { cs => Ast.PackageDirective(cs.mkString.trim) }
    lazy val PercentImport: Parser[Ast.ImportDirective] = rule(
      percentKeyword("import") ~> (not(chr(';')) ~> any).* <~ chr(';') <~ Spacing
    ) ^^ { cs => Ast.ImportDirective(cs.mkString.trim) }
    lazy val PercentObject: Parser[Ast.ObjectDirective] = rule(
      percentKeyword("object") ~> (not(chr(';')) ~> any).* <~ chr(';') <~ Spacing
    ) ^^ { cs => Ast.ObjectDirective(cs.mkString.trim) }
    lazy val PercentStart: Parser[Ast.StartDirective] = rule(
      percentKeyword("start") ~> (not(chr(';')) ~> any).* <~ chr(';') <~ Spacing
    ) ^^ { cs => Ast.StartDirective(Symbol(cs.mkString.trim)) }
    lazy val PercentHelper: Parser[Ast.HelperDirective] = rule(
      percentKeyword("helper") ~> ScalaBlock <~ chr(';').? <~ Spacing
    ) ^^ { code => Ast.HelperDirective(code) }
    lazy val PercentPreprocess: Parser[Ast.PreprocessDirective] = rule(
      percentKeyword("preprocess") ~> ScalaBlock <~ chr(';').? <~ Spacing
    ) ^^ { code => Ast.PreprocessDirective(code) }
    lazy val PercentRaw: Parser[Ast.RawRuleDirective] = rule(
      (percentKeyword("raw") ~> Ident) ~ (ScalaBlock <~ chr(';').? <~ Spacing)
    ) ^^ { case name ~ code => Ast.RawRuleDirective(name.name.name, code) }

    // ── Balanced-brace Scala code block parser ──────────────────────────────
    // Returns the inner content (excluding outer braces) as a String
    lazy val ScalaBlock: Parser[String] = rule(chr('{') ~> ScalaBlockInner <~ chr('}'))
    lazy val ScalaBlockInner: Parser[String] = ScalaChunk.* ^^ { _.mkString }
    lazy val ScalaChunk: Parser[String] = rule(
        ScalaNestedBlock
      | ScalaTripleQuoteStr
      | ScalaDoubleQuoteStr
      | ScalaLineCommentChunk
      | ScalaBlockCommentChunk
      | ScalaCharLiteralChunk
      | (not(chr('}')) ~> any) ^^ { c => c.toString }
    )
    lazy val ScalaNestedBlock: Parser[String] = rule(
      (chr('{') ~ ScalaBlockInner ~ chr('}')) ^^ { case _ ~ inner ~ _ => "{" + inner + "}" }
    )
    // Triple-quoted strings: """..."""  (raw""" also matched since raw is just an identifier before """)
    lazy val ScalaTripleQuoteStr: Parser[String] = rule(
      (string("\"\"\"") ~ ScalaTripleStrContent.* ~ string("\"\"\"")) ^^ {
        case _ ~ cs ~ _ => "\"\"\"" + cs.mkString + "\"\"\""
      }
    )
    lazy val ScalaTripleStrContent: Parser[String] = rule(
      (not(string("\"\"\"")) ~> any) ^^ { c => c.toString }
    )
    lazy val ScalaDoubleQuoteStr: Parser[String] = rule(
      (chr('"') ~ ScalaStrChar.* ~ chr('"')) ^^ { case _ ~ cs ~ _ => "\"" + cs.mkString + "\"" }
    )
    lazy val ScalaStrChar: Parser[String] = rule(
        (chr('\\') ~ any) ^^ { case _ ~ c => "\\" + c.toString }
      | (not(chr('"')) ~> any) ^^ { _.toString }
    )
    lazy val ScalaLineCommentChunk: Parser[String] = rule(
      (string("//") ~ (not(EndOfLine) ~> any).*) ^^ { case _ ~ cs => "//" + cs.mkString }
    )
    lazy val ScalaBlockCommentChunk: Parser[String] = rule(
      (string("/*") ~ (not(string("*/")) ~> any).* ~ string("*/")) ^^ { case _ ~ cs ~ _ => "/*" + cs.mkString + "*/" }
    )
    lazy val ScalaCharLiteralChunk: Parser[String] = rule(
      (chr('\'') ~ ScalaCharLitContent ~ chr('\'')) ^^ { case _ ~ c ~ _ => "'" + c + "'" }
    )
    lazy val ScalaCharLitContent: Parser[String] = rule(
        (chr('\\') ~ any) ^^ { case _ ~ c => "\\" + c.toString }
      | (not(chr('\'')) ~> any) ^^ { _.toString }
    )

    // ── Annotations ─────────────────────────────────────────────────────────
    lazy val Annotation: Parser[Ast.Annotation] = rule(
      chr('@') ~> AnnotationBody <~ Spacing
    )
    lazy val AnnotationBody: Parser[Ast.Annotation] = rule(
      (loc <~ string("memo"))  ^^ { p => Ast.MemoAnnotation(Position(p.line, p.column)) }
    | (loc <~ string("cut"))   ^^ { p => Ast.CutAnnotation(Position(p.line, p.column)) }
    | (loc <~ string("void"))  ^^ { p => Ast.VoidAnnotation(Position(p.line, p.column)) }
    | (loc <~ string("token")) ^^ { p => Ast.TokenAnnotation(Position(p.line, p.column)) }
    | (loc <~ string("label") <~ chr('(') <~ chr('"')) ~ (not(chr('"')) ~> any).* <~ chr('"') <~ chr(')')
        ^^ { case p ~ cs => Ast.LabelAnnotation(Position(p.line, p.column), cs.mkString) }
    | (loc <~ string("guard") <~ chr('(') <~ chr('"')) ~ (not(chr('"')) ~> any).* <~ chr('"') <~ chr(')')
        ^^ { case p ~ cs => Ast.GuardAnnotation(Position(p.line, p.column), cs.mkString) }
    )

    lazy val Definition: Parser[Rule] =
      rule(Annotation.* ~ Ident ~ (COLON ~> ReturnType).? ~ ((LPAREN ~> Arg.repeat1By(COMMA) <~ RPAREN).? <~ EQ) ~ (Expression ~ ActionBlock.? <~ SEMI_COLON).commit) ^^ {
        case anns ~ name ~ retType ~ argsOpt ~ (body ~ actionOpt) =>
          val argsWithTypes = argsOpt.getOrElse(List())
          val finalBody = actionOpt match {
            case Some(action) => Sequence(body.pos, body, action)
            case None => body
          }
          Rule(
            name.pos,
            name.name,
            finalBody,
            argsWithTypes.map(_._1.name),
            argsWithTypes.map(_._2),
            retType,
            anns
          )
      }

    lazy val ReturnType: Parser[String] = rule(
      (not(chr('=')) ~> any).+ ^^ { cs => cs.mkString.trim }
    )

    lazy val Arg: Parser[(Identifier, Option[Type])] = rule(Ident ~ (COLON ~> TypeTree).?) ^^ { case id ~ tpe => (id, tpe)}

    lazy val TypeTree: Parser[Type] = rule {
      RuleTypeTree | SimpleTypeTree
    }

    lazy val RuleTypeTree: Parser[RuleType] = rule {
      (OPEN ~> (SimpleTypeTree.repeat1By(COMMA) <~ CLOSE) ~ (loc <~ ARROW) ~ SimpleTypeTree) ^^ { case paramTypes ~ pos ~ resultType => RuleType(Position(pos.line, pos.column), paramTypes, resultType) }
    | SimpleTypeTree ~ (loc <~ ARROW) ~ SimpleTypeTree ^^ { case paramType ~ pos ~ resultType => RuleType(Position(pos.line, pos.column), List(paramType), resultType) }
    }

    lazy val SimpleTypeTree: Parser[SimpleType] = rule {
      loc <~ QUESTION ^^ { case pos => SimpleType(Position(pos.line, pos.column)) }
    }
    
    lazy val Expression: Parser[Expression] = rule(Sequencable.repeat1By(SLASH | BAR) ^^ { ns =>
      val x :: xs = ns; xs.foldLeft(x){(a, y) => Alternation(y.pos, a, y)}
    })

    // Sequencable: a chain of elements connected by implicit sequence, <~, or ~>,
    // optionally split by the `^` cut operator, and optionally followed by
    // an action block: => { scalaCode }
    lazy val Sequencable: Parser[Expression] = rule(
      CutSequence ~ ActionSuffix.? ^^ {
        case body ~ None => body
        case body ~ Some((apos, code)) => Ast.ActionBlock(apos, body, code)
      }
    )

    // CUT_OP matches the `^` cut operator in sequence position.
    // Note: `^` inside [...] (character class) is handled separately by CLASS; inside "..." by Literal.
    // A bare `^` between sequence elements is the commit/cut operator.
    lazy val CUT_OP: Parser[Unit] = chr('^') <~ Spacing ^^ { _ => () }
    lazy val CutSequence: Parser[Expression] = rule(
      ProjectedSequence ~ (loc ~ (CUT_OP ~> ProjectedSequence)).? ^^ {
        case before ~ None => before
        case before ~ Some(cutLoc ~ after) =>
          Sequence(before.pos, before, Cut(Position(cutLoc.line, cutLoc.column), after))
      }
    )

    // Action block suffix: => { scalaCode }
    lazy val ActionSuffix: Parser[(Position, String)] = rule(
      (loc <~ string("=>") <~ Spacing) ~ (ScalaBlock <~ Spacing) ^^ { case pos ~ code => (Position(pos.line, pos.column), code) }
    )

    sealed trait ProjOp
    case object SeqOp extends ProjOp
    case object LeftProjOp extends ProjOp
    case object RightProjOp extends ProjOp

    // Parse elements separated by <~, ~>, or implicit juxtaposition
    lazy val ProjectedSequence: Parser[Expression] = rule(
      Prefix ~ ProjElement.* ^^ { case first ~ rest =>
        rest.foldLeft(first) { case (acc, (op, e)) => op match {
          case SeqOp       => Sequence(e.pos, acc, e)
          case LeftProjOp  => LeftProject(e.pos, acc, e)
          case RightProjOp => RightProject(e.pos, acc, e)
        }}
      }
    )

    lazy val ProjElement: Parser[(ProjOp, Expression)] = rule(
      (loc <~ chr('<') <~ chr('~') <~ Spacing) ~ Prefix ^^ { case _ ~ e => (LeftProjOp, e) }
    | (loc <~ chr('~') <~ chr('>') <~ Spacing) ~ Prefix ^^ { case _ ~ e => (RightProjOp, e) }
    | Prefix ^^ { e => (SeqOp, e) }
    )
    lazy val Prefix: Parser[Expression]     = rule(
      (loc <~ AND) ~ Suffix ^^ { case pos ~ e => AndPredicate(Position(pos.line, pos.column), e) }
    | (loc <~ NOT) ~ Suffix ^^ { case pos ~ e => NotPredicate(Position(pos.line, pos.column), e) }
    | Suffix
    )
    lazy val Suffix: Parser[Expression]     = rule(
      loc ~ Primary <~ QUESTION ^^ { case pos ~ e => Optional(Position(pos.line, pos.column), e) }
    | loc ~ Primary <~ STAR ^^ { case pos ~ e => Repeat0(Position(pos.line, pos.column), e) }
    | loc ~ Primary <~ PLUS ^^ { case pos ~ e => Repeat1(Position(pos.line, pos.column), e) }
    | loc ~ Primary <~ (chr(':') ~ string("ign") ~ Spacing) ^^ { case pos ~ e => IgnoredExpr(Position(pos.line, pos.column), e) }
    | Primary
    )
    lazy val Primary: Parser[Expression]    = rule(
      (loc <~ Debug) ~ (LPAREN ~> Expression <~ RPAREN) ^^ { case loc ~ body => Ast.Debug(Position(loc.line, loc.column), body)}
    | IdentifierWithoutSpace ~ (LPAREN ~> Expression.repeat0By(COMMA) <~ RPAREN) ^^ { case name ~ params => Ast.Call(Position(name.pos.line, name.pos.column), name.name, params) }
    | LabeledExpr
    | Ident
    | CLASS
    | (OPEN ~> (Ident.repeat0By(COMMA) ~ (loc <~ ARROW) ~ Expression) <~ CLOSE) ^^ { case ids ~ loc ~ body => Function(Position(loc.line, loc.column), ids.map(_.name), body) }
    | OPEN ~> Expression <~ CLOSE
    | loc <~ DOT ^^ { case pos => Wildcard(Position(pos.line, pos.column)) }
    | loc <~ chr('_') ^^ { case pos => StringLiteral(Position(pos.line, pos.column), "") }
    | Literal
    )
    lazy val LabeledExpr: Parser[Labeled] = rule(
      IdentifierWithoutSpace ~ (chr(':') <~ Spacing) ~ Suffix ^^ {
        case id ~ _ ~ body => Labeled(id.pos, id.name.name, body)
      }
    )
    lazy val loc: Parser[Location] = %
    lazy val IdentifierWithoutSpace: Parser[Identifier] = rule(loc ~ IdentStart ~ IdentCont.* ^^ {
      case pos ~ s ~ c => Identifier(Position(pos.line, pos.column), Symbol("" + s + c.foldLeft("")(_ + _)))
    })
    lazy val Ident: Parser[Identifier] = rule(IdentifierWithoutSpace <~ Spacing)
    lazy val IdentStart: Parser[Char] = rule(crange('a','z') | crange('A','Z') | chr('_'))
    lazy val IdentCont: Parser[Char] = rule(IdentStart | crange('0','9'))
    lazy val Literal: Parser[StringLiteral] = rule(loc ~ (chr('\"') ~> CHAR.* <~ chr('\"')) <~ Spacing) ^^ {
      case pos ~ cs => StringLiteral(Position(pos.line, pos.column), cs.mkString)
    }
    lazy val CLASS: Parser[CharClass] = rule {
      (loc <~ chr('[')) ~ chr('^').? ~ ((not(chr(']')) ~> Range).* <~ chr(']') ~> Spacing) ^^ {
        //negative character class
        case (pos ~ Some(_) ~ rs) => CharClass(Position(pos.line, pos.column), false, rs)
        //positive character class
        case (pos ~ None ~ rs) => CharClass(Position(pos.line, pos.column), true, rs)
      }
    }
    lazy val Range: Parser[CharClassElement] = rule(
      CHAR ~ chr('-') ~ CHAR ^^ { case f ~ _ ~ t => CharRange(f, t) }
    | CHAR ^^ { case c => OneChar(c) }
    )
    private val META_CHARS = List('"','\\')
    lazy val META: Parser[Char] = cset(META_CHARS:_*)
    lazy val HEX: Parser[Char] = crange('0','9') | crange('a', 'f')
    lazy val CHAR: Parser[Char] = ( 
      chr('\\') ~> cset('n','r','t','f') ^^ { case c => escape(c) }
    | chr('\\') ~> chr('u') ~> (HEX ~ HEX ~ HEX ~ HEX) ^^ {
        case u1 ~ u2 ~ u3 ~ u4 => Integer.parseInt("" + u1 + u2 + u3 + u4, 16).toChar
      }
    | chr('\\') ~ META ^^ { case _ ~ c => c }
    | chr('\\') ~ crange('0','2') ~ crange('0','7') ~ crange('0','7') ^^ { 
        case _ ~ a ~ b ~ c => Integer.parseInt("" + a + b + c, 8).toChar
      }
    | chr('\\') ~ crange('0','7') ~ crange('0','7').? ^^ {
        case _ ~ a ~ Some(b) => Integer.parseInt("" + a + b, 8).toChar
        case _ ~ a ~ _ => Integer.parseInt("" + a, 8).toChar
      }
    | not(META) ~> any ^^ { case c => c}
    )
    lazy val ActionBlock: Parser[SemanticAction] = rule {
      loc ~ (chr('$') ~> LBRACE ~> ActionBody <~ RBRACE) <~ Spacing ^^ {
        case pos ~ code => SemanticAction(Position(pos.line, pos.column), code)
      }
    }
    lazy val ActionBody: Parser[String] = rule {
      (ActionBodyChunk).* ^^ { case chunks => chunks.mkString }
    }
    lazy val ActionBodyChunk: Parser[String] = rule {
      // Nested braces
      chr('{') ~ ActionBody ~ chr('}') ^^ { case _ ~ inner ~ _ => "{" + inner + "}" }
      // String literals in action body
    | chr('"') ~ (not(chr('"')) ~> any).* ~ chr('"') ^^ { case _ ~ cs ~ _ => "\"" + cs.mkString + "\"" }
      // Any character except } and { and "
    | (not(chr('}')) ~ not(chr('{')) ~ not(chr('"')) ~> any) ^^ { case c => c.toString }
    }
    lazy val LBRACE = chr('{') <~ Spacing
    lazy val RBRACE = chr('}') <~ Spacing
    lazy val Debug = string("Debug") <~ Spacing
    lazy val LPAREN = chr('(') <~ Spacing
    lazy val RPAREN = chr(')') <~ Spacing
    lazy val LBRACKET = chr('[') <~ Spacing
    lazy val RBRACKET = chr(']') <~ Spacing
    lazy val COMMA = chr(',') <~ Spacing
    lazy val LT = chr('<') <~ Spacing
    lazy val GT = chr('>') <~ Spacing
    lazy val COLON = chr(':') <~ Spacing
    lazy val SEMI_COLON = chr(';') <~ Spacing
    lazy val EQ = chr('=') <~ Spacing
    lazy val SLASH = chr('/') <~ Spacing
    lazy val BAR = chr('|') <~ Spacing
    lazy val AND = chr('&') <~ Spacing
    lazy val NOT = chr('!') <~ Spacing
    lazy val QUESTION = chr('?') <~ Spacing
    lazy val STAR = chr('*') <~ Spacing
    lazy val PLUS = chr('+') <~ Spacing
    lazy val OPEN = chr('(') <~ Spacing
    lazy val CLOSE = chr(')') <~ Spacing
    lazy val DOT = chr('.') <~ Spacing
    lazy val ARROW = chr('-') <~ chr('>') <~ Spacing
    lazy val Spacing = (Space | Comment).*
    lazy val Comment = (
      chr('/') ~ chr('/') ~ (not(EndOfLine) ~ any).* ~ EndOfLine
    )
    lazy val Space = chr(' ') | chr('\t') | EndOfLine
    lazy val EndOfLine = chr('\r') ~ chr('\n') | chr('\n') | chr('\r')
    lazy val EndOfFile = not(any)
  }

  /**
   * Parses a pattern from `content` and returns the `Grammar` instance, which is the parse result.
 *
   * @param fileName
   * @param content
   * @return `Grammar` instance
   */
  def parse(fileName: String, content: java.io.Reader): Grammar = {
    val input = Iterator.continually(content.read()).takeWhile(_ != -1).map(_.toChar).mkString
    ParserCore.parse(ParserCore.GRAMMAR, input) match {
      case Result.Success(node) => node
      case Result.Failure(location, message) =>
        throw ParseException(Position(location.line, location.column), message)
    }
  }

  /**
   * Parses a `pattern` and returns the `Grammar` instance, which is the parse result.
 *
   * @param pattern input string
   * @return `Grammar` instance
   */
  def parse(pattern: String): Grammar = {
    parse("", new StringReader(pattern))
  }

  def main(args: Array[String]): Unit = {
    val g = parse(args(0), new FileReader(args(0)))
    println(g)
  }
}
