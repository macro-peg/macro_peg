package com
package github
package kmizu
package macro_peg
package ir

import com.github.kmizu.macro_peg.Ast
import com.github.kmizu.macro_peg.Ast.*
import com.github.kmizu.macro_peg.{Diagnostic, DiagnosticPhase, GrammarValidator, TypeChecker, TypeError}

/** Lowers a surface [[Ast.Grammar]] into the common [[Ir.Program]].
  *
  * Pipeline (reusing existing passes verbatim):
  *  1. [[GrammarValidator.validate]] → `Diagnostic(WellFormedness)`
  *  2. [[TypeChecker]] (optional) → `Diagnostic(TypeCheck)`
  *  3. assign mangled [[Ir.RuleId]]s (ported from `ParserGenerator.buildRuleNameMap`)
  *  4. convert each `Rule.body` (`Ast.Expression => Ir.Expr`), flattening
  *     sequences/alternations and structuring labels + trailing actions.
  *
  * Higher-order (Macro PEG) rules are lowered **natively**, not inlined here:
  *  - a rule's formal parameters become [[Ir.Param]]s; references to them in the
  *    body become [[Ir.Expr.ParamRef]] (emitted as `() => Option[Any]` thunks);
  *  - a parameterized call becomes [[Ir.Expr.HoCall]];
  *  - a lambda argument becomes [[Ir.Expr.Lambda]].
  * Macro inlining (beta reduction via `MacroExpander`) is a separate concern done
  * by the caller (`ParserGenerator.generateInternal` runs `tryInlineHigherOrder`
  * with a timeout); whatever grammar reaches here is lowered as-is.
  *
  * NOTE: this first cut handles a single scannerless syntactic section
  * (`lexical = None`). The two-level scanner split (Pillar C) plugs in later.
  */
object Lowering {

  final case class LowerOptions(typeCheck: Boolean = true, validate: Boolean = true)

  def lower(
    grammar: Ast.Grammar,
    startRule: Symbol,
    opts: LowerOptions = LowerOptions()
  ): Either[Diagnostic, Ir.Program] =
    for {
      _       <- if (opts.validate) validateGrammar(grammar) else Right(())
      _       <- if (opts.typeCheck) typeCheck(grammar) else Right(())
      program <- build(grammar, startRule)
    } yield program

  // --- step 1: well-formedness (same mapping as Interpreter.fromGrammarEither) ---
  private def validateGrammar(grammar: Ast.Grammar): Either[Diagnostic, Unit] =
    GrammarValidator.validate(grammar).left.map { err =>
      Diagnostic(
        phase = DiagnosticPhase.WellFormedness,
        message = err.message,
        position = Some(err.pos),
        hint = err.hint
      )
    }

  // --- step 2: type checking ---
  private def typeCheck(grammar: Ast.Grammar): Either[Diagnostic, Unit] =
    new TypeChecker(grammar).check() match {
      case Left(TypeError(pos, msg)) =>
        Left(Diagnostic(
          phase = DiagnosticPhase.TypeCheck,
          message = msg,
          position = Some(pos),
          hint = Some("fix type annotations or function arguments")
        ))
      case Right(_) => Right(())
    }

  // --- step 3 + 4: build the IR program ---
  private def build(grammar: Ast.Grammar, startRule: Symbol): Either[Diagnostic, Ir.Program] = {
    val nameMap = buildRuleNameMap(grammar.rules)
    val ruleArity: Map[Symbol, Int] = grammar.rules.map(r => r.name -> r.args.length).toMap

    def ruleId(name: Symbol): Either[Diagnostic, Ir.RuleId] =
      nameMap.get(name) match {
        case Some(mangled) => Right(Ir.RuleId(name.name, mangled, Ir.Level.Syntactic))
        case None          => Left(Diagnostic(DiagnosticPhase.Generation, s"unknown rule `${name.name}`"))
      }

    val loweredRules: Either[Diagnostic, Vector[Ir.IrRule]] =
      grammar.rules.foldLeft[Either[Diagnostic, Vector[Ir.IrRule]]](Right(Vector.empty)) { (acc, rule) =>
        val params: Map[Symbol, Int] = rule.args.zipWithIndex.toMap
        val ctx = LowerCtx(nameMap, ruleArity, params)
        for {
          rs   <- acc
          id   <- ruleId(rule.name)
          body <- lowerExpr(rule.body, ctx)
        } yield rs :+ Ir.IrRule(
          id = id,
          params = rule.args.zipWithIndex.map { case (a, i) =>
            Ir.Param(a.name, irTypeOf(rule.argTypes.lift(i).flatten))
          }.toVector,
          body = body,
          resultType = Ir.IrType.TUnknown
        )
      }

    for {
      rules <- loweredRules
      start <- ruleId(startRule)
    } yield Ir.Program(
      lexical = None,
      syntactic = Ir.Section(Ir.Level.Syntactic, rules),
      preamble = grammar.preamble,
      startRule = start,
      needsInterpreter = false
    )
  }

  private final case class LowerCtx(
    nameMap: Map[Symbol, String],
    ruleArity: Map[Symbol, Int],
    params: Map[Symbol, Int]
  )

  private def irTypeOf(t: Option[Ast.Type]): Ir.IrType = t match {
    case None                       => Ir.IrType.TUnknown
    case Some(SimpleType(_))        => Ir.IrType.TUnknown
    case Some(RuleType(_, ps, res)) =>
      Ir.IrType.TFun(ps.map(p => irTypeOf(Some(p))).toVector, irTypeOf(Some(res)))
  }

  // Flatten a Sequence tree into (label, expr) parts; mirrors
  // ParserGenerator.generateRecursiveDescentBackend.flattenParts so emitter
  // behavior is preserved. A trailing SemanticAction is stripped here and
  // surfaced separately via lowerExpr's Seq handling.
  private def flattenParts(expr: Expression): List[(Option[String], Expression)] = expr match {
    case Sequence(_, l, _: SemanticAction) => flattenParts(l)
    case Sequence(_, l, r)                 => flattenParts(l) ++ flattenParts(r)
    case Labeled(_, label, body)           => List((Some(label), body))
    case other                             => List((None, other))  // Cut stays a part → lowered to Ir.Expr.Cut
  }

  private def trailingAction(expr: Expression): Option[SemanticAction] = expr match {
    case Sequence(_, _, sa: SemanticAction) => Some(sa)
    case _                                  => None
  }

  private def flattenAlt(expr: Expression): List[Expression] = expr match {
    case Alternation(_, l, r) => flattenAlt(l) ++ flattenAlt(r)
    case other                => List(other)
  }

  private def lowerExpr(exp: Expression, ctx: LowerCtx): Either[Diagnostic, Ir.Expr] = exp match {
    case s @ Sequence(_, _, _) =>
      val action = trailingAction(s)
      val parts  = flattenParts(s)
      val loweredParts =
        parts.foldLeft[Either[Diagnostic, Vector[Ir.LabeledExpr]]](Right(Vector.empty)) { (acc, p) =>
          val (lbl, e) = p
          for { ps <- acc; le <- lowerExpr(e, ctx) } yield ps :+ Ir.LabeledExpr(lbl, le)
        }
      loweredParts.map { lp =>
        val actionRef = action.map { sa =>
          val boundLabels = lp.flatMap(_.label).map(l => (l, Ir.IrType.TUnknown)).toVector
          Ir.ActionRef(sa.code, sa.pos, boundLabels)
        }
        Ir.Expr.Seq(lp, actionRef)
      }

    case a @ Alternation(_, _, _) =>
      flattenAlt(a).foldLeft[Either[Diagnostic, Vector[Ir.Expr]]](Right(Vector.empty)) { (acc, e) =>
        for { bs <- acc; le <- lowerExpr(e, ctx) } yield bs :+ le
      }.map(bs => Ir.Expr.Alt(bs))

    case Repeat0(_, b)      => lowerExpr(b, ctx).map(Ir.Expr.Rep(_, oneOrMore = false))
    case Repeat1(_, b)      => lowerExpr(b, ctx).map(Ir.Expr.Rep(_, oneOrMore = true))
    case Optional(_, b)     => lowerExpr(b, ctx).map(Ir.Expr.Opt(_))
    case AndPredicate(_, b) => lowerExpr(b, ctx).map(Ir.Expr.And(_))
    case NotPredicate(_, b) => lowerExpr(b, ctx).map(Ir.Expr.Not(_))

    case StringLiteral(_, s) => Right(Ir.Expr.Str(s))
    case Wildcard(_)         => Right(Ir.Expr.AnyChar)

    case CharClass(_, positive, elems) =>
      val ranges  = elems.collect { case CharRange(f, t) => (f, t) }.toVector
      val singles = elems.collect { case OneChar(c) => c }.toSet
      Right(Ir.Expr.Chars(positive, ranges, singles))

    case CharSet(_, positive, elems) =>
      Right(Ir.Expr.Chars(positive, Vector.empty, elems))

    case Identifier(pos, name) =>
      ctx.params.get(name) match {
        case Some(i) => Right(Ir.Expr.ParamRef(i, name.name))
        case None =>
          ctx.nameMap.get(name) match {
            case None => Left(Diagnostic(DiagnosticPhase.Generation, s"unknown identifier `${name.name}`", position = Some(pos)))
            case Some(mangled) =>
              if (ctx.ruleArity.getOrElse(name, 0) > 0)
                Left(Diagnostic(DiagnosticPhase.Generation, s"higher-order rule `${name.name}` used without arguments", position = Some(pos)))
              else
                Right(Ir.Expr.RuleRef(Ir.RuleId(name.name, mangled, Ir.Level.Syntactic)))
          }
      }

    case Call(pos, name, args) =>
      ctx.params.get(name) match {
        case Some(i) =>
          // a lambda parameter used as a 0-arg callee (emit元: pvar())
          Right(Ir.Expr.ParamRef(i, name.name))
        case None =>
          ctx.nameMap.get(name) match {
            case None => Left(Diagnostic(DiagnosticPhase.Generation, s"unknown call target `${name.name}`", position = Some(pos)))
            case Some(mangled) =>
              val id    = Ir.RuleId(name.name, mangled, Ir.Level.Syntactic)
              val arity = ctx.ruleArity.getOrElse(name, 0)
              if (args.isEmpty && arity == 0) Right(Ir.Expr.RuleRef(id))
              else
                args.foldLeft[Either[Diagnostic, Vector[Ir.Expr]]](Right(Vector.empty)) { (acc, a) =>
                  for { as <- acc; la <- lowerExpr(a, ctx) } yield as :+ la
                }.map(as => Ir.Expr.HoCall(id, as))
          }
      }

    case Function(_, ps, body) =>
      val lambdaScope = ctx.params ++ ps.zipWithIndex.toMap
      lowerExpr(body, ctx.copy(params = lambdaScope)).map(b => Ir.Expr.Lambda(ps.map(_.name).toVector, b))

    case Labeled(_, _, body) =>
      // Standalone label outside a sequence: only meaningful inside Seq+action.
      lowerExpr(body, ctx)

    case Cut(_, body)   => lowerExpr(body, ctx).map(Ir.Expr.Cut(_))
    case Debug(_, body) => lowerExpr(body, ctx).map(Ir.Expr.Debug(_))

    case SemanticAction(pos, _) =>
      Left(Diagnostic(DiagnosticPhase.Generation,
        "standalone SemanticAction not allowed outside rule action block", position = Some(pos)))

    case e @ (_: ActionBlock | _: LeftProject | _: RightProject | _: IgnoredExpr) =>
      Left(Diagnostic(DiagnosticPhase.Generation,
        "action blocks and projections are only supported by the plain Scala backend", position = Some(e.pos),
        hint = Some("add a directive or annotation so the grammar is routed to the plain Scala backend")))
  }

  // ---- ported helper (from ParserGenerator) ----

  private def buildRuleNameMap(rules: List[Rule]): Map[Symbol, String] = {
    val used = scala.collection.mutable.Set.empty[String]
    var nameMap = Map.empty[Symbol, String]
    rules.foreach { rule =>
      val base = "r_" + sanitizeIdentifier(rule.name.name)
      var candidate = base
      var index = 1
      while (used.contains(candidate)) {
        index += 1
        candidate = s"${base}_$index"
      }
      used += candidate
      nameMap += (rule.name -> candidate)
    }
    nameMap
  }

  private def sanitizeIdentifier(name: String): String = {
    val cleaned = name.map(ch => if (ch.isLetterOrDigit || ch == '_') ch else '_')
    if (cleaned.headOption.exists(_.isDigit)) "_" + cleaned else cleaned
  }
}
