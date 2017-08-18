package com
package github
package kmizu
package macro_peg

import scala.util.parsing.combinator._
import scala.util.parsing.input.{CharSequenceReader, StreamReader}
import scala.util.parsing.input.Position
import java.io._
import Ast._
/**
  * This object provides a parser that parses strings in Macro PEG and translates
  * them into ASTs of Macro PEG (which is like PEGs).
  * @author Kota Mizushima
  *
  */
object MacroPEGParser {

  /**
   * This exception is thrown in the case of a parsing failure
 *
   * @param pos the position where the parsing failed
   * @param msg error message
   */
  case class ParseException(pos: Ast.Position, msg: String) extends Exception(pos.line + ", " + pos.column + ":" + msg)
  
  private object ParserCore extends Parsers {
    type Elem = Char
    private val any: Parser[Char] = elem(".", c => c != CharSequenceReader.EofCh)
    private def chr(c: Char): Parser[Char] = c
    private def string(s: String): Parser[String] = s.foldLeft(success("")){(p, e) => p ~ e ^^ {case x ~ y => x + y }}
    private def crange(f: Char, t: Char): Parser[Char] = elem("[]", c => f <= c && c <= t)
    private def cset(cs: Char*): Parser[Char] = elem("[]", c => cs.indexWhere(_ == c) >= 0)
    private val escape: Map[Char, Char] = Map(
      'n' -> '\n', 'r' -> '\r', 't' -> '\t', 'f' -> '\f'
    )
    private def not[T](p: => Parser[T], msg: String): Parser[Unit] = {
      not(p) | failure(msg)
    }
    lazy val GRAMMAR: Parser[Grammar] = (loc <~ Spacing) ~ Definition.* <~ EndOfFile ^^ {
      case pos ~ rules => Grammar(Position(pos.line, pos.column), rules)
    }

    lazy val Definition: Parser[Rule] = Ident  ~ ((LPAREN ~> rep1sep(Arg, COMMA) <~ RPAREN).? <~ EQ) ~! (Expression <~ SEMI_COLON) ^^ {
      case name ~ argsOpt ~ body =>
        Rule(name.pos, name.name, body, argsOpt.getOrElse(List()).map(_._1.name))
    }

    lazy val Arg: Parser[(Identifier, Option[Type])] = Ident ~ (COLON ~> TypeTree).? ^^ { case id ~ tpe => (id, tpe)}

    lazy val TypeTree: Parser[Type] = {
      RuleTypeTree | SimpleTypeTree
    }

    lazy val RuleTypeTree: Parser[RuleType] = {
      (OPEN ~> (rep1sep(SimpleTypeTree, COMMA) <~ CLOSE) ~ (loc <~ ARROW) ~ SimpleTypeTree) ^^ { case paramTypes ~ pos ~ resultType => RuleType(Position(pos.line, pos.column), paramTypes, resultType) }
    }

    lazy val SimpleTypeTree: Parser[SimpleType] = {
      loc <~ QUESTION ^^ { case pos => SimpleType(Position(pos.line, pos.column)) }
    }
    
    lazy val Expression: Parser[Expression] = rep1sep(Sequencable, SLASH | BAR) ^^ { ns =>
      val x :: xs = ns; xs.foldLeft(x){(a, y) => Alternation(y.pos, a, y)}
    }
    lazy val Sequencable: Parser[Expression]   = Prefix.+ ^^ { ns =>
      val x :: xs = ns; xs.foldLeft(x){(a, y) => Sequence(y.pos, a, y)}
    }
    lazy val Prefix: Parser[Expression]     = (
      (loc <~ AND) ~ Suffix ^^ { case pos ~ e => AndPredicate(Position(pos.line, pos.column), e) }
    | (loc <~ NOT) ~ Suffix ^^ { case pos ~ e => NotPredicate(Position(pos.line, pos.column), e) }
    | Suffix
    )
    lazy val Suffix: Parser[Expression]     = (
      loc ~ Primary <~ QUESTION ^^ { case pos ~ e => Optional(Position(pos.line, pos.column), e) }
    | loc ~ Primary <~ STAR ^^ { case pos ~ e => Repeat0(Position(pos.line, pos.column), e) }
    | loc ~ Primary <~ PLUS ^^ { case pos ~ e => Repeat1(Position(pos.line, pos.column), e) }
    | Primary
    )
    lazy val Primary: Parser[Expression]    = (
      (loc <~ Debug) ~ (LPAREN ~> Expression <~ RPAREN) ^^ { case loc ~ body => Ast.Debug(Position(loc.line, loc.column), body)}
    | IdentifierWithoutSpace ~ (LPAREN ~> repsep(Expression, COMMA) <~ RPAREN) ^^ { case name ~ params => Call(Position(name.pos.line, name.pos.column), name.name, params) }
    | Ident
    | CLASS
    | (OPEN ~> (repsep(Ident, COMMA) ~ (loc <~ ARROW) ~ Expression) <~ CLOSE) ^^ { case ids ~ loc ~ body => Function(Position(loc.line, loc.column), ids.map(_.name), body) }
    | OPEN ~> Expression <~ CLOSE
    | loc <~ DOT ^^ { case pos => Wildcard(Position(pos.line, pos.column)) }
    | loc <~ chr('_') ^^ { case pos => StringLiteral(Position(pos.line, pos.column), "") }
    | Literal
    )
    lazy val loc: Parser[Position] = Parser{reader => Success(reader.pos, reader)}
    lazy val IdentifierWithoutSpace: Parser[Identifier] = loc ~ IdentStart ~ IdentCont.* ^^ {
      case pos ~ s ~ c => Identifier(Position(pos.line, pos.column), Symbol("" + s + c.foldLeft("")(_ + _)))
    }
    lazy val Ident: Parser[Identifier] = IdentifierWithoutSpace <~ Spacing
    lazy val IdentStart: Parser[Char] = crange('a','z') | crange('A','Z') | '_'
    lazy val IdentCont: Parser[Char] = IdentStart | crange('0','9')
    lazy val Literal: Parser[StringLiteral] = loc ~ (chr('\"') ~> CHAR.* <~ chr('\"')) <~ Spacing ^^ {
      case pos ~ cs => StringLiteral(Position(pos.line, pos.column), cs.mkString)
    }
    lazy val CLASS: Parser[CharClass] = {
      (loc <~ chr('[')) ~ opt(chr('^')) ~ ((not(chr(']')) ~> Range).* <~ ']' ~> Spacing) ^^ {
        //negative character class
        case (pos ~ Some(_) ~ rs) => CharClass(Position(pos.line, pos.column), false, rs)
        //positive character class
        case (pos ~ None ~ rs) => CharClass(Position(pos.line, pos.column), true, rs)
      }
    }
    lazy val Range: Parser[CharClassElement] = (
      CHAR ~ '-' ~ CHAR ^^ { case f~_~t => CharRange(f, t) }
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
    | chr('\\') ~ crange('0','7') ~ opt(crange('0','7')) ^^ {
        case _ ~ a ~ Some(b) => Integer.parseInt("" + a + b, 8).toChar
        case _ ~ a ~ _ => Integer.parseInt("" + a, 8).toChar
      }
    | not(META, " meta character " + META_CHARS.mkString("[",",","]") + " is not expected") ~>  any ^^ { case c => c}
    )
    lazy val Debug = string("Debug") <~ Spacing
    lazy val LPAREN = chr('(') <~ Spacing
    lazy val RPAREN = chr(')') <~ Spacing
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
    lazy val EndOfFile = not(any, "EOF Expected")
  }

  /**
   * Parses a pattern from `content` and returns the `Grammar` instance, which is the parse result.
 *
   * @param fileName
   * @param content
   * @return `Grammar` instance
   */
  def parse(fileName: String, content: java.io.Reader): Grammar = {
    ParserCore.GRAMMAR(StreamReader(content)) match {
      case ParserCore.Success(node, _) => node
      case ParserCore.Failure(msg, rest) =>
        val pos = rest.pos
        throw new ParseException(Position(pos.line, pos.column), msg)
      case ParserCore.Error(msg, rest) =>
        val pos = rest.pos
        throw new ParseException(Position(pos.line, pos.column), msg)
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

  def main(args: Array[String]) {
    val g = parse(args(0), new FileReader(args(0)))
    println(g)
  }
}
