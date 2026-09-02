package com.github.kmizu.macro_peg.codegen

import com.github.kmizu.macro_peg.Ast
import com.github.kmizu.macro_peg.Ast._
import com.github.kmizu.macro_peg._
import com.github.kmizu.macro_peg.ir.{Ir, Lowering}

case class GenerationError(pos: Position, message: String, hint: Option[String] = None)

sealed trait Backend
object Backend {
  case object Combinator extends Backend
  case object RecursiveDescent extends Backend
}

object ParserGenerator {
  def generateFromSource(
    source: String,
    objectName: String = "GeneratedParser",
    packageName: Option[String] = None,
    startRule: Symbol = Symbol("S"),
    backend: Backend = Backend.Combinator
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
    parsed.flatMap(grammar => generateWithSource(grammar, source, objectName, packageName, startRule, backend))
  }

  def generate(
    grammar: Grammar,
    objectName: String = "GeneratedParser",
    packageName: Option[String] = None,
    startRule: Symbol = Symbol("S"),
    backend: Backend = Backend.Combinator
  ): Either[Diagnostic, String] = {
    val source = renderGrammar(grammar)
    generateWithSource(grammar, source, objectName, packageName, startRule, backend)
  }

  private def generateWithSource(
    grammar: Grammar,
    source: String,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol,
    backend: Backend
  ): Either[Diagnostic, String] = {
    GrammarValidator.validate(grammar).left.map { err =>
      Diagnostic(
        phase = DiagnosticPhase.WellFormedness,
        message = err.message,
        position = Some(err.pos),
        hint = err.hint
      )
    }.flatMap { _ =>
      generateInternal(grammar, source, objectName, packageName, startRule, backend).left.map { err =>
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
    source: String,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol,
    backend: Backend
  ): Either[GenerationError, String] = {
    val effectiveStart = grammar.directives.collectFirst { case StartDirective(r) => r }.getOrElse(startRule)
    val rawRuleNamesSet = grammar.directives.collect { case RawRuleDirective(n, _) => n }.toSet
    if(!grammar.rules.exists(_.name == effectiveStart) && !rawRuleNamesSet(effectiveStart.name)) {
      return Left(GenerationError(Ast.DUMMY_POSITION, s"start rule `${effectiveStart.name}` is not defined", Some("choose an existing rule as startRule")))
    }

    if(usesNewFormat(grammar)) {
      generatePlainScalaBackend(grammar, objectName, packageName, effectiveStart)
    } else backend match {
      case Backend.RecursiveDescent =>
        // Try to inline higher-order macros first; if that fails, handle them natively via lambda params
        val grammarToUse = tryInlineHigherOrder(grammar, effectiveStart) match {
          case Some(inlined) => inlined
          case None          => grammar  // fall back to original, higher-order rules handled as lambda params
        }
        generateRecursiveDescentBackend(grammarToUse, objectName, packageName, effectiveStart)

      case Backend.Combinator =>
        if(isFirstOrder(grammar)) {
          generateCombinatorBackend(grammar, objectName, packageName, effectiveStart)
        } else {
          // Attempt aggressive inlining of higher-order macros (like compiler inlining).
          // MacroExpander beta-reduces all Call sites, producing a first-order grammar.
          tryInlineHigherOrder(grammar, effectiveStart) match {
            case Some(inlined) =>
              generateCombinatorBackend(inlined, objectName, packageName, effectiveStart)
            case None =>
              Right(generateInterpreterBackend(source, objectName, packageName, effectiveStart))
          }
        }
    }
  }

  private def usesNewFormat(grammar: Grammar): Boolean =
    grammar.directives.nonEmpty ||
    grammar.rules.exists(r =>
      r.annotations.nonEmpty ||
      r.returnType.isDefined ||
      containsNewFeatures(r.body)
    )

  private def containsNewFeatures(exp: Expression): Boolean = exp match {
    case ActionBlock(_, _, _) => true
    case LeftProject(_, l, r) => containsNewFeatures(l) || containsNewFeatures(r)
    case RightProject(_, l, r) => containsNewFeatures(l) || containsNewFeatures(r)
    case Sequence(_, l, r) => containsNewFeatures(l) || containsNewFeatures(r)
    case Alternation(_, l, r) => containsNewFeatures(l) || containsNewFeatures(r)
    case Repeat0(_, b) => containsNewFeatures(b)
    case Repeat1(_, b) => containsNewFeatures(b)
    case Optional(_, b) => containsNewFeatures(b)
    case AndPredicate(_, b) => containsNewFeatures(b)
    case NotPredicate(_, b) => containsNewFeatures(b)
    case Debug(_, b) => containsNewFeatures(b)
    case Function(_, _, body) => containsNewFeatures(body)
    case Call(_, _, args) => args.exists(containsNewFeatures)
    case IgnoredExpr(_, e) => containsNewFeatures(e)
    case _ => false
  }

  private def generatePlainScalaBackend(
    grammar: Grammar,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol
  ): Either[GenerationError, String] = {

    val ruleMap: Map[Symbol, Rule] = grammar.rules.map(r => r.name -> r).toMap
    val higherOrderSet: Set[Symbol] = grammar.rules.filter(_.args.nonEmpty).map(_.name).toSet
    val memoRuleList: List[Symbol] = grammar.rules.filter(_.annotations.exists(_.isInstanceOf[MemoAnnotation])).map(_.name)
    val memoIdMap: Map[Symbol, Int] = memoRuleList.zipWithIndex.toMap

    var counter = 0
    def fresh(prefix: String): String = { counter += 1; s"_${prefix}${counter}" }

    def parseMethodName(sym: Symbol): String = {
      val n = sym.name
      if (n.isEmpty) "parse" else s"parse${n.head.toUpper}${n.tail}"
    }

    def charLiteralS(ch: Char): String = ch match {
      case '\'' => "'\\''"
      case '\\' => "'\\\\'"
      case '\n' => "'\\n'"
      case '\r' => "'\\r'"
      case '\t' => "'\\t'"
      case c if c < 32 || c > 126 => s"'\\u${"%04x".format(c.toInt)}'"
      case c => s"'$c'"
    }

    def charClassPred(elems: List[CharClassElement]): String =
      elems.map {
        case CharRange(f, t) => s"(_c >= ${charLiteralS(f)} && _c <= ${charLiteralS(t)})"
        case OneChar(ch)     => s"(_c == ${charLiteralS(ch)})"
      }.mkString(" || ")

    def substitute(exp: Expression, env: Map[Symbol, Expression]): Expression = {
      if (env.isEmpty) return exp
      exp match {
        case Sequence(p, l, r)      => Sequence(p, substitute(l, env), substitute(r, env))
        case Alternation(p, l, r)   => Alternation(p, substitute(l, env), substitute(r, env))
        case Repeat0(p, b)          => Repeat0(p, substitute(b, env))
        case Repeat1(p, b)          => Repeat1(p, substitute(b, env))
        case Optional(p, b)         => Optional(p, substitute(b, env))
        case AndPredicate(p, b)     => AndPredicate(p, substitute(b, env))
        case NotPredicate(p, b)     => NotPredicate(p, substitute(b, env))
        case ActionBlock(p, b, c)   => ActionBlock(p, substitute(b, env), c)
        case LeftProject(p, l, r)   => LeftProject(p, substitute(l, env), substitute(r, env))
        case RightProject(p, l, r)  => RightProject(p, substitute(l, env), substitute(r, env))
        case IgnoredExpr(p, e)      => IgnoredExpr(p, substitute(e, env))
        case Debug(p, b)            => Debug(p, substitute(b, env))
        case Identifier(_, name)    => env.getOrElse(name, exp)
        case Call(pos, name, args)  =>
          val sArgs = args.map(a => substitute(a, env))
          env.get(name) match {
            case Some(Function(_, params, body)) if params.length == sArgs.length =>
              substitute(body, params.zip(sArgs).toMap)
            case Some(other) if sArgs.isEmpty => other
            case _                            => Call(pos, name, sArgs)
          }
        case Function(p, params, body) =>
          Function(p, params, substitute(body, env -- params.toSet))
        case other => other
      }
    }

    def genExpr(exp: Expression, pos: String): String = exp match {
      case StringLiteral(_, s) =>
        val esc = escapeString(s)
        val label = esc.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""(if (input.startsWith("$esc", $pos)) Some(("$esc", $pos + ${s.length})) else _fail($pos, "\\"$label\\""))"""

      case Wildcard(_) =>
        s"(if ($pos < input.length) Some((input.charAt($pos).toString, $pos + 1)) else _fail($pos, \"any character\"))"

      case CharClass(_, positive, elems) =>
        val pred = charClassPred(elems)
        val check = if (positive) pred else s"!($pred)"
        val desc = (if (positive) "" else "^") + elems.map {
          case OneChar(c) => c.toString
          case CharRange(lo, hi) => s"$lo-$hi"
        }.mkString
        val descEsc = desc.replace("\\", "\\\\").replace("\"", "\\\"")
        s"(if ($pos < input.length && { val _c = input.charAt($pos); $check }) Some((input.charAt($pos).toString, $pos + 1)) else _fail($pos, \"[$descEsc]\"))"

      case CharSet(_, positive, elems) =>
        val sortedPred = elems.toList.sorted.map(ch => s"(_c == ${charLiteralS(ch)})").mkString(" || ")
        val check = if (positive) sortedPred else s"!($sortedPred)"
        val desc = (if (positive) "" else "^") + elems.toList.sorted.mkString
        val descEsc = desc.replace("\\", "\\\\").replace("\"", "\\\"")
        s"(if ($pos < input.length && { val _c = input.charAt($pos); $check }) Some((input.charAt($pos).toString, $pos + 1)) else _fail($pos, \"[$descEsc]\"))"

      // Sequence with one side ignored: collapse to LeftProject or RightProject
      case Sequence(_, l, IgnoredExpr(_, r)) =>
        val (r1, p1, p2) = (fresh("r"), fresh("p"), fresh("p"))
        s"${genExpr(l, pos)}.flatMap { case ($r1, $p1) => ${genExpr(r, p1)}.map { case (_, $p2) => ($r1, $p2) } }"

      case Sequence(_, IgnoredExpr(_, l), r) =>
        val p1 = fresh("p")
        s"${genExpr(l, pos)}.flatMap { case (_, $p1) => ${genExpr(r, p1)} }"

      case Sequence(_, l, r) =>
        val (r1, p1, r2, p2) = (fresh("r"), fresh("p"), fresh("r"), fresh("p"))
        s"${genExpr(l, pos)}.flatMap { case ($r1, $p1) => ${genExpr(r, p1)}.map { case ($r2, $p2) => (new ~($r1, $r2), $p2) } }"

      // Standalone IgnoredExpr: match but return Unit
      case IgnoredExpr(_, e) =>
        val p1 = fresh("p")
        s"${genExpr(e, pos)}.map { case (_, $p1) => ((), $p1) }"

      case LeftProject(_, l, r) =>
        val (r1, p1, p2) = (fresh("r"), fresh("p"), fresh("p"))
        s"${genExpr(l, pos)}.flatMap { case ($r1, $p1) => ${genExpr(r, p1)}.map { case (_, $p2) => ($r1, $p2) } }"

      case RightProject(_, l, r) =>
        val p1 = fresh("p")
        s"${genExpr(l, pos)}.flatMap { case (_, $p1) => ${genExpr(r, p1)} }"

      case Alternation(_, l, r) =>
        val v = fresh("v")
        val sc = fresh("sc")
        s"""{ val $sc = _failState.get().committed; _failState.set(_failState.get().copy(committed = false)); val $v = ${genExpr(l, pos)}; if ($v.isDefined || _failState.get().committed) $v else { _failState.set(_failState.get().copy(committed = $sc)); ${genExpr(r, pos)} } }"""

      case Repeat0(_, b) =>
        val (rs, cp, st, np, go) = (fresh("rs"), fresh("cp"), fresh("st"), fresh("np"), fresh("go"))
        s"""{
          |  var $rs: List[Any] = Nil; var $cp: Int = $pos; var $go = true
          |  while ($go) { ${genExpr(b, cp)} match {
          |    case Some(($st, $np)) => $rs = $rs :+ $st; $cp = $np
          |    case None => $go = false } }
          |  Some(($rs, $cp)) }""".stripMargin

      case Repeat1(_, b) =>
        val (fs, fp, rs, cp, st, np, go) = (fresh("fs"), fresh("fp"), fresh("rs"), fresh("cp"), fresh("st"), fresh("np"), fresh("go"))
        s"""{
          |  ${genExpr(b, pos)} match {
          |    case None => None
          |    case Some(($fs, $fp)) =>
          |      var $rs: List[Any] = List($fs); var $cp: Int = $fp; var $go = true
          |      while ($go) { ${genExpr(b, cp)} match {
          |        case Some(($st, $np)) => $rs = $rs :+ $st; $cp = $np
          |        case None => $go = false } }
          |      Some(($rs, $cp)) } }""".stripMargin

      case Optional(_, b) =>
        s"(${genExpr(b, pos)}.map { case (v, p) => (Some(v), p) }.orElse(Some((None, $pos))))"

      case AndPredicate(_, b) =>
        s"(if (${genExpr(b, pos)}.isDefined) Some(((), $pos)) else None)"

      case NotPredicate(_, b) =>
        s"(if (${genExpr(b, pos)}.isEmpty) Some(((), $pos)) else None)"

      case ActionBlock(_, body, code) =>
        s"${genExpr(body, pos)}.map { case (r, p) => (_applyAction({ $code }, r), p) }"

      case Debug(_, b) => genExpr(b, pos)

      case Identifier(_, name) =>
        s"${parseMethodName(name)}(input, $pos)"

      case Call(_, name, args) =>
        ruleMap.get(name) match {
          case Some(rule) if rule.args.nonEmpty =>
            if (args.length != rule.args.length)
              s"(None: Option[(Any, Int)]) /* arity mismatch for ${name.name} */"
            else
              genExpr(substitute(rule.body, rule.args.zip(args).toMap), pos)
          case _ =>
            s"${parseMethodName(name)}(input, $pos)"
        }

      case Function(_, _, _) =>
        "(None: Option[(Any, Int)]) /* unexpected Function node */"
    }

    def genRule(rule: Rule): String = {
      if (rule.args.nonEmpty) return ""
      val method = parseMethodName(rule.name)
      val bodyCode = genExpr(rule.body, "pos")
      val isCut   = rule.annotations.exists(_.isInstanceOf[CutAnnotation])
      val labelOpt = rule.annotations.collectFirst { case LabelAnnotation(_, n) => n }
      val body = memoIdMap.get(rule.name) match {
        case Some(id) =>
          s"_withMemo($id, pos) {\n    $bodyCode\n  }"
        case None =>
          bodyCode
      }
      // Build wrapper based on annotations
      (isCut, labelOpt) match {
        case (false, None) =>
          s"  def $method(input: String, pos: Int): Option[(Any, Int)] = $body"
        case (true, None) =>
          s"""  def $method(input: String, pos: Int): Option[(Any, Int)] = {
             |    val _savedOff = _failState.get().offset
             |    val _r = $body
             |    if (_r.isEmpty) { val _fs = _failState.get(); if (_fs.offset > _savedOff) _failState.set(_fs.copy(committed = true)) }
             |    _r
             |  }""".stripMargin
        case (false, Some(lbl)) =>
          val lblEsc = lbl.replace("\\", "\\\\").replace("\"", "\\\"")
          s"""  def $method(input: String, pos: Int): Option[(Any, Int)] = {
             |    _pushRule("$lblEsc")
             |    try {
             |      val _r = $body
             |      if (_r.isEmpty) { val _fs = _failState.get(); if (_fs.offset == pos) _failState.set(_fs.copy(expected = List("$lblEsc"))) }
             |      _r
             |    } finally { _popRule() }
             |  }""".stripMargin
        case (true, Some(lbl)) =>
          val lblEsc = lbl.replace("\\", "\\\\").replace("\"", "\\\"")
          s"""  def $method(input: String, pos: Int): Option[(Any, Int)] = {
             |    _pushRule("$lblEsc")
             |    val _savedOff = _failState.get().offset
             |    try {
             |      val _r = $body
             |      if (_r.isEmpty) { val _fs = _failState.get()
             |        if (_fs.offset > _savedOff) _failState.set(_fs.copy(committed = true))
             |        else if (_fs.offset == pos) _failState.set(_fs.copy(expected = List("$lblEsc")))
             |      }
             |      _r
             |    } finally { _popRule() }
             |  }""".stripMargin
      }
    }

    val directives   = grammar.directives
    val pkgName      = directives.collectFirst { case PackageDirective(n) => n }.orElse(packageName)
    val objName      = directives.collectFirst { case ObjectDirective(n) => n }.getOrElse(objectName)
    val imports      = directives.collect { case ImportDirective(p) => s"import $p" }
    val helpers      = directives.collect { case HelperDirective(c) => c }
    val preprocs     = directives.collect { case PreprocessDirective(c) => c }
    val rawRules     = directives.collect { case RawRuleDirective(name, body) => (name, body) }
    val rawRuleNames = rawRules.map(_._1).toSet

    val packageLine  = pkgName.map(p => s"package $p\n\n").getOrElse("")
    val importSection = if (imports.nonEmpty) imports.mkString("\n") + "\n\n" else ""

    val errorInfra =
      s"""  private case class _FailState(offset: Int, expected: List[String], committed: Boolean)
         |  private val _failState = new ThreadLocal[_FailState] {
         |    override def initialValue() = _FailState(0, Nil, false)
         |  }
         |  private val _ruleStack = new ThreadLocal[List[String]] {
         |    override def initialValue() = Nil
         |  }
         |  private def _fail(pos: Int, expected: String): None.type = {
         |    val fs = _failState.get()
         |    if (pos > fs.offset) _failState.set(_FailState(pos, List(expected), false))
         |    else if (pos == fs.offset && !fs.expected.contains(expected)) _failState.set(fs.copy(expected = fs.expected :+ expected))
         |    None
         |  }
         |  private def _pushRule(name: String): Unit = _ruleStack.set(name :: _ruleStack.get())
         |  private def _popRule(): Unit = { val s = _ruleStack.get(); if (s.nonEmpty) _ruleStack.set(s.tail) }
         |  private def _formatError(input: String, fs: _FailState): String = {
         |    val off = math.max(0, math.min(input.length, fs.offset))
         |    val pre = input.substring(0, off)
         |    val line = pre.count(_ == '\\n') + 1
         |    val col  = pre.reverseIterator.takeWhile(_ != '\\n').length + 1
         |    val s0   = math.max(0, off - 30); val s1 = math.min(input.length, off + 30)
         |    val frag = input.substring(s0, s1).replace('\\n', ' ').replace('\\r', ' ')
         |    val ptr  = " " * (off - s0) + "^"
         |    val exp  = if (fs.expected.isEmpty) "" else s"\\nexpected: $${fs.expected.distinct.mkString(", ")}"
         |    val stk  = if (_ruleStack.get().isEmpty) "" else s"\\nin rule: $${_ruleStack.get().reverse.mkString(" > ")}"
         |    s"parse error at $$line:$$col$$exp$$stk\\n$$frag\\n$$ptr"
         |  }""".stripMargin

    val memoInfra = if (memoIdMap.nonEmpty) {
      val idDecls = memoIdMap.map { case (sym, id) =>
        s"  private val _MEMO_${sanitizeIdentifier(sym.name).toUpperCase} = $id"
      }.mkString("\n")
      s"""$idDecls
         |  private val _memoStore = new ThreadLocal[java.util.HashMap[(Int,Int),AnyRef]] {
         |    override def initialValue() = new java.util.HashMap[(Int,Int),AnyRef]()
         |  }
         |  private def _withMemo(id: Int, pos: Int)(f: => Option[(Any,Int)]): Option[(Any,Int)] = {
         |    val key = (id, pos)
         |    _memoStore.get().get(key) match {
         |      case null =>
         |        _memoStore.get().put(key, (None: Option[(Any,Int)]).asInstanceOf[AnyRef])
         |        val r = f; _memoStore.get().put(key, r.asInstanceOf[AnyRef]); r
         |      case v => v.asInstanceOf[Option[(Any,Int)]]
         |    }
         |  }
         |  def resetMemo(): Unit = { _memoStore.get().clear(); _failState.set(_FailState(0, Nil, false)); _ruleStack.set(Nil) }""".stripMargin
    } else
      s"  def resetMemo(): Unit = { _failState.set(_FailState(0, Nil, false)); _ruleStack.set(Nil) }"

    val helperSection  = helpers.map(h => s"  $h").mkString("\n")
    val preprocSection = preprocs.map(p => s"  $p").mkString("\n")
    val hasPreproc     = preprocs.nonEmpty
    val rawRuleDefs    = rawRules.map { case (name, body) =>
      val method = parseMethodName(Symbol(name))
      s"  def $method(input: String, pos: Int): Option[(Any, Int)] = {\n$body\n  }"
    }.mkString("\n\n")
    val ruleDefs       = grammar.rules.filter(r => r.args.isEmpty && !rawRuleNames(r.name.name)).map(genRule).filter(_.nonEmpty).mkString("\n\n")
    val startMethod    = parseMethodName(startRule)

    // parse entry point: if %preprocess is defined, call _preprocess first
    val parseEntryInput = if (hasPreproc) "_preprocess(input)" else "input"

    Right(
      s"""${packageLine}${importSection}object $objName {
         |  case class ~[+A, +B](_1: A, _2: B) {
         |    override def toString: String = s"($$_1 ~ $$_2)"
         |  }
         |  private def _applyAction(f: Any => Any, v: Any): Any = f(v)
         |
         |$errorInfra
         |
         |$memoInfra
         |$helperSection
         |$preprocSection
         |
         |$rawRuleDefs
         |
         |$ruleDefs
         |
         |  def parse(input: String): Option[(Any, Int)] = {
         |    resetMemo()
         |    val _in = $parseEntryInput
         |    $startMethod(_in, 0)
         |  }
         |
         |  def parseAll(input: String): Either[String, Any] = {
         |    resetMemo()
         |    val _in = $parseEntryInput
         |    $startMethod(_in, 0) match {
         |      case Some((result, pos)) if pos == _in.length => Right(result)
         |      case Some((_, pos)) => _fail(pos, "end of input"); Left(_formatError(_in, _failState.get()))
         |      case None => Left(_formatError(_in, _failState.get()))
         |    }
         |  }
         |}
         |""".stripMargin
    )
  }

  /** Try to inline all higher-order macro calls via beta reduction.
   *  Returns Some(firstOrderGrammar) on success, None if expansion loops or fails.
   *  Dead higher-order rule definitions are dropped after inlining. */
  private def tryInlineHigherOrder(grammar: Grammar, startRule: Symbol, timeoutMs: Long = 3000L): Option[Grammar] = {
    @volatile var result: Option[Grammar] = None
    val worker = new Thread(() => {
      try {
        val expanded = MacroExpander.expandGrammar(grammar)
        val firstOrderRules = expanded.rules.filter(_.args.isEmpty)
        val inlined = expanded.copy(rules = firstOrderRules)
        if(isFirstOrder(inlined) && inlined.rules.exists(_.name == startRule))
          result = Some(inlined)
      } catch { case _: Throwable => () }
    }, "macro-expander-worker")
    worker.setDaemon(true)
    worker.start()
    worker.join(timeoutMs)
    if(worker.isAlive) worker.interrupt()  // stop a runaway (non-terminating) expansion
    result
  }

  private def generateCombinatorBackend(
    grammar: Grammar,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol
  ): Either[GenerationError, String] = {
    val ruleNameMap = buildRuleNameMap(grammar.rules)

    // Build a left-associative ~ pattern string, e.g. ["l","_","r"] → "l ~ _ ~ r"
    def buildCasePattern(labels: List[Option[String]]): String =
      labels.map(_.getOrElse("_")).mkString(" ~ ")

    // IR-based combinator emitter. Combinator is first-order only (higher-order is
    // inlined by generateInternal before reaching here), so HoCall/ParamRef/Lambda
    // are errors. Sequence/Alternation are N-ary in the IR; `~`/`/` are left-assoc
    // operators, so a flat mkString reproduces the original left-nested semantics.
    def emitCombinatorIr(exp: Ir.Expr): Either[GenerationError, String] = exp match {
      case Ir.Expr.Seq(parts, Some(action)) =>
        val hasLabels = parts.exists(_.label.isDefined)
        parts.toList.foldLeft[Either[GenerationError, List[(Option[String], String)]]](Right(Nil)) {
          case (acc, p) => for { list <- acc; emitted <- emitCombinatorIr(p.expr) } yield list :+ (p.label, emitted)
        }.map { emittedParts =>
          val seqStr = emittedParts.map(_._2).mkString(" ~ ")
          if (hasLabels) {
            val patStr = buildCasePattern(emittedParts.map(_._1))
            s"($seqStr).map { case $patStr => { ${action.code} } }"
          } else {
            s"($seqStr).map { __result => { ${action.code} } }"
          }
        }
      case Ir.Expr.Seq(parts, None) =>
        if (parts.length == 1) emitCombinatorIr(parts.head.expr)
        else parts.toList.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) {
          (acc, p) => for { list <- acc; e <- emitCombinatorIr(p.expr) } yield list :+ e
        }.map(es => s"(${es.mkString(" ~ ")})")
      case Ir.Expr.Alt(branches) =>
        branches.toList.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) {
          (acc, b) => for { list <- acc; e <- emitCombinatorIr(b) } yield list :+ e
        }.map(es => s"(${es.mkString(" / ")})")
      case Ir.Expr.Rep(b, false) => emitCombinatorIr(b).map(be => s"($be).*")
      case Ir.Expr.Rep(b, true)  => emitCombinatorIr(b).map(be => s"($be).+")
      case Ir.Expr.Opt(b)        => emitCombinatorIr(b).map(be => s"($be).?")
      case Ir.Expr.And(b)        => emitCombinatorIr(b).map(be => s"($be).and")
      case Ir.Expr.Not(b)        => emitCombinatorIr(b).map(be => s"!($be)")
      case Ir.Expr.Str(target)   => Right("\"" + escapeString(target) + "\".s")
      case Ir.Expr.AnyChar       => Right("any")
      case Ir.Expr.Chars(positive, ranges, singles) =>
        val encoded = (ranges.toList.map { case (f, t) => s"${charLiteral(f)} to ${charLiteral(t)}" } ++
                       singles.toList.sorted.map(ch => s"Seq(${charLiteral(ch)})")).mkString(", ")
        if (positive) Right(s"range($encoded)") else Right(s"notIn(range($encoded))")
      case Ir.Expr.RuleRef(id)   => Right(s"refer(${id.mangled})")
      case Ir.Expr.Cut(b)        => emitCombinatorIr(b).map(be => s"($be).cut")
      case Ir.Expr.Debug(b)      => emitCombinatorIr(b).map(be => s"($be).display")
      case Ir.Expr.TokenRef(_) =>
        Left(GenerationError(Ast.DUMMY_POSITION, "token references not yet supported in combinator backend"))
      case _: Ir.Expr.HoCall | _: Ir.Expr.ParamRef | _: Ir.Expr.Lambda =>
        Left(GenerationError(Ast.DUMMY_POSITION, "higher-order expression in combinator backend (should have been inlined)", Some("use higher-order fallback backend")))
    }

    val emittedRules: Either[GenerationError, List[String]] =
      Lowering.lower(grammar, startRule, Lowering.LowerOptions(typeCheck = false)) match {
        case Left(d) =>
          Left(GenerationError(d.position.getOrElse(Ast.DUMMY_POSITION), d.message, d.hint))
        case Right(program) =>
          program.syntactic.rules.toList.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) { (acc, rule) =>
            for { lines <- acc; body <- emitCombinatorIr(rule.body) } yield
              lines :+ s"  lazy val ${rule.id.mangled}: P[Any] = $body"
          }
      }

    emittedRules.map { lines =>
      val packagePrefix = packageName.map(p => s"package $p\n\n").getOrElse("")
      val startRuleName = ruleNameMap(startRule)
      val ruleDefs = lines.mkString("\n")
      val headPreamble = if (grammar.preamble.nonEmpty) grammar.preamble + "\n" else ""
      s"""${packagePrefix}import com.github.kmizu.macro_peg.combinator.MacroParsers._
         |
         |object $objectName {
         |$headPreamble  private def notIn[T](p: P[T]): P[String] =
         |    (!p ~ any).map { case _ ~ ch => ch }
         |
         |  /** Flatten nested ~ tuples / Lists / Options into a single String. */
         |  def flatText(x: Any): String = x match {
         |    case a ~ b          => flatText(a) + flatText(b)
         |    case xs: List[?]    => xs.map(flatText).mkString
         |    case Some(v)        => flatText(v)
         |    case None           => ""
         |    case ()             => ""
         |    case s: String      => s
         |    case c: Char        => c.toString
         |    case _              => ""
         |  }
         |
         |  /** Parse a Ruby integer literal text (hex/octal/binary/decimal) to BigInt. */
         |  def parseIntLit(raw: Any): BigInt = {
         |    val s = flatText(raw).trim.filter(_ != '_')
         |    if      (s.startsWith("0x") || s.startsWith("0X")) BigInt(s.drop(2), 16)
         |    else if (s.startsWith("0b") || s.startsWith("0B")) BigInt(s.drop(2), 2)
         |    else if (s.startsWith("0o") || s.startsWith("0O")) BigInt(s.drop(2), 8)
         |    else if (s.startsWith("0d") || s.startsWith("0D")) BigInt(s.drop(2), 10)
         |    else if (s.length > 1 && s.startsWith("0"))        BigInt(s.drop(1), 8)
         |    else                                                BigInt(s)
         |  }
         |
         |  /** Parse a Ruby float literal text to Double. */
         |  def parseFloatLit(raw: Any): Double = flatText(raw).trim.filter(_ != '_').toDouble
         |
         |  /** Flatten a left-associative ~ chain into a List (left to right). */
         |  def flattenTilde(x: Any): List[Any] = x match {
         |    case a ~ b => flattenTilde(a) ::: List(b)
         |    case other => List(other)
         |  }
         |
         |  /** Cast Any to RubyAst.Expr; for ~ chains (rules without semantic actions) search for Expr. */
         |  def toExpr(x: Any): com.github.kmizu.macro_peg.ruby.RubyAst.Expr = x match {
         |    case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e
         |    case _ =>
         |      flattenTilde(x).collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(com.github.kmizu.macro_peg.ruby.RubyAst.StringLiteral(flatText(x).trim))
         |  }
         |
         |  /** Cast Any to List[RubyAst.Expr]. */
         |  def toExprList(xs: List[Any]): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] =
         |    xs.map(toExpr)
         |
         |  /** Cast Any to List[RubyAst.Statement]. Handles ~ chains from rules without semantic actions. */
         |  def toStmtList(xs: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = xs match {
         |    case list: List[?] => list.flatMap(x => toStmtList(x))
         |    case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |    case Some(inner) => toStmtList(inner)
         |    case None => Nil
         |    case a ~ (b: Option[?]) => toStmtList(a) ++ b.map(x => toStmtList(x)).getOrElse(Nil)
         |    case a ~ (b: List[?]) => toStmtList(a) ++ b.flatMap(x => toStmtList(x))
         |    case a ~ b =>
         |      val bStmts = b match {
         |        case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |        case _ => Nil
         |      }
         |      toStmtList(a) ++ bStmts
         |    case _ => Nil
         |  }
         |
         |  /**
         |   * Fold binary ops: result of `Lhs (Op Spacing Rhs)*` into a BinaryOp chain.
         |   * Raw structure: lhs ~ List[(opToken ~ spacing ~ rhs)...] (left-associative ~ nesting).
         |   * Each list element is a left-assoc chain ending in rhs; everything before rhs is the op text.
         |   */
         |  def foldBinOps(raw: Any): Any = raw match {
         |    case lhs ~ (ops: List[?]) =>
         |      ops.foldLeft(toExpr(lhs)) { case (acc, item) =>
         |        val parts = flattenTilde(item)
         |        val rhs = toExpr(parts.last)
         |        val opText = flatText(parts.dropRight(1)).trim
         |        com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(acc, opText, rhs)
         |      }
         |    case other => other
         |  }
         |
         |  /**
         |   * Fold right-associative power: result of `Base (Token("**") Spacing PowerExpr)?`.
         |   * Raw structure: base ~ Option(opAndRhs).
         |   */
         |  def foldPowerOp(raw: Any): Any = raw match {
         |    case base ~ Some(opAndRhs) =>
         |      val parts = flattenTilde(opAndRhs)
         |      val rhs = toExpr(parts.last)
         |      com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(toExpr(base), "**", rhs)
         |    case base ~ None => toExpr(base)
         |    case other => other
         |  }
         |
         |  /**
         |   * Fold ternary: result of `Cond (Spacing "?" Spacing Then Spacing ":" !":" Spacing Else)?`.
         |   * The optional branch is deeply nested. We flattenTilde and take positions 3 and 7
         |   * (0-indexed: sp=0, "?"=1, sp=2, then=3, sp=4, ":"=5, sp=6, else=7).
         |   */
         |  def foldTernary(raw: Any): Any = raw match {
         |    case cond ~ Some(rest) =>
         |      val parts = flattenTilde(rest)
         |      if (parts.length >= 8) {
         |        val thenExpr = toExpr(parts(3))
         |        val elseExpr = toExpr(parts(7))
         |        com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(
         |          toExpr(cond),
         |          List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(thenExpr)),
         |          List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(elseExpr))
         |        )
         |      } else toExpr(cond)
         |    case cond ~ None => toExpr(cond)
         |    case other => other
         |  }
         |
         |  /**
         |   * Extract elements from comma-separated list result:
         |   * first ~ List[(spacing ~ "," ~ spacing ~ item) ...]
         |   */
         |  def extractCommaSep(raw: Any): List[Any] = raw match {
         |    case first ~ (rest: List[?]) =>
         |      first :: rest.map { item => flattenTilde(item).last }
         |    case single => List(single)
         |  }
         |
         |  /**
         |   * Build RangeExpr from `EqExpr (RangeOp EqExpr?)?` result.
         |   * raw = eqExpr ~ Option(rangeOp ~ optEqExpr)
         |   */
         |  def foldRange(raw: Any): Any = raw match {
         |    case start ~ Some(opAndEnd) =>
         |      val parts = flattenTilde(opAndEnd)
         |      val opText = flatText(parts.head).trim
         |      val exclusive = opText == "..."
         |      val endExpr = parts.last match {
         |        case Some(e) => toExpr(e)
         |        case None    => com.github.kmizu.macro_peg.ruby.RubyAst.NilLiteral()
         |        case e       => toExpr(e)
         |      }
         |      com.github.kmizu.macro_peg.ruby.RubyAst.RangeExpr(toExpr(start), endExpr, exclusive)
         |    case start ~ None => toExpr(start)
         |    case other => other
         |  }
         |
         |  /**
         |   * Build beginless RangeExpr from `RangeOp EqExpr` result.
         |   * raw = rangeOp ~ eqExpr (as a ~ pair)
         |   */
         |  def beginlessRange(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val opText = flatText(parts.head).trim
         |    val exclusive = opText == "..."
         |    com.github.kmizu.macro_peg.ruby.RubyAst.RangeExpr(
         |      com.github.kmizu.macro_peg.ruby.RubyAst.NilLiteral(),
         |      toExpr(parts.last),
         |      exclusive
         |    )
         |  }
         |
         |  /**
         |   * Build ArrayLiteral from Token("[") Spacing (...optional elements...)? Token("]").
         |   * raw structure (flattenTilde): ["[", spacing, Option(first ~ rest ~ trailingComma? ~ spacing), "]"]
         |   * Inner optional: first ~ List[(sp "," sp item)...] ~ Option(",") ~ spacing
         |   */
         |  def buildArrayLiteral(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    // parts = ["[", spacing, Option(inner), "]"]  (4 elements)
         |    val innerOpt = if (parts.length >= 3) parts(2) else None
         |    val elems: List[Any] = innerOpt match {
         |      case Some(inner) =>
         |        val ip = flattenTilde(inner)
         |        // ip = [first, List[...], Option(trailing_comma), spacing]
         |        val first = ip.head
         |        val rest: List[Any] = ip.lift(1) match {
         |          case Some(xs: List[?]) => xs.map { item => flattenTilde(item).last }
         |          case _ => Nil
         |        }
         |        first :: rest
         |      case None => Nil
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ArrayLiteral(elems.map(toExpr))
         |  }
         |
         |  /**
         |   * Build Return from `Kw("return") (InlineSpacing CallArgList)?`.
         |   * raw = "return" ~ Option(spacing ~ argList)
         |   */
         |  def buildReturnStmt(raw: Any): Any = raw match {
         |    case _ ~ Some(spaceAndArgs) =>
         |      val parts = flattenTilde(spaceAndArgs)
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(Some(toExpr(parts.last)))
         |    case _ ~ None =>
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(None)
         |    case _ =>
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(None)
         |  }
         |
         |  /**
         |   * Build AssignExpr from CondExpr (CompoundAssignOp/AssignEq Spacing AssignExpr)?.
         |   * raw = condExpr ~ Option(op ~ spacing ~ rhs)
         |   */
         |  def buildAssignExpr(raw: Any): Any = raw match {
         |    case lhs ~ Some(opAndRhs) =>
         |      val parts = flattenTilde(opAndRhs)
         |      val op = flatText(parts.head).trim
         |      val rhs = toExpr(parts.last)
         |      val lhsName = lhs match {
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.LocalVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.InstanceVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.ClassVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.GlobalVar(n, _) => n
         |        case _ => flatText(lhs).trim
         |      }
         |      if (op == "=") {
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, rhs)
         |      } else {
         |        val baseOp = op.stripSuffix("=")
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(
         |          lhsName,
         |          com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(toExpr(lhs), baseOp, rhs)
         |        )
         |      }
         |    case lhs ~ None => toExpr(lhs)
         |    case other => other
         |  }
         |
         |  /**
         |   * Build MultiAssignStmt: MultiAssignLhs Spacing AssignEq Spacing MultiAssignRhs
         |   * raw = ((((lhs ~ sp1) ~ eq) ~ sp2) ~ rhs)  (left-associative ~-chain)
         |   * Uses explicit nested pattern to extract lhsResult and rhsResult intact.
         |   */
         |  def buildMultiAssignStmt(raw: Any): Any = {
         |    val (lhsResult, rhsResult) = raw match {
         |      case ((((l ~ _) ~ _) ~ _) ~ r) => (l, r)
         |      case other => (other, other)
         |    }
         |    // lhsResult = MultiAssignLhs = new ~(firstLhs, List[(sep~lhs)*])
         |    val (firstLhs, moreLhsList) = lhsResult match {
         |      case a ~ (b: List[?]) => (a, b)
         |      case other => (other, Nil)
         |    }
         |    val firstName = flatText(firstLhs).trim
         |    val moreNames: List[String] = moreLhsList.map(item => flatText(flattenTilde(item).last).trim).filter(_.nonEmpty)
         |    val names = (firstName :: moreNames).filter(_.nonEmpty)
         |    // rhsResult = MultiAssignRhs = new ~(new ~(firstRhs, List[restRhs]), optComma)
         |    val rhsInner = rhsResult match { case a ~ _ => a; case other => other }
         |    val (firstRhsRaw, moreRhsList) = rhsInner match {
         |      case a ~ (b: List[?]) => (a, b)
         |      case other => (other, Nil)
         |    }
         |    val firstRhs = toExpr(firstRhsRaw)
         |    val moreRhs: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = moreRhsList.map(item => toExpr(flattenTilde(item).last))
         |    if (names.size == 1 && moreRhs.isEmpty) {
         |      com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(names.head, firstRhs)
         |    } else {
         |      com.github.kmizu.macro_peg.ruby.RubyAst.MultiAssignExpr(names, com.github.kmizu.macro_peg.ruby.RubyAst.ArrayLiteral(firstRhs :: moreRhs))
         |    }
         |  }
         |
         |  def buildCommandCall(name: Any, args: Any): Any = {
         |    val methodName = flatText(name).trim
         |    val argList = extractArgList(args)
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, methodName, argList)
         |  }
         |
         |  def appendCommandArgsToExpr(target: com.github.kmizu.macro_peg.ruby.RubyAst.Expr, args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr]): com.github.kmizu.macro_peg.ruby.RubyAst.Expr = {
         |    if (args.isEmpty) return target
         |    target match {
         |      case call @ com.github.kmizu.macro_peg.ruby.RubyAst.Call(receiver, methodName, existingArgs, span) =>
         |        if (existingArgs.isEmpty) com.github.kmizu.macro_peg.ruby.RubyAst.Call(receiver, methodName, args, span)
         |        else com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(call), "call", args)
         |      case com.github.kmizu.macro_peg.ruby.RubyAst.LocalVar(name, span) =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, name, args, span)
         |      case com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, rhs, _) =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, appendCommandArgsToExpr(rhs, args))
         |      case other =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(other), "call", args)
         |    }
         |  }
         |
         |  def buildExprWithCmdArgs(baseRaw: Any, tailRaw: Any): Any = {
         |    val base = toExpr(baseRaw)
         |    tailRaw match {
         |      case None => base
         |      case Some(tail) =>
         |        val parts = flattenTilde(tail)
         |        // parts: [(), "", CommandArgList_result, Block?_result]
         |        // guard contributes 2 flat elements: () from AndPred and "" from NotPred
         |        val argsPart = if (parts.length >= 3) parts(2) else null
         |        val args = if (argsPart != null) extractArgList(argsPart) else Nil
         |        appendCommandArgsToExpr(base, args)
         |      case _ => base
         |    }
         |  }
         |
         |  /**
         |   * Build a single (key, value) hash entry from HashEntryRocket or HashEntryLabel.
         |   */
         |  def buildHashEntry(entry: Any): (com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr) = {
         |    val parts = flattenTilde(entry)
         |    val hasRocket = parts.exists(p => flatText(p).trim == "=>")
         |    if (hasRocket) {
         |      (toExpr(parts.head), toExpr(parts.last))
         |    } else {
         |      val key: com.github.kmizu.macro_peg.ruby.RubyAst.Expr = parts.head match {
         |        case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e
         |        case raw =>
         |          val s = flatText(raw).trim.stripSuffix(":")
         |          com.github.kmizu.macro_peg.ruby.RubyAst.SymbolLiteral(s, com.github.kmizu.macro_peg.ruby.RubyAst.UnknownSpan)
         |      }
         |      (key, toExpr(parts.last))
         |    }
         |  }
         |
         |  /**
         |   * Build HashLiteral from `Token("{") Spacing HashBody? Spacing Token("}")`.
         |   */
         |  def buildHashLiteral(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    // parts = ["{", spacing, Option(body), spacing, "}"]
         |    val entries: List[(com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr)] =
         |      parts.lift(2) match {
         |        case Some(Some(body)) =>
         |          val bp = flattenTilde(body)
         |          val first = buildHashEntry(bp.head)
         |          val rest: List[(com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr)] =
         |            bp.lift(1) match {
         |              case Some(xs: List[?]) => xs.map { item => buildHashEntry(flattenTilde(item).last) }
         |              case _ => Nil
         |            }
         |          first :: rest
         |        case _ => Nil
         |      }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.HashLiteral(entries)
         |  }
         |
         |  /**
         |   * Build ArrayLiteral elements from a CallArgList result:
         |   * first ~ List[(sp "," sp item)...] ~ Option(",")
         |   * Handles Option wrapping for optional arg list.
         |   */
         |  def extractArgList(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = raw match {
         |    case Some(inner) => extractArgListInner(inner)
         |    case None => Nil
         |    case _ => extractArgListInner(raw)
         |  }
         |
         |  def extractArgListInner(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = {
         |    val parts = flattenTilde(raw)
         |    // Structure: first ~ List[(sp ~ "," ~ sp ~ item)...] ~ Option(trailing ",")
         |    // But also could be just: first (no list)
         |    parts.length match {
         |      case 1 => List(toExpr(parts.head))
         |      case _ =>
         |        val first = toExpr(parts.head)
         |        val rest: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.lift(1) match {
         |          case Some(xs: List[?]) => xs.map { item => toExpr(flattenTilde(item).last) }
         |          case _ => Nil
         |        }
         |        first :: rest
         |    }
         |  }
         |
         |  /**
         |   * Collect BlockStatements result into List[Statement].
         |   * BlockStatements = (Statement StatementSep)* Statement?
         |   * raw = List[(stmt ~ sep)] ~ Option(stmt)
         |   */
         |  def collectStmts(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = {
         |    val parts = flattenTilde(raw)
         |    // parts = [List[(stmt ~ sep)...], Option(lastStmt)]
         |    val repeated: List[Any] = parts.head match {
         |      case xs: List[?] => xs.map { item => flattenTilde(item).head }
         |      case _ => Nil
         |    }
         |    val lastOpt: List[Any] = parts.lift(1) match {
         |      case Some(Some(s)) => List(s)
         |      case _ => Nil
         |    }
         |    (repeated ++ lastOpt).flatMap {
         |      case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |      case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr =>
         |        List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(e))
         |      case _ => Nil
         |    }
         |  }
         |
         |  /**
         |   * Build IfExpr from:
         |   * Kw("if") Spacing Expr StatementSep BodyWithRescue
         |   *   (Kw("elsif") Spacing Expr StatementSep BodyWithRescue)*
         |   *   (Kw("else") StatementSep? BodyWithRescue)?  Kw("end")
         |   * Flatten and pick out condition (idx 2), then body (idx 4), elsif list (idx 5), else (idx 6).
         |   */
         |  def buildIfExpr(cond: Any, body: Any, elsifs: Any, elseOpt: Any): Any = {
         |    val condExpr = toExpr(cond)
         |    val thenBody = toStmtList(body)
         |    val elsifList: List[Any] = elsifs match {
         |      case xs: List[?] => xs
         |      case _ => Nil
         |    }
         |    val elseBody: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = elseOpt match {
         |      case Some(inner) =>
         |        flattenTilde(inner).collectFirst { case stmts: List[?] => stmts }
         |          .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |          .getOrElse(toStmtList(inner))
         |      case _ => Nil
         |    }
         |    val fullElse = elsifList.foldRight(elseBody) { case (elsif, acc) =>
         |      val ep = flattenTilde(elsif)
         |      val ec = ep.collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(toExpr(elsif))
         |      val eb = ep.collectFirst { case stmts: List[?] => stmts }
         |        .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |        .getOrElse(Nil)
         |      List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(
         |        com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(ec, eb, acc)
         |      ))
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(condExpr, thenBody, fullElse)
         |  }
         |
         |  def buildUnlessExpr(cond: Any, body: Any, elseOpt: Any): Any = {
         |    val condExpr = toExpr(cond)
         |    val thenBody = toStmtList(body)
         |    val elseBody: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = elseOpt match {
         |      case Some(inner) =>
         |        flattenTilde(inner).collectFirst { case stmts: List[?] => stmts }
         |          .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |          .getOrElse(toStmtList(inner))
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.UnlessExpr(condExpr, thenBody, elseBody)
         |  }
         |
         |  def buildWhileExpr(cond: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.WhileExpr(toExpr(cond), toStmtList(body))
         |
         |  def buildUntilExpr(cond: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.UntilExpr(toExpr(cond), toStmtList(body))
         |
         |  /**
         |   * Build Program from Spacing TopStatements !.
         |   * TopStatements = ~(List[seps], Some(~(~(firstStmt, List[(seps~stmt)*]), List[seps])) | None)
         |   */
         |  def buildProgram(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    // parts(0)=Spacing, parts(1)=TopStatements, parts(2)=!.
         |    val stmts: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = parts.lift(1) match {
         |      case Some(topStmtsRaw) =>
         |        val tsParts = flattenTilde(topStmtsRaw) // [List[seps], Some(stmtChain) | None]
         |        tsParts.lift(1) match {
         |          case Some(Some(stmtChain)) =>
         |            // stmtChain = ~(~(firstStmt, moreList), trailSeps)
         |            // flattenTilde gives [...firstStmtParts..., moreList, trailSeps]
         |            val scParts = flattenTilde(stmtChain)
         |            // moreList is second-to-last; firstStmtParts are everything before it
         |            val moreList: List[Any] = scParts.dropRight(1).lastOption match {
         |              case Some(xs: List[?]) => xs
         |              case _ => Nil
         |            }
         |            val firstStmtParts = scParts.dropRight(2)
         |            val more: List[Any] = moreList.map(item => flattenTilde(item).last)
         |            toStmtList(firstStmtParts) ++ more.flatMap(x => toStmtList(x))
         |          case _ => Nil
         |        }
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Program(stmts)
         |  }
         |
         |  /**
         |   * Extract parameter names from DefParamList result.
         |   * DefParamItem can be: "**" Identifier? | "*" Identifier? | "&" Identifier? | "..." | Identifier ...
         |   * We do a best-effort extraction of identifier names.
         |   */
         |  def extractParamNames(raw: Any): List[String] = raw match {
         |    case Some(inner) =>
         |      val parts = flattenTilde(inner)
         |      // parts = [first, List[(comma ~ param)...]]
         |      val first = parts.head
         |      val rest: List[Any] = parts.lift(1) match {
         |        case Some(xs: List[?]) => xs.map(item => flattenTilde(item).last)
         |        case _ => Nil
         |      }
         |      (first :: rest).map(flatText(_).trim).filter(_.nonEmpty)
         |    case None => Nil
         |    case _ => Nil
         |  }
         |
         |  /**
         |   * Build Call chain from PostfixExpr result:
         |   * (FunctionCall / PrimaryNoCall) CallSuffix*
         |   * raw = base ~ List[suffix]
         |   *
         |   * CallSuffix = DotCallSuffix / IndexSuffix / BraceBlockSuffix / DoBlockSuffix
         |   * Each suffix is structurally distinct but we use flatText heuristics.
         |   */
         |  def buildPostfixExpr(raw: Any): Any = raw match {
         |    case base ~ (suffixes: List[?]) if suffixes.nonEmpty =>
         |      suffixes.foldLeft(toExpr(base)) { (acc, suffix) =>
         |        val parts = flattenTilde(suffix)
         |        val firstText = flatText(parts.head).trim
         |        if (firstText == "[") {
         |          // IndexSuffix: "[" spacing argList? spacing "]"
         |          val args = parts.lift(2).map(extractArgList).getOrElse(Nil)
         |          com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(acc), "[]", args)
         |        } else if (firstText == "." || firstText == "::" || firstText == "&.") {
         |          // DotCallSuffix: memberSep ~ methodName ~ argsOpt ~ blockOpt
         |          val methodName = flatText(parts.lift(1).getOrElse("")).trim
         |          val args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.lift(2) match {
         |            case Some(Some(inner)) => extractArgListInner(inner)
         |            case _ => Nil
         |          }
         |          com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(acc), methodName, args)
         |        } else {
         |          // Block suffix or other — just return base for now
         |          acc
         |        }
         |      }
         |    case base ~ _ => toExpr(base)
         |    case other => other
         |  }
         |
         |  /**
         |   * Build FunctionCall from `MethodIdentifier !HorizontalSpaceChar CallArgs`.
         |   * raw = name ~ predResult ~ args
         |   */
         |  def buildFunctionCall(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val name = flatText(parts.head).trim
         |    val args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.last match {
         |      case Some(inner) => extractArgList(Some(inner))
         |      case _ => extractArgList(parts.last)
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, name, args)
         |  }
         |
         |  def buildDefExpr(name: Any, params: Any, body: Any): Any = {
         |    val defName = flatText(name).trim
         |    val paramList = params match {
         |      case Some(inner) => extractParamNames(Some(inner))
         |      case _ => Nil
         |    }
         |    val bodyStmts: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = body match {
         |      case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr =>
         |        List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(e))
         |      case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |      case _ => toStmtList(body)
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Def(defName, paramList, bodyStmts)
         |  }
         |
         |  def buildClassExpr(nameOrReceiver: Any, body: Any): Any = {
         |    val bodyStmts = toStmtList(body)
         |    if (flatText(nameOrReceiver).trim.startsWith("<<")) {
         |      val e = flattenTilde(nameOrReceiver)
         |        .collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(com.github.kmizu.macro_peg.ruby.RubyAst.StringLiteral(flatText(nameOrReceiver).trim))
         |      com.github.kmizu.macro_peg.ruby.RubyAst.SingletonClassDef(e, bodyStmts)
         |    } else {
         |      val name = flatText(nameOrReceiver).trim
         |      com.github.kmizu.macro_peg.ruby.RubyAst.ClassDef(name, bodyStmts, com.github.kmizu.macro_peg.ruby.RubyAst.UnknownSpan, None)
         |    }
         |  }
         |
         |  def buildModuleExpr(name: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ModuleDef(flatText(name).trim, toStmtList(body))
         |
         |  def buildBeginExpr(body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.BeginRescue(toStmtList(body), Nil, Nil, Nil)
         |
         |  def buildCaseExpr(scrutineeOpt: Any, clauses: Any, elseOpt: Any): Any = {
         |    val scrutinee: Option[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = scrutineeOpt match {
         |      case Some(inner) => Some(toExpr(inner))
         |      case _ => None
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.CaseExpr(scrutinee, Nil, Nil)
         |  }
         |
         |  def buildForExpr(vars: Any, iter: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ForIn(
         |      flatText(vars).trim, toExpr(iter), toStmtList(body)
         |    )
         |
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

  // ---------------------------------------------------------------------------
  // Recursive-Descent backend: generates plain Scala code, no library deps
  // ---------------------------------------------------------------------------
  private def generateRecursiveDescentBackend(
    grammar: Grammar,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol
  ): Either[GenerationError, String] = {
    val ruleNameMap  = buildRuleNameMap(grammar.rules)

    var freshCount = 0
    def fresh(prefix: String = "x"): String = { freshCount += 1; s"__${prefix}${freshCount}" }

    // ─── IR-based emission (Pillar A) ────────────────────────────────────────
    // All grammars (first-order AND higher-order) lower to the common IR and emit
    // from Ir.Expr. Higher-order is handled natively via the lambda-param method
    // (ParamRef → `__p$i()`, HoCall → `sn(thunks)`). Output is semantically stable:
    // Sequence/Alternation are left-associative in the Parser, so flattening to
    // N-ary IR and left-folding reproduces the same `~` nesting and choice order.

    def emitIr(exp: Ir.Expr): Either[GenerationError, String] = exp match {
      case Ir.Expr.Str("") =>
        Right("""Some("")""")
      case Ir.Expr.Str(target) =>
        val esc = escapeString(target); val len = target.length
        Right(s"""(if (input.startsWith("$esc", pos)) { pos += $len; Some("$esc") } else None)""")

      case Ir.Expr.AnyChar =>
        Right("""(if (pos < input.length) { val __wc = input.charAt(pos).toString; pos += 1; Some(__wc) } else None)""")

      case Ir.Expr.Chars(positive, ranges, singles) =>
        val rChecks = ranges.toList.map { case (f, t) => s"(__c >= ${charLiteral(f)} && __c <= ${charLiteral(t)})" }
        val sChecks = singles.toList.sorted.map(c => s"(__c == ${charLiteral(c)})")
        val joined  = (rChecks ++ sChecks).mkString(" || ")
        val cond    = if (positive) s"($joined)" else s"(!($joined))"
        Right(s"(if (pos < input.length) { val __c = input.charAt(pos); if $cond { pos += 1; Some(__c.toString) } else None } else None)")

      case Ir.Expr.Seq(parts, None)         => emitSeqNoActionIr(parts)
      case Ir.Expr.Seq(parts, Some(action)) => emitSeqActionIr(parts, action)
      case Ir.Expr.Alt(branches)            => emitAltIr(branches)

      case Ir.Expr.Rep(body, false) =>
        val buf = fresh("buf"); val go = fresh("go"); val sv = fresh("s")
        emitIr(body).map { bc =>
          s"{ val $buf = scala.collection.mutable.ListBuffer[Any](); var $go = true; while ($go) { val $sv = pos; val __ri = $bc; if (__ri.isDefined) $buf += __ri.get else { pos = $sv; $go = false } }; Some($buf.toList) }"
        }

      case Ir.Expr.Rep(body, true) =>
        val buf = fresh("buf"); val go = fresh("go"); val sv = fresh("s")
        emitIr(body).map { bc =>
          s"{ val __f1 = $bc; if (__f1.isDefined) { val $buf = scala.collection.mutable.ListBuffer[Any](__f1.get); var $go = true; while ($go) { val $sv = pos; val __ri = $bc; if (__ri.isDefined) $buf += __ri.get else { pos = $sv; $go = false } }; Some($buf.toList) } else None }"
        }

      case Ir.Expr.Opt(body) =>
        val sv = fresh("s")
        emitIr(body).map { bc =>
          s"{ val $sv = pos; val __op = $bc; if (__op.isDefined) Some(Some(__op.get)) else { pos = $sv; Some(None) } }"
        }

      case Ir.Expr.And(body) =>
        val sv = fresh("s")
        emitIr(body).map { bc =>
          s"{ val $sv = pos; val __ap = $bc; pos = $sv; if (__ap.isDefined) Some(()) else None }"
        }

      case Ir.Expr.Not(body) =>
        val sv = fresh("s")
        emitIr(body).map { bc =>
          s"""{ val $sv = pos; val __np = $bc; pos = $sv; if (__np.isDefined) None else Some("") }"""
        }

      case Ir.Expr.RuleRef(id) =>
        Right(s"${id.mangled}()")

      case Ir.Expr.Cut(body) =>
        val sv = fresh("s")
        emitIr(body).map { bc =>
          s"{ val $sv = pos; val __cr = $bc; if (__cr.isDefined) __cr else { pos = $sv; throw new __CommittedFailure(pos) } }"
        }

      case Ir.Expr.Debug(body) =>
        emitIr(body)

      case Ir.Expr.TokenRef(_) =>
        Left(GenerationError(Ast.DUMMY_POSITION, "token references not yet supported in recursive-descent backend"))

      case Ir.Expr.ParamRef(i, _) =>
        Right(s"__p$i()")

      case Ir.Expr.HoCall(id, args) =>
        args.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) { (acc, a) =>
          for { l <- acc; c <- emitArgAsLambdaIr(a) } yield l :+ c
        }.map(argList => s"${id.mangled}(${argList.mkString(", ")})")

      case _: Ir.Expr.Lambda =>
        Left(GenerationError(Ast.DUMMY_POSITION, "lambda expression not supported in recursive-descent backend"))
    }

    // N-ary sequence without action: evaluate parts left-to-right, left-fold into `~`.
    def emitSeqNoActionIr(parts: Vector[Ir.LabeledExpr]): Either[GenerationError, String] =
      if (parts.isEmpty) Right("""Some("")""")
      else if (parts.size == 1) emitIr(parts.head.expr)
      else {
        val sv = fresh("s")
        parts.toList.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) { (acc, p) =>
          for { l <- acc; c <- emitIr(p.expr) } yield l :+ c
        }.map { codes =>
          val binds = codes.map(c => (fresh("v"), c))
          def build(rem: List[(String, String)], acc: List[String]): String = rem match {
            case Nil =>
              val res = acc.reduceLeft((a, b) => s"new ~($a, $b)")
              s"Some($res)"
            case (v, c) :: rest =>
              s"val ${v}r = $c; if (${v}r.isDefined) { val $v = ${v}r.get; ${build(rest, acc :+ v)} } else { pos = $sv; None }"
          }
          s"{ val $sv = pos; ${build(binds, Nil)} }"
        }
      }

    // N-ary sequence with trailing action: bind labeled captures as named vals.
    def emitSeqActionIr(parts: Vector[Ir.LabeledExpr], action: Ir.ActionRef): Either[GenerationError, String] = {
      val sv = fresh("s")
      parts.toList.foldLeft[Either[GenerationError, List[(String, String)]]](Right(Nil)) { (acc, p) =>
        for { l <- acc; c <- emitIr(p.expr) } yield {
          val bindName = p.label.getOrElse(fresh("v"))
          l :+ (bindName, c)
        }
      }.map { namedParts =>
        def buildNested(rem: List[(String, String)], acc: List[String]): String = rem match {
          case Nil =>
            val resultExpr = acc match {
              case Nil     => "\"\""
              case List(v) => v
              case vs      => vs.reduceLeft((a, b) => s"new ~($a, $b)")
            }
            s"val __result = $resultExpr; Some({ ${action.code} })"
          case (bindName, code) :: rest =>
            val rVar  = fresh("r")
            val inner = buildNested(rest, acc :+ bindName)
            s"val $rVar = $code; if ($rVar.isDefined) { val $bindName = $rVar.get; $inner } else { pos = $sv; None }"
        }
        s"{ val $sv = pos; ${buildNested(namedParts, Nil)} }"
      }
    }

    // N-ary ordered choice: left-fold, each alternative restoring pos on failure.
    def emitAltIr(branches: Vector[Ir.Expr]): Either[GenerationError, String] =
      branches.toList match {
        case Nil          => Right("None")
        case first :: rest =>
          rest.foldLeft(emitIr(first)) { (accE, b) =>
            for { acc <- accE; bc <- emitIr(b) } yield {
              val sv = fresh("s")
              s"{ val $sv = pos; try { val __alt = $acc; if (__alt.isDefined) __alt else { pos = $sv; $bc } } catch { case __cf: __CommittedFailure => throw __cf } }"
            }
          }
      }

    // Emit an argument to a higher-order rule call, wrapped as () => Option[Any].
    def emitArgAsLambdaIr(arg: Ir.Expr): Either[GenerationError, String] = arg match {
      case Ir.Expr.ParamRef(i, _) => Right(s"__p$i")              // pass the thunk through
      case Ir.Expr.RuleRef(id)    => Right(s"() => ${id.mangled}()")
      case _                      => emitIr(arg).map(c => s"() => $c")
    }

    // Generate all rule methods — always via Lowering + IR (incl. higher-order).
    val emittedRules: Either[GenerationError, List[String]] =
      Lowering.lower(grammar, startRule, Lowering.LowerOptions(typeCheck = false)) match {
        case Left(d) =>
          Left(GenerationError(d.position.getOrElse(Ast.DUMMY_POSITION), d.message, d.hint))
        case Right(program) =>
          program.syntactic.rules.toList.foldLeft[Either[GenerationError, List[String]]](Right(Nil)) { (acc, rule) =>
            for { lines <- acc; bodyCode <- emitIr(rule.body) } yield {
              val paramList = rule.params.indices.map(i => s"__p$i: () => Option[Any]").mkString(", ")
              lines :+ s"  def ${rule.id.mangled}($paramList): Option[Any] = $bodyCode"
            }
          }
      }

    emittedRules.map { ruleDefs =>
      val packagePrefix  = packageName.map(p => s"package $p\n\n").getOrElse("")
      val headPreamble   = if (grammar.preamble.nonEmpty) grammar.preamble + "\n" else ""
      val startRuleName  = ruleNameMap(startRule)
      val rulesText      = ruleDefs.mkString("\n\n")
      s"""${packagePrefix}object $objectName {
         |$headPreamble
         |  /** Local tilde for sequential parse result pairing. */
         |  case class ~[+A, +B](_1: A, _2: B)
         |
         |  /** Thrown by the cut operator ^ to prevent alternation backtracking. */
         |  private class __CommittedFailure(val failPos: Int)
         |    extends RuntimeException(s"committed failure at position $$failPos")
         |
         |  private var input: String = ""
         |  private var pos: Int = 0
         |
         |  def flatText(x: Any): String = x match {
         |    case a ~ b          => flatText(a) + flatText(b)
         |    case xs: List[?]    => xs.map(flatText).mkString
         |    case Some(v)        => flatText(v)
         |    case None           => ""
         |    case ()             => ""
         |    case s: String      => s
         |    case c: Char        => c.toString
         |    case _              => ""
         |  }
         |
         |  def parseIntLit(raw: Any): BigInt = {
         |    val s = flatText(raw).trim.filter(_ != '_')
         |    if      (s.startsWith("0x") || s.startsWith("0X")) BigInt(s.drop(2), 16)
         |    else if (s.startsWith("0b") || s.startsWith("0B")) BigInt(s.drop(2), 2)
         |    else if (s.startsWith("0o") || s.startsWith("0O")) BigInt(s.drop(2), 8)
         |    else if (s.startsWith("0d") || s.startsWith("0D")) BigInt(s.drop(2), 10)
         |    else if (s.length > 1 && s.startsWith("0"))        BigInt(s.drop(1), 8)
         |    else                                                BigInt(s)
         |  }
         |
         |  def parseFloatLit(raw: Any): Double = flatText(raw).trim.filter(_ != '_').toDouble
         |
         |  def flattenTilde(x: Any): List[Any] = x match {
         |    case a ~ b => flattenTilde(a) ::: List(b)
         |    case other => List(other)
         |  }
         |
         |  def toExpr(x: Any): com.github.kmizu.macro_peg.ruby.RubyAst.Expr = x match {
         |    case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e
         |    case _ =>
         |      flattenTilde(x).collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(com.github.kmizu.macro_peg.ruby.RubyAst.StringLiteral(flatText(x).trim))
         |  }
         |
         |  def toExprList(xs: List[Any]): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] =
         |    xs.map(toExpr)
         |
         |  def toStmtList(xs: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = xs match {
         |    case list: List[?] => list.flatMap(x => toStmtList(x))
         |    case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |    case Some(inner) => toStmtList(inner)
         |    case None => Nil
         |    case a ~ (b: Option[?]) => toStmtList(a) ++ b.map(x => toStmtList(x)).getOrElse(Nil)
         |    case a ~ (b: List[?]) => toStmtList(a) ++ b.flatMap(x => toStmtList(x))
         |    case a ~ b =>
         |      val bStmts = b match {
         |        case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |        case _ => Nil
         |      }
         |      toStmtList(a) ++ bStmts
         |    case _ => Nil
         |  }
         |
         |  def foldBinOps(raw: Any): Any = raw match {
         |    case lhs ~ (ops: List[?]) =>
         |      ops.foldLeft(toExpr(lhs)) { case (acc, item) =>
         |        val parts = flattenTilde(item)
         |        val rhs = toExpr(parts.last)
         |        val opText = flatText(parts.dropRight(1)).trim
         |        com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(acc, opText, rhs)
         |      }
         |    case other => other
         |  }
         |
         |  def foldPowerOp(raw: Any): Any = raw match {
         |    case base ~ Some(opAndRhs) =>
         |      val parts = flattenTilde(opAndRhs)
         |      val rhs = toExpr(parts.last)
         |      com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(toExpr(base), "**", rhs)
         |    case base ~ None => toExpr(base)
         |    case other => other
         |  }
         |
         |  def foldTernary(raw: Any): Any = raw match {
         |    case cond ~ Some(rest) =>
         |      val parts = flattenTilde(rest)
         |      if (parts.length >= 8) {
         |        val thenExpr = toExpr(parts(3))
         |        val elseExpr = toExpr(parts(7))
         |        com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(
         |          toExpr(cond),
         |          List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(thenExpr)),
         |          List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(elseExpr))
         |        )
         |      } else toExpr(cond)
         |    case cond ~ None => toExpr(cond)
         |    case other => other
         |  }
         |
         |  def extractCommaSep(raw: Any): List[Any] = raw match {
         |    case first ~ (rest: List[?]) =>
         |      first :: rest.map { item => flattenTilde(item).last }
         |    case single => List(single)
         |  }
         |
         |  def foldRange(raw: Any): Any = raw match {
         |    case start ~ Some(opAndEnd) =>
         |      val parts = flattenTilde(opAndEnd)
         |      val opText = flatText(parts.head).trim
         |      val exclusive = opText == "..."
         |      val endExpr = parts.last match {
         |        case Some(e) => toExpr(e)
         |        case None    => com.github.kmizu.macro_peg.ruby.RubyAst.NilLiteral()
         |        case e       => toExpr(e)
         |      }
         |      com.github.kmizu.macro_peg.ruby.RubyAst.RangeExpr(toExpr(start), endExpr, exclusive)
         |    case start ~ None => toExpr(start)
         |    case other => other
         |  }
         |
         |  def beginlessRange(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val opText = flatText(parts.head).trim
         |    val exclusive = opText == "..."
         |    com.github.kmizu.macro_peg.ruby.RubyAst.RangeExpr(
         |      com.github.kmizu.macro_peg.ruby.RubyAst.NilLiteral(),
         |      toExpr(parts.last),
         |      exclusive
         |    )
         |  }
         |
         |  def buildArrayLiteral(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val innerOpt = if (parts.length >= 3) parts(2) else None
         |    val elems: List[Any] = innerOpt match {
         |      case Some(inner) =>
         |        val ip = flattenTilde(inner)
         |        val first = ip.head
         |        val rest: List[Any] = ip.lift(1) match {
         |          case Some(xs: List[?]) => xs.map { item => flattenTilde(item).last }
         |          case _ => Nil
         |        }
         |        first :: rest
         |      case None => Nil
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ArrayLiteral(elems.map(toExpr))
         |  }
         |
         |  def buildReturnStmt(raw: Any): Any = raw match {
         |    case _ ~ Some(spaceAndArgs) =>
         |      val parts = flattenTilde(spaceAndArgs)
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(Some(toExpr(parts.last)))
         |    case _ ~ None =>
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(None)
         |    case _ =>
         |      com.github.kmizu.macro_peg.ruby.RubyAst.Return(None)
         |  }
         |
         |  def buildAssignExpr(raw: Any): Any = raw match {
         |    case lhs ~ Some(opAndRhs) =>
         |      val parts = flattenTilde(opAndRhs)
         |      val op = flatText(parts.head).trim
         |      val rhs = toExpr(parts.last)
         |      val lhsName = lhs match {
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.LocalVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.InstanceVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.ClassVar(n, _) => n
         |        case com.github.kmizu.macro_peg.ruby.RubyAst.GlobalVar(n, _) => n
         |        case _ => flatText(lhs).trim
         |      }
         |      if (op == "=") {
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, rhs)
         |      } else {
         |        val baseOp = op.stripSuffix("=")
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(
         |          lhsName,
         |          com.github.kmizu.macro_peg.ruby.RubyAst.BinaryOp(toExpr(lhs), baseOp, rhs)
         |        )
         |      }
         |    case lhs ~ None => toExpr(lhs)
         |    case other => other
         |  }
         |
         |  def buildMultiAssignStmt(raw: Any): Any = {
         |    val (lhsResult, rhsResult) = raw match {
         |      case ((((l ~ _) ~ _) ~ _) ~ r) => (l, r)
         |      case other => (other, other)
         |    }
         |    val (firstLhs, moreLhsList) = lhsResult match {
         |      case a ~ (b: List[?]) => (a, b)
         |      case other => (other, Nil)
         |    }
         |    val firstName = flatText(firstLhs).trim
         |    val moreNames: List[String] = moreLhsList.map(item => flatText(flattenTilde(item).last).trim).filter(_.nonEmpty)
         |    val names = (firstName :: moreNames).filter(_.nonEmpty)
         |    val rhsInner = rhsResult match { case a ~ _ => a; case other => other }
         |    val (firstRhsRaw, moreRhsList) = rhsInner match {
         |      case a ~ (b: List[?]) => (a, b)
         |      case other => (other, Nil)
         |    }
         |    val firstRhs = toExpr(firstRhsRaw)
         |    val moreRhs: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = moreRhsList.map(item => toExpr(flattenTilde(item).last))
         |    if (names.size == 1 && moreRhs.isEmpty) {
         |      com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(names.head, firstRhs)
         |    } else {
         |      com.github.kmizu.macro_peg.ruby.RubyAst.MultiAssignExpr(names, com.github.kmizu.macro_peg.ruby.RubyAst.ArrayLiteral(firstRhs :: moreRhs))
         |    }
         |  }
         |
         |  def buildCommandCall(name: Any, args: Any): Any = {
         |    val methodName = flatText(name).trim
         |    val argList = extractArgList(args)
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, methodName, argList)
         |  }
         |
         |  def appendCommandArgsToExpr(target: com.github.kmizu.macro_peg.ruby.RubyAst.Expr, args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr]): com.github.kmizu.macro_peg.ruby.RubyAst.Expr = {
         |    if (args.isEmpty) return target
         |    target match {
         |      case call @ com.github.kmizu.macro_peg.ruby.RubyAst.Call(receiver, methodName, existingArgs, span) =>
         |        if (existingArgs.isEmpty) com.github.kmizu.macro_peg.ruby.RubyAst.Call(receiver, methodName, args, span)
         |        else com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(call), "call", args)
         |      case com.github.kmizu.macro_peg.ruby.RubyAst.LocalVar(name, span) =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, name, args, span)
         |      case com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, rhs, _) =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.AssignExpr(lhsName, appendCommandArgsToExpr(rhs, args))
         |      case other =>
         |        com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(other), "call", args)
         |    }
         |  }
         |
         |  def buildExprWithCmdArgs(baseRaw: Any, tailRaw: Any): Any = {
         |    val base = toExpr(baseRaw)
         |    tailRaw match {
         |      case None => base
         |      case Some(tail) =>
         |        val parts = flattenTilde(tail)
         |        // parts: [(), "", CommandArgList_result, Block?_result]
         |        // guard contributes 2 flat elements: () from AndPred and "" from NotPred
         |        val argsPart = if (parts.length >= 3) parts(2) else null
         |        val args = if (argsPart != null) extractArgList(argsPart) else Nil
         |        appendCommandArgsToExpr(base, args)
         |      case _ => base
         |    }
         |  }
         |
         |  def buildHashEntry(entry: Any): (com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr) = {
         |    val parts = flattenTilde(entry)
         |    val hasRocket = parts.exists(p => flatText(p).trim == "=>")
         |    if (hasRocket) {
         |      (toExpr(parts.head), toExpr(parts.last))
         |    } else {
         |      val key: com.github.kmizu.macro_peg.ruby.RubyAst.Expr = parts.head match {
         |        case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e
         |        case raw =>
         |          val s = flatText(raw).trim.stripSuffix(":")
         |          com.github.kmizu.macro_peg.ruby.RubyAst.SymbolLiteral(s, com.github.kmizu.macro_peg.ruby.RubyAst.UnknownSpan)
         |      }
         |      (key, toExpr(parts.last))
         |    }
         |  }
         |
         |  def buildHashLiteral(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val entries: List[(com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr)] =
         |      parts.lift(2) match {
         |        case Some(Some(body)) =>
         |          val bp = flattenTilde(body)
         |          val first = buildHashEntry(bp.head)
         |          val rest: List[(com.github.kmizu.macro_peg.ruby.RubyAst.Expr, com.github.kmizu.macro_peg.ruby.RubyAst.Expr)] =
         |            bp.lift(1) match {
         |              case Some(xs: List[?]) => xs.map { item => buildHashEntry(flattenTilde(item).last) }
         |              case _ => Nil
         |            }
         |          first :: rest
         |        case _ => Nil
         |      }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.HashLiteral(entries)
         |  }
         |
         |  def extractArgList(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = raw match {
         |    case Some(inner) => extractArgListInner(inner)
         |    case None => Nil
         |    case _ => extractArgListInner(raw)
         |  }
         |
         |  def extractArgListInner(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = {
         |    val parts = flattenTilde(raw)
         |    parts.length match {
         |      case 1 => List(toExpr(parts.head))
         |      case _ =>
         |        val first = toExpr(parts.head)
         |        val rest: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.lift(1) match {
         |          case Some(xs: List[?]) => xs.map { item => toExpr(flattenTilde(item).last) }
         |          case _ => Nil
         |        }
         |        first :: rest
         |    }
         |  }
         |
         |  def collectStmts(raw: Any): List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = {
         |    val parts = flattenTilde(raw)
         |    val repeated: List[Any] = parts.head match {
         |      case xs: List[?] => xs.map { item => flattenTilde(item).head }
         |      case _ => Nil
         |    }
         |    val lastOpt: List[Any] = parts.lift(1) match {
         |      case Some(Some(s)) => List(s)
         |      case _ => Nil
         |    }
         |    (repeated ++ lastOpt).flatMap {
         |      case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |      case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr =>
         |        List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(e))
         |      case _ => Nil
         |    }
         |  }
         |
         |  def buildIfExpr(cond: Any, body: Any, elsifs: Any, elseOpt: Any): Any = {
         |    val condExpr = toExpr(cond)
         |    val thenBody = toStmtList(body)
         |    val elsifList: List[Any] = elsifs match {
         |      case xs: List[?] => xs
         |      case _ => Nil
         |    }
         |    val elseBody: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = elseOpt match {
         |      case Some(inner) =>
         |        flattenTilde(inner).collectFirst { case stmts: List[?] => stmts }
         |          .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |          .getOrElse(toStmtList(inner))
         |      case _ => Nil
         |    }
         |    val fullElse = elsifList.foldRight(elseBody) { case (elsif, acc) =>
         |      val ep = flattenTilde(elsif)
         |      val ec = ep.collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(toExpr(elsif))
         |      val eb = ep.collectFirst { case stmts: List[?] => stmts }
         |        .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |        .getOrElse(Nil)
         |      List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(
         |        com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(ec, eb, acc)
         |      ))
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.IfExpr(condExpr, thenBody, fullElse)
         |  }
         |
         |  def buildUnlessExpr(cond: Any, body: Any, elseOpt: Any): Any = {
         |    val condExpr = toExpr(cond)
         |    val thenBody = toStmtList(body)
         |    val elseBody: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = elseOpt match {
         |      case Some(inner) =>
         |        flattenTilde(inner).collectFirst { case stmts: List[?] => stmts }
         |          .map(_.asInstanceOf[List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement]])
         |          .getOrElse(toStmtList(inner))
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.UnlessExpr(condExpr, thenBody, elseBody)
         |  }
         |
         |  def buildWhileExpr(cond: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.WhileExpr(toExpr(cond), toStmtList(body))
         |
         |  def buildUntilExpr(cond: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.UntilExpr(toExpr(cond), toStmtList(body))
         |
         |  def buildProgram(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    // parts(0)=Spacing, parts(1)=TopStatements, parts(2)=!.
         |    // TopStatements = ~(List[seps], Some(~(~(firstStmt, List[(seps~stmt)*]), List[seps])) | None)
         |    val stmts: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = parts.lift(1) match {
         |      case Some(topStmtsRaw) =>
         |        val tsParts = flattenTilde(topStmtsRaw) // [List[seps], Some(stmtChain) | None]
         |        tsParts.lift(1) match {
         |          case Some(Some(stmtChain)) =>
         |            // stmtChain = ~(~(firstStmt, moreList), trailSeps)
         |            // flattenTilde gives [...firstStmtParts..., moreList, trailSeps]
         |            val scParts = flattenTilde(stmtChain)
         |            // moreList is second-to-last; firstStmtParts are everything before it
         |            val moreList: List[Any] = scParts.dropRight(1).lastOption match {
         |              case Some(xs: List[?]) => xs
         |              case _ => Nil
         |            }
         |            val firstStmtParts = scParts.dropRight(2)
         |            val more: List[Any] = moreList.map(item => flattenTilde(item).last)
         |            toStmtList(firstStmtParts) ++ more.flatMap(x => toStmtList(x))
         |          case _ => Nil
         |        }
         |      case _ => Nil
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Program(stmts)
         |  }
         |
         |  def extractParamNames(raw: Any): List[String] = raw match {
         |    case Some(inner) =>
         |      val parts = flattenTilde(inner)
         |      val first = parts.head
         |      val rest: List[Any] = parts.lift(1) match {
         |        case Some(xs: List[?]) => xs.map(item => flattenTilde(item).last)
         |        case _ => Nil
         |      }
         |      (first :: rest).map(flatText(_).trim).filter(_.nonEmpty)
         |    case None => Nil
         |    case _ => Nil
         |  }
         |
         |  def buildPostfixExpr(raw: Any): Any = raw match {
         |    case base ~ (suffixes: List[?]) if suffixes.nonEmpty =>
         |      suffixes.foldLeft(toExpr(base)) { (acc, suffix) =>
         |        val parts = flattenTilde(suffix)
         |        val firstText = flatText(parts.head).trim
         |        if (firstText == "[") {
         |          val args = parts.lift(2).map(extractArgList).getOrElse(Nil)
         |          com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(acc), "[]", args)
         |        } else if (firstText == "." || firstText == "::" || firstText == "&.") {
         |          val methodName = flatText(parts.lift(1).getOrElse("")).trim
         |          val args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.lift(2) match {
         |            case Some(Some(inner)) => extractArgListInner(inner)
         |            case _ => Nil
         |          }
         |          com.github.kmizu.macro_peg.ruby.RubyAst.Call(Some(acc), methodName, args)
         |        } else {
         |          acc
         |        }
         |      }
         |    case base ~ _ => toExpr(base)
         |    case other => other
         |  }
         |
         |  def buildFunctionCall(raw: Any): Any = {
         |    val parts = flattenTilde(raw)
         |    val name = flatText(parts.head).trim
         |    val args: List[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = parts.last match {
         |      case Some(inner) => extractArgList(Some(inner))
         |      case _ => extractArgList(parts.last)
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Call(None, name, args)
         |  }
         |
         |  def buildDefExpr(name: Any, params: Any, body: Any): Any = {
         |    val defName = flatText(name).trim
         |    val paramList = params match {
         |      case Some(inner) => extractParamNames(Some(inner))
         |      case _ => Nil
         |    }
         |    val bodyStmts: List[com.github.kmizu.macro_peg.ruby.RubyAst.Statement] = body match {
         |      case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr =>
         |        List(com.github.kmizu.macro_peg.ruby.RubyAst.ExprStmt(e))
         |      case s: com.github.kmizu.macro_peg.ruby.RubyAst.Statement => List(s)
         |      case _ => toStmtList(body)
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.Def(defName, paramList, bodyStmts)
         |  }
         |
         |  def buildClassExpr(nameOrReceiver: Any, body: Any): Any = {
         |    val bodyStmts = toStmtList(body)
         |    if (flatText(nameOrReceiver).trim.startsWith("<<")) {
         |      val e = flattenTilde(nameOrReceiver)
         |        .collectFirst { case e: com.github.kmizu.macro_peg.ruby.RubyAst.Expr => e }
         |        .getOrElse(com.github.kmizu.macro_peg.ruby.RubyAst.StringLiteral(flatText(nameOrReceiver).trim))
         |      com.github.kmizu.macro_peg.ruby.RubyAst.SingletonClassDef(e, bodyStmts)
         |    } else {
         |      val name = flatText(nameOrReceiver).trim
         |      com.github.kmizu.macro_peg.ruby.RubyAst.ClassDef(name, bodyStmts, com.github.kmizu.macro_peg.ruby.RubyAst.UnknownSpan, None)
         |    }
         |  }
         |
         |  def buildModuleExpr(name: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ModuleDef(flatText(name).trim, toStmtList(body))
         |
         |  def buildBeginExpr(body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.BeginRescue(toStmtList(body), Nil, Nil, Nil)
         |
         |  def buildCaseExpr(scrutineeOpt: Any, clauses: Any, elseOpt: Any): Any = {
         |    val scrutinee: Option[com.github.kmizu.macro_peg.ruby.RubyAst.Expr] = scrutineeOpt match {
         |      case Some(inner) => Some(toExpr(inner))
         |      case _ => None
         |    }
         |    com.github.kmizu.macro_peg.ruby.RubyAst.CaseExpr(scrutinee, Nil, Nil)
         |  }
         |
         |  def buildForExpr(vars: Any, iter: Any, body: Any): Any =
         |    com.github.kmizu.macro_peg.ruby.RubyAst.ForIn(
         |      flatText(vars).trim, toExpr(iter), toStmtList(body)
         |    )
         |
         |$rulesText
         |
         |  def parse(s: String): Either[String, Any] = {
         |    input = s; pos = 0
         |    try {
         |      $startRuleName() match {
         |        case Some(v) if pos == input.length => Right(v)
         |        case _ => Left(s"parse failed at position $$pos")
         |      }
         |    } catch {
         |      case cf: __CommittedFailure => Left(s"committed parse failure at position $${cf.failPos}")
         |    }
         |  }
         |
         |  def parseAll(s: String): Either[String, Any] = parse(s)
         |}
         |""".stripMargin
    }
  }

  private def generateInterpreterBackend(
    source: String,
    objectName: String,
    packageName: Option[String],
    startRule: Symbol
  ): String = {
    val packagePrefix = packageName.map(p => s"package $p\n\n").getOrElse("")
    val start = escapeString(startRule.name)
    val sourceLiteral = "\"" + escapeString(source) + "\""
    s"""${packagePrefix}import com.github.kmizu.macro_peg._
       |
       |object $objectName {
       |  private val grammarSource: String = $sourceLiteral
       |  private val interpreterCache = scala.collection.mutable.Map.empty[EvaluationStrategy, Either[Diagnostic, Interpreter]]
       |
       |  private def interpreterEither(strategy: EvaluationStrategy): Either[Diagnostic, Interpreter] =
       |    interpreterCache.getOrElseUpdate(strategy, Interpreter.fromSourceEither(grammarSource, strategy))
       |
       |  def evaluate(
       |    input: String,
       |    start: Symbol = Symbol("$start"),
       |    strategy: EvaluationStrategy = EvaluationStrategy.CallByName
       |  ): Either[Diagnostic, EvaluationResult.Success] =
       |    interpreterEither(strategy).flatMap(_.evaluateEither(input, start))
       |
       |  def parse(
       |    input: String,
       |    start: Symbol = Symbol("$start"),
       |    strategy: EvaluationStrategy = EvaluationStrategy.CallByName
       |  ): Either[Diagnostic, EvaluationResult.Success] =
       |    evaluate(input, start, strategy)
       |
       |  def parseAll(
       |    input: String,
       |    start: Symbol = Symbol("$start"),
       |    strategy: EvaluationStrategy = EvaluationStrategy.CallByName
       |  ): Either[String, String] =
       |    evaluate(input, start, strategy).map(_.remained).left.map(_.format)
       |}
       |""".stripMargin
  }

  private def isFirstOrder(grammar: Grammar): Boolean = {
    grammar.rules.forall(r => r.args.isEmpty && !containsHigherOrder(r.body))
  }

  private def containsHigherOrder(exp: Expression): Boolean = exp match {
    case Sequence(_, l, r) => containsHigherOrder(l) || containsHigherOrder(r)
    case Alternation(_, l, r) => containsHigherOrder(l) || containsHigherOrder(r)
    case Repeat0(_, b) => containsHigherOrder(b)
    case Repeat1(_, b) => containsHigherOrder(b)
    case Optional(_, b) => containsHigherOrder(b)
    case AndPredicate(_, b) => containsHigherOrder(b)
    case NotPredicate(_, b) => containsHigherOrder(b)
    case Call(_, _, args) => args.nonEmpty || args.exists(containsHigherOrder)
    case Function(_, _, _) => true
    case Debug(_, b) => containsHigherOrder(b)
    case Labeled(_, _, b) => containsHigherOrder(b)
    case SemanticAction(_, _) => false
    case _ => false
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

  private def renderGrammar(grammar: Grammar): String = {
    grammar.rules.map(renderRule).mkString("\n")
  }

  private def renderRule(rule: Rule): String = {
    val argsText =
      if(rule.args.isEmpty) ""
      else {
        val args = rule.args.zipWithIndex.map { case (argName, i) =>
          rule.argTypes.lift(i).flatten match {
            case Some(tpe) => s"${argName.name}: ${renderType(tpe)}"
            case None => argName.name
          }
        }.mkString(", ")
        s"($args)"
      }
    s"${rule.name.name}$argsText = ${renderExpression(rule.body)};"
  }

  private def renderType(tpe: Type): String = tpe match {
    case SimpleType(_) => "?"
    case RuleType(_, paramTypes, resultType) =>
      val params = paramTypes.map(renderType).mkString(", ")
      s"($params) -> ${renderType(resultType)}"
  }

  private def renderExpression(exp: Expression): String = exp match {
    case Sequence(_, l, r) => s"(${renderExpression(l)} ${renderExpression(r)})"
    case Alternation(_, l, r) => s"(${renderExpression(l)} / ${renderExpression(r)})"
    case Repeat0(_, b) => s"(${renderExpression(b)})*"
    case Repeat1(_, b) => s"(${renderExpression(b)})+"
    case Optional(_, b) => s"(${renderExpression(b)})?"
    case AndPredicate(_, b) => s"&(${renderExpression(b)})"
    case NotPredicate(_, b) => s"!(${renderExpression(b)})"
    case StringLiteral(_, target) => "\"" + escapeString(target) + "\""
    case Wildcard(_) => "."
    case CharClass(_, positive, elems) => renderCharClass(positive, elems)
    case CharSet(_, positive, elems) =>
      val sorted = elems.toList.sorted
      val body = sorted.map(ch => unicodeEscape(ch)).mkString
      if(positive) s"[$body]" else s"[^$body]"
    case Debug(_, b) => s"Debug(${renderExpression(b)})"
    case Identifier(_, name) => name.name
    case Call(_, name, args) =>
      s"${name.name}(${args.map(renderExpression).mkString(", ")})"
    case Function(_, args, body) =>
      s"(${args.map(_.name).mkString(", ")} -> ${renderExpression(body)})"
    case ActionBlock(_, b, code) => s"(${renderExpression(b)} => { $code })"
    case LeftProject(_, l, r) => s"(${renderExpression(l)} <~ ${renderExpression(r)})"
    case RightProject(_, l, r) => s"(${renderExpression(l)} ~> ${renderExpression(r)})"
    case Labeled(_, label, body) => s"$label:${renderExpression(body)}"
    case Cut(_, body) => s"^ ${renderExpression(body)}"
    case SemanticAction(_, code) => "${ " + code + " }"
  }

  private def renderCharClass(positive: Boolean, elems: List[CharClassElement]): String = {
    val body = elems.map {
      case CharRange(from, to) => s"${unicodeEscape(from)}-${unicodeEscape(to)}"
      case OneChar(ch) => unicodeEscape(ch)
    }.mkString
    if(positive) s"[$body]" else s"[^$body]"
  }

  private def unicodeEscape(ch: Char): String = "\\u%04x".format(ch.toInt)

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
