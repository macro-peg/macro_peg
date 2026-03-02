package com.github.kmizu.macro_peg.codegen

import com.github.kmizu.macro_peg.Ast
import com.github.kmizu.macro_peg.Ast._
import com.github.kmizu.macro_peg._

case class GenerationError(pos: Position, message: String, hint: Option[String] = None)

object ParserGenerator {
  def generateFromSource(
    source: String,
    objectName: String = "GeneratedParser",
    packageName: Option[String] = None,
    startRule: Symbol = Symbol("S")
  ): Either[Diagnostic, String] = {
    val parsed = try {
      Right(Parser.parse(source))
    } catch {
      case Parser.ParseException(pos, msg) =>
        Left(Diagnostic(
          phase = DiagnosticPhase.Parse,
          message = msg,
          position = Some(pos),
          hint = Some("fix grammar syntax before code generation")
        ))
    }
    parsed.flatMap(generate(_, objectName, packageName, startRule))
  }

  def generate(
    grammar: Grammar,
    objectName: String = "GeneratedParser",
    packageName: Option[String] = None,
    startRule: Symbol = Symbol("S")
  ): Either[Diagnostic, String] = {
    GrammarValidator.validate(grammar).left.map { err =>
      Diagnostic(
        phase = DiagnosticPhase.WellFormedness,
        message = err.message,
        position = Some(err.pos),
        hint = err.hint
      )
    }.flatMap { _ =>
      generateInternal(grammar, objectName, packageName, startRule).left.map { err =>
        Diagnostic(
          phase = DiagnosticPhase.Generation,
          message = err.message,
          position = Some(err.pos),
          hint = err.hint
        )
      }
    }
  }

  private def generateInternal(
    grammar: Grammar,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol
  ): Either[GenerationError, String] = {
    if(!grammar.rules.exists(_.name == startRule)) {
      return Left(GenerationError(Ast.DUMMY_POSITION, s"start rule `${startRule.name}` is not defined", Some("choose an existing rule as startRule")))
    }

    val ruleNameMap = buildRuleNameMap(grammar.rules)

    grammar.rules.find(_.args.nonEmpty) match {
      case Some(r) =>
        return Left(GenerationError(r.pos, s"rule `${r.name.name}` has parameters; generator currently supports first-order rules only", Some("expand/remove macro parameters before generation")))
      case None =>
        ()
    }

    def emitExpression(exp: Expression): Either[GenerationError, String] = exp match {
      case Sequence(_, l, r) =>
        for(le <- emitExpression(l); re <- emitExpression(r)) yield s"($le ~ $re)"
      case Alternation(_, l, r) =>
        for(le <- emitExpression(l); re <- emitExpression(r)) yield s"($le / $re)"
      case Repeat0(_, b) =>
        emitExpression(b).map(be => s"($be).*")
      case Repeat1(_, b) =>
        emitExpression(b).map(be => s"($be).+")
      case Optional(_, b) =>
        emitExpression(b).map(be => s"($be).?")
      case AndPredicate(_, b) =>
        emitExpression(b).map(be => s"($be).and")
      case NotPredicate(_, b) =>
        emitExpression(b).map(be => s"!($be)")
      case StringLiteral(_, target) =>
        Right("\"" + escapeString(target) + "\".s")
      case Wildcard(_) =>
        Right("any")
      case CharClass(_, positive, elems) =>
        val encodedElems = elems.map {
          case CharRange(from, to) => s"${charLiteral(from)} to ${charLiteral(to)}"
          case OneChar(ch) => s"Seq(${charLiteral(ch)})"
        }.mkString(", ")
        if(positive) Right(s"range($encodedElems)")
        else Right(s"notIn(range($encodedElems))")
      case CharSet(_, positive, elems) =>
        val encodedElems = elems.toList.sorted.map(ch => s"Seq(${charLiteral(ch)})").mkString(", ")
        if(positive) Right(s"range($encodedElems)")
        else Right(s"notIn(range($encodedElems))")
      case Identifier(pos, name) =>
        ruleNameMap.get(name) match {
          case Some(scalaName) => Right(s"refer($scalaName)")
          case None => Left(GenerationError(pos, s"unknown identifier `${name.name}` during generation"))
        }
      case Call(pos, name, args) =>
        if(args.nonEmpty) {
          Left(GenerationError(pos, s"call `${name.name}` has arguments; generator currently supports first-order rules only", Some("expand/remove macro arguments before generation")))
        } else {
          ruleNameMap.get(name) match {
            case Some(scalaName) => Right(s"refer($scalaName)")
            case None => Left(GenerationError(pos, s"unknown call target `${name.name}` during generation"))
          }
        }
      case Function(pos, _, _) =>
        Left(GenerationError(pos, "lambda/function expression is not supported by parser generator", Some("use Interpreter/Evaluator for higher-order grammar execution")))
      case Debug(_, b) =>
        emitExpression(b).map(be => s"($be).display")
    }

    val emittedRules = grammar.rules.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) { (acc, rule) =>
      for {
        lines <- acc
        body <- emitExpression(rule.body)
      } yield lines :+ s"  lazy val ${ruleNameMap(rule.name)}: P[Any] = $body"
    }

    emittedRules.map { lines =>
      val packagePrefix = packageName.map(p => s"package $p\n\n").getOrElse("")
      val startRuleName = ruleNameMap(startRule)
      val ruleDefs = lines.mkString("\n")
      s"""${packagePrefix}import com.github.kmizu.macro_peg.combinator.MacroParsers._
         |
         |object $objectName {
         |  private def notIn[T](p: P[T]): P[String] =
         |    (!p ~ any).map { case _ ~ ch => ch }
         |
         |$ruleDefs
         |
         |  lazy val Start: P[Any] = refer($startRuleName) ~ !any
         |
         |  def parse(input: String): ParseResult[Any] =
         |    Start(input)
         |
         |  def parseAll(input: String): Either[String, Any] =
         |    com.github.kmizu.macro_peg.combinator.MacroParsers.parseAll(refer($startRuleName), input)
         |      .left.map(f => com.github.kmizu.macro_peg.combinator.MacroParsers.formatFailure(input, f))
         |}
         |""".stripMargin
    }
  }

  private def buildRuleNameMap(rules: List[Rule]): Map[Symbol, String] = {
    val used = scala.collection.mutable.Set.empty[String]
    var nameMap = Map.empty[Symbol, String]

    rules.foreach { rule =>
      val base = "r_" + sanitizeIdentifier(rule.name.name)
      var candidate = base
      var index = 1
      while(used.contains(candidate)) {
        index += 1
        candidate = s"${base}_$index"
      }
      used += candidate
      nameMap += (rule.name -> candidate)
    }
    nameMap
  }

  private def sanitizeIdentifier(name: String): String = {
    val cleaned = name.map { ch =>
      if(ch.isLetterOrDigit || ch == '_') ch else '_'
    }
    if(cleaned.headOption.exists(_.isDigit)) "_" + cleaned else cleaned
  }

  private def charLiteral(ch: Char): String = ch match {
    case '\n' => "'\\n'"
    case '\r' => "'\\r'"
    case '\t' => "'\\t'"
    case '\'' => "'\\''"
    case '\\' => "'\\\\'"
    case c if c.isControl => "'\\u%04x'".format(c.toInt)
    case c => "'" + c + "'"
  }

  private def escapeString(raw: String): String = {
    val builder = new StringBuilder
    raw.foreach {
      case '"' => builder.append("\\\"")
      case '\\' => builder.append("\\\\")
      case '\n' => builder.append("\\n")
      case '\r' => builder.append("\\r")
      case '\t' => builder.append("\\t")
      case c if c.isControl => builder.append("\\u%04x".format(c.toInt))
      case c => builder.append(c)
    }
    builder.toString()
  }
}
