package com.github.kmizu.macro_peg.combinator

import scala.collection.mutable.ListBuffer

object MacroParsers {
  type Input = String
  case class ~[+A, +B](_1: A, _2: B)

  abstract sealed class ParseResult[+T] {
    /**
      * Drop extra information from this ParseResult.
      * Mainly this is used by tests that focus on acceptance/rejection only.
      */
    def drop: ParseResult[Any] = this match {
      case ParseSuccess(_, next) => ParseSuccess(None, next)
      case ParseFailure(_, next, _, _, _) => ParseFailure("", next)
    }
  }

  final case class ParseSuccess[+T](result: T, next: Input) extends ParseResult[T]

  abstract sealed class AbstractFailure extends ParseResult[Nothing] {
    val message: String
  }

  final case class ParseFailure(
    override val message: String,
    next: Input,
    committed: Boolean = false,
    expected: List[String] = Nil,
    ruleStack: List[String] = Nil
  ) extends AbstractFailure {
    def addTrace(label: String): ParseFailure = copy(ruleStack = label :: ruleStack)
    def addExpected(label: String): ParseFailure =
      if(expected.contains(label)) this else copy(expected = expected :+ label)
  }

  object ParseFailure {
    def apply(message: String, next: Input): ParseFailure =
      new ParseFailure(message, next, committed = false, expected = Nil, ruleStack = Nil)
  }

  abstract sealed class MacroParser[+T] { self =>
    def apply(input: Input): ParseResult[T]
    def |[U >: T](b: MacroParser[U]): MacroParser[U] = Alternation(this, b)
    def /[U >: T](b: MacroParser[U]): MacroParser[U] = this | b
    def ~[U](b: MacroParser[U]): MacroParser[T ~ U] = Sequence(this, b)
    def <~[U](b: MacroParser[U]): MacroParser[T] = (this ~ b).map(_._1)
    def ~>[U](b: MacroParser[U]): MacroParser[U] = (this ~ b).map(_._2)
    def ? : MacroParser[Option[T]] = OptionParser(this)
    def * : MacroParser[List[T]] = RepeatParser(this)
    def + : MacroParser[List[T]] = Repeat1Parser(this)
    def unary_! : MacroParser[Any] = NotParser(this)
    def and: MacroParser[Any] = AndParser(this)
    def evalCC[U](cc: MacroParser[T] => MacroParser[U]): MacroParser[U] = EvalCC(this, cc)
    def map[U](function: T => U): MacroParser[U] = MappingParser(this, function)
    def as[U](value: => U): MacroParser[U] = map(_ => value)
    def void: MacroParser[Unit] = as(())
    def display: MacroParser[T] = new MacroParser[T] {
      override def apply(input: Input): ParseResult[T] = {
        println("input: " + input)
        self(input)
      }
    }

    /** Replace failure expectation with a user-friendly label. */
    def label(name: String): MacroParser[T] = LabelParser(this, name)

    /** Commit this parse point; alternation won't try the next branch on failure. */
    def cut: MacroParser[T] = CutParser(this)

    /** Recover from a non-committed failure by trying `fallback` from the same input. */
    def recover[U >: T](fallback: => MacroParser[U]): MacroParser[U] =
      RecoverParser(this, () => fallback)

    /** Push trace label to failures for debugging nested parser calls. */
    def trace(name: String): MacroParser[T] = TraceParser(name, this)
  }

  def chainl[A](p: MacroParser[A])(q: MacroParser[(A, A) => A]): MacroParser[A] = {
    (p ~ (q ~ p).*).map { case x ~ xs =>
      xs.foldLeft(x) { case (a, f ~ b) =>
        f(a, b)
      }
    }
  }

  def success[A](value: A): MacroParser[A] =
    StringParser("").map(_ => value)

  def sepBy1[A, B](elem: MacroParser[A], sep: MacroParser[B]): MacroParser[List[A]] =
    (elem ~ (sep ~ elem).*).map { case head ~ tail => head :: tail.map(_._2) }

  def sepBy0[A, B](elem: MacroParser[A], sep: MacroParser[B]): MacroParser[List[A]] =
    sepBy1(elem, sep) / success(Nil)

  type P[+T] = MacroParser[T]
  def any: AnyParser.type = AnyParser
  def string(literal: String): StringParser = StringParser(literal)

  implicit class RichString(val self: String) extends AnyVal {
    def s: StringParser = StringParser(self)
  }

  def range(ranges: Seq[Char]*): RangedParser = RangedParser(ranges: _*)
  implicit def characterRangesToParser(ranges: Seq[Seq[Char]]): RangedParser = range(ranges: _*)
  def refer[T](parser: => MacroParser[T]): ReferenceParser[T] = ReferenceParser(() => parser)
  def rewritable[T](parser: MacroParser[T]): RewritableParser[T] = RewritableParser(parser)

  private def consumed(input: Input, failure: ParseFailure): Int = input.length - failure.next.length

  private def chooseFailure(input: Input, left: ParseFailure, right: ParseFailure): ParseFailure = {
    val leftConsumed = consumed(input, left)
    val rightConsumed = consumed(input, right)
    if(leftConsumed > rightConsumed) left
    else if(rightConsumed > leftConsumed) right
    else {
      ParseFailure(
        message = if(right.message.nonEmpty) right.message else left.message,
        next = right.next,
        committed = left.committed || right.committed,
        expected = (left.expected ++ right.expected).distinct,
        ruleStack = if(right.ruleStack.nonEmpty) right.ruleStack else left.ruleStack
      )
    }
  }

  def parseAll[T](parser: MacroParser[T], input: Input): Either[ParseFailure, T] = {
    (parser ~ !any)(input) match {
      case ParseSuccess(result ~ _, _) => Right(result)
      case failure: ParseFailure => Left(failure)
    }
  }

  def formatFailure(input: Input, failure: ParseFailure): String = {
    val offset = consumed(input, failure)
    val prefix = input.substring(0, math.max(0, math.min(input.length, offset)))
    val line = prefix.count(_ == '\n') + 1
    val col = prefix.reverseIterator.takeWhile(_ != '\n').length + 1
    val start = math.max(0, offset - 20)
    val end = math.min(input.length, offset + 20)
    val fragment = input.substring(start, end)
    val pointer = " " * (offset - start) + "^"
    val expected = if(failure.expected.isEmpty) "" else s"\nexpected: ${failure.expected.mkString(", ")}"
    val stack = if(failure.ruleStack.isEmpty) "" else s"\nstack: ${failure.ruleStack.reverse.mkString(" -> ")}"
    val msg = if(failure.message.isEmpty) "parse failed" else failure.message
    s"$msg at $line:$col$expected$stack\n$fragment\n$pointer"
  }

  final case class EvalCC[T, U](parser: MacroParser[T], cc: MacroParser[T] => MacroParser[U]) extends MacroParser[U] {
    override def apply(input: Input): ParseResult[U] = {
      parser(input) match {
        case ParseSuccess(value, next) =>
          cc(new StringWithValueParser(input.substring(0, input.length - next.length), value))(next)
        case failure: ParseFailure =>
          failure
      }
    }
  }

  final case class StringParser(literal: String) extends MacroParser[String] {
    override def apply(input: Input): ParseResult[String] = {
      if(input.startsWith(literal)) ParseSuccess(literal, input.substring(literal.length))
      else ParseFailure(s"expected $literal", input, expected = literal :: Nil)
    }
  }

  final case class StringWithValueParser[T](literal: String, value: T) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = {
      if(input.startsWith(literal)) ParseSuccess(value, input.substring(literal.length))
      else ParseFailure(s"expected $literal", input, expected = literal :: Nil)
    }
  }

  final case class RangedParser(ranges: Seq[Char]*) extends MacroParser[String] {
    override def apply(input: Input): ParseResult[String] = {
      if(input.isEmpty) {
        ParseFailure(
          s"found EOF, but expected one of: $ranges",
          input,
          expected = List(ranges.map(_.mkString).mkString(" | "))
        )
      } else if(ranges.exists(_.contains(input.charAt(0)))) {
        ParseSuccess(input.substring(0, 1), input.substring(1))
      } else {
        ParseFailure(
          s"found ${input.substring(0, 1)}, but expected one of: $ranges",
          input,
          expected = List(ranges.map(_.mkString).mkString(" | "))
        )
      }
    }
  }

  final case class OptionParser[T](parser: MacroParser[T]) extends MacroParser[Option[T]] {
    override def apply(input: Input): ParseResult[Option[T]] = {
      parser(input) match {
        case ParseSuccess(result, next) => ParseSuccess(Some(result), next)
        case failure@ParseFailure(_, _, true, _, _) => failure
        case ParseFailure(_, _, false, _, _) => ParseSuccess(None, input)
      }
    }
  }

  final case class MappingParser[T, U](parser: MacroParser[T], function: T => U) extends MacroParser[U] {
    override def apply(input: Input): ParseResult[U] = {
      parser(input) match {
        case ParseSuccess(result, next) => ParseSuccess(function(result), next)
        case failure: ParseFailure => failure
      }
    }
  }

  final case class RepeatParser[T](parser: MacroParser[T]) extends MacroParser[List[T]] {
    override def apply(input: Input): ParseResult[List[T]] = {
      var rest = input
      val total = ListBuffer[T]()
      while(true) {
        parser(rest) match {
          case ParseSuccess(result, next) =>
            if(next == rest) {
              return ParseFailure(
                "repetition parser consumed no input",
                rest,
                committed = true,
                expected = hintLabel("parser under * must consume input")
              )
            }
            total += result
            rest = next
          case failure@ParseFailure(_, _, true, _, _) =>
            return failure
          case ParseFailure(_, _, false, _, _) =>
            return ParseSuccess(total.toList, rest)
        }
      }
      throw new RuntimeException("unreachable code")
    }
  }

  private def hintLabel(label: String): List[String] = List(label)

  final case class Repeat1Parser[T](parser: MacroParser[T]) extends MacroParser[List[T]] {
    override def apply(input: Input): ParseResult[List[T]] = {
      var rest = input
      val total = ListBuffer[T]()
      parser(rest) match {
        case ParseSuccess(result, next) =>
          if(next == rest) {
            return ParseFailure(
              "repetition parser consumed no input",
              rest,
              committed = true,
              expected = hintLabel("parser under + must consume input")
            )
          }
          total += result
          rest = next
          while(true) {
            parser(rest) match {
              case ParseSuccess(result2, next2) =>
                if(next2 == rest) {
                  return ParseFailure(
                    "repetition parser consumed no input",
                    rest,
                    committed = true,
                    expected = hintLabel("parser under + must consume input")
                  )
                }
                total += result2
                rest = next2
              case failure@ParseFailure(_, _, true, _, _) =>
                return failure
              case ParseFailure(_, _, false, _, _) =>
                return ParseSuccess(total.toList, rest)
            }
          }
          throw new RuntimeException("unreachable code")
        case failure: ParseFailure =>
          failure
      }
    }
  }

  final case class Alternation[T, U >: T](a: MacroParser[T], b: MacroParser[U]) extends MacroParser[U] {
    override def apply(input: Input): ParseResult[U] = {
      a(input) match {
        case success: ParseSuccess[T] => success
        case failure@ParseFailure(_, _, true, _, _) => failure
        case leftFailure: ParseFailure =>
          b(input) match {
            case success: ParseSuccess[U] => success
            case rightFailure: ParseFailure => chooseFailure(input, leftFailure, rightFailure)
          }
      }
    }
  }

  final case class Sequence[T, U](a: MacroParser[T], b: MacroParser[U]) extends MacroParser[T ~ U] {
    override def apply(input: Input): ParseResult[T ~ U] = a(input) match {
      case ParseSuccess(result1, next1) =>
        b(next1) match {
          case ParseSuccess(result2, next2) => ParseSuccess(new ~(result1, result2), next2)
          case failure: ParseFailure => failure
        }
      case failure: ParseFailure => failure
    }
  }

  final case class ReferenceParser[T](delayedParser: () => MacroParser[T]) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = reference(input)
    lazy val reference: MacroParser[T] = delayedParser()
  }

  case object AnyParser extends MacroParser[String] {
    override def apply(input: Input): ParseResult[String] = {
      if(input.length >= 1) ParseSuccess(input.substring(0, 1), input.substring(1))
      else ParseFailure("EOF", input, expected = List("any character"))
    }
  }

  final case class AndParser[T](p: MacroParser[T]) extends MacroParser[Any] {
    override def apply(input: Input): ParseResult[Any] = p(input) match {
      case ParseSuccess(_, _) => ParseSuccess((), input)
      case ParseFailure(message, _, committed, expected, stack) =>
        ParseFailure(message, input, committed, expected, stack)
    }
  }

  final case class NotParser[T](p: MacroParser[T]) extends MacroParser[Any] {
    override def apply(input: Input): ParseResult[Any] = p(input) match {
      case ParseSuccess(_, _) => ParseFailure("negative lookahead failed", input)
      case ParseFailure(_, _, _, _, _) => ParseSuccess("", input)
    }
  }

  final case class RewritableParser[T](var p: MacroParser[T]) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = p(input)
  }

  final case class LabelParser[T](parser: MacroParser[T], name: String) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = parser(input) match {
      case success: ParseSuccess[T] => success
      case failure: ParseFailure =>
        failure.copy(
          message = if(failure.message.nonEmpty) failure.message else s"expected $name",
          expected = List(name)
        )
    }
  }

  final case class CutParser[T](parser: MacroParser[T]) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = parser(input) match {
      case success: ParseSuccess[T] => success
      case failure: ParseFailure => failure.copy(committed = true)
    }
  }

  final case class RecoverParser[T](parser: MacroParser[T], fallback: () => MacroParser[T]) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = parser(input) match {
      case success: ParseSuccess[T] => success
      case failure@ParseFailure(_, _, true, _, _) => failure
      case _: ParseFailure => fallback()(input)
    }
  }

  final case class TraceParser[T](name: String, parser: MacroParser[T]) extends MacroParser[T] {
    override def apply(input: Input): ParseResult[T] = parser(input) match {
      case success: ParseSuccess[T] => success
      case failure: ParseFailure => failure.addTrace(name)
    }
  }
}
