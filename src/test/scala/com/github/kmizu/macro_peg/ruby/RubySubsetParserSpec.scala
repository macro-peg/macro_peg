package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.ruby.RubyAst._
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class RubySubsetParserSpec extends AnyFunSpec with Diagrams {
  describe("RubySubsetParser") {
    it("parses calls and binary expressions into AST") {
      val input = "foo(1, 2).bar(3); x = 1 + 2 * 3"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(Call(None, "foo", List(IntLiteral(1), IntLiteral(2)))),
              "bar",
              List(IntLiteral(3))
            )
          ),
          Assign(
            "x",
            BinaryOp(
              IntLiteral(1),
              "+",
              BinaryOp(IntLiteral(2), "*", IntLiteral(3))
            )
          )
        ))
      )
    }

    it("parses class/def structure and nested statements") {
      val input = "class User; def greet(name); \"hi\"; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ClassDef(
            "User",
            List(
              Def(
                "greet",
                List("name"),
                List(ExprStmt(StringLiteral("hi")))
              )
            )
          )
        ))
      )
    }

    it("parses def parameters without parentheses") {
      val input = "def add as; as; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "add",
            List("as"),
            List(ExprStmt(LocalVar("as", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses array and hash literals") {
      val input = "x = [1, 2, {\"a\" => 3}]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            ArrayLiteral(List(
              IntLiteral(1),
              IntLiteral(2),
              HashLiteral(List(StringLiteral("a") -> IntLiteral(3)))
            ))
          )
        ))
      )
    }

    it("parses hash literals with punctuated label keys") {
      val input = "x = {a?: true, b!: false}"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses hash literals with quoted label keys") {
      val input = """x = {"a-b": true}"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses hash literals with newline after label colon") {
      val input =
        """x = {a:
          |  1}""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses keyword args with newline after label colon") {
      val input =
        """foo(a:
          |  1)""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with splat elements") {
      val input = "x = [*head, 1]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            ArrayLiteral(List(
              LocalVar("head", UnknownSpan),
              IntLiteral(1, UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses parenthesized postfix if expression in splat array element") {
      val input = "x = [*((params.rest.name || :*) if params.rest && !params.rest.is_a?(ImplicitRestNode))]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses array literals with local assignment elements") {
      val input = """a = ["", src="", ec, nil, 50, :partial_input=>true]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("returns parse failure for broken syntax") {
      val input = "def greet(; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isLeft)
    }

    it("parses module with if/unless and symbols") {
      val input = "module M; if flag; :ok; else; :ng; end; unless done; x = :wait; else; x = :done; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ModuleDef(
            "M",
            List(
              IfExpr(
                LocalVar("flag"),
                List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
                List(ExprStmt(SymbolLiteral("ng", UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnlessExpr(
                LocalVar("done"),
                List(Assign("x", SymbolLiteral("wait", UnknownSpan), UnknownSpan)),
                List(Assign("x", SymbolLiteral("done", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses class definition on rhs of logical and") {
      val input = "ready and class C; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("supports RubyFullParser entrypoint") {
      val input = "x = :\"hello\""
      val parsed = RubyFullParser.parse(input)
      assert(parsed == Right(Program(List(Assign("x", SymbolLiteral("hello", UnknownSpan), UnknownSpan)), UnknownSpan)))
    }

    it("parses if with elsif chain") {
      val input = "if a; :x; elsif b; :y; else; :z; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("a"),
            List(ExprStmt(SymbolLiteral("x", UnknownSpan), UnknownSpan)),
            List(
              IfExpr(
                LocalVar("b"),
                List(ExprStmt(SymbolLiteral("y", UnknownSpan), UnknownSpan)),
                List(ExprStmt(SymbolLiteral("z", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses postfix if/unless modifiers") {
      val input = "x = 1 if ready; y = 2 unless done"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(LocalVar("ready"), List(Assign("x", IntLiteral(1), UnknownSpan)), Nil, UnknownSpan),
          UnlessExpr(LocalVar("done"), List(Assign("y", IntLiteral(2), UnknownSpan)), Nil, UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses postfix rescue modifier") {
      val input = "r.close rescue nil"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          BeginRescue(
            List(ExprStmt(Call(Some(LocalVar("r", UnknownSpan)), "close", Nil, UnknownSpan), UnknownSpan)),
            List(
              RescueClause(
                Nil,
                None,
                List(ExprStmt(NilLiteral(UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            Nil,
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses parenthesized rescue expression in receiver chain") {
      val input = "v = (foo rescue $!).local_variables"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses postfix while modifier on begin-rescue block") {
      val input =
        """begin
          |  x = 1
          |rescue E
          |  x = 2
          |end while ready""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          WhileExpr(
            LocalVar("ready", UnknownSpan),
            List(
              BeginRescue(
                List(Assign("x", IntLiteral(1, UnknownSpan), UnknownSpan)),
                List(
                  RescueClause(
                    List(ConstRef(List("E"), UnknownSpan)),
                    None,
                    List(Assign("x", IntLiteral(2, UnknownSpan), UnknownSpan)),
                    UnknownSpan
                  )
                ),
                Nil,
                Nil,
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style calls without parentheses") {
      val input = "puts :ok; add 1, 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "puts", List(SymbolLiteral("ok", UnknownSpan)), UnknownSpan),
            UnknownSpan
          ),
          ExprStmt(
            Call(None, "add", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses float literal in command-style call arguments") {
      val input = "sleep 0.1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "sleep", List(FloatLiteral(0.1, UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call args split across newlines") {
      val input =
        """assert_equal "a",
          |             "b"
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("a", UnknownSpan), StringLiteral("b", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call with parenthesized first arg and trailing args") {
      val input = """assert_equal (+"ア").force_encoding(Encoding::SHIFT_JIS), slice"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_equal",
              List(
                Call(
                  Some(
                    UnaryOp("+", StringLiteral("ア", UnknownSpan), UnknownSpan)
                  ),
                  "force_encoding",
                  List(ConstRef(List("Encoding", "SHIFT_JIS"), UnknownSpan)),
                  UnknownSpan
                ),
                LocalVar("slice", UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call with postfix modifier") {
      val input = "log 1, 2 if ready"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("ready"),
            List(ExprStmt(Call(None, "log", List(IntLiteral(1), IntLiteral(2)), UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command-style call inside def body") {
      val input = "def greet(name); puts name; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "greet",
            List("name"),
            List(
              ExprStmt(Call(None, "puts", List(LocalVar("name")), UnknownSpan), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver command-style calls without parentheses") {
      val input = "logger.info 1, 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(Some(LocalVar("logger")), "info", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses destructured block parameters") {
      val input = "tests.each do |(insn, expr, *a)| insn; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(LocalVar("tests", UnknownSpan)), "each", Nil, UnknownSpan),
              Block(
                List("(insn,expr,*a)"),
                List(ExprStmt(LocalVar("insn", UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses block parameter default without consuming closing pipe") {
      val input = "p = Proc.new{|b, c=42| :ok}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses block-local parameters after semicolon") {
      val input = "tap {|;x| x = x; break local_variables}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses block keyword defaults and trailing comma in params") {
      val input = "categories.map {|category, str: \"foo\", num: 424242, | [category, str, num] }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses multiline block parameters with anonymous rest") {
      val input =
        """tap do |_,
          |  bug6115 = '[ruby-dev:45308]',
          |  *|
          |  bug6115
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline block parameters with array defaults") {
      val input =
        """tap do |_,
          |  methods = [['map', 'no'], ['inject([])', 'with']],
          |  blocks = [['do end', 'do'], ['{}', 'brace']],
          |  *|
          |  methods
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses do-block parameters on the next line") {
      val input =
        """items.each do
          |  |b, e = 'end', pre = nil, post = nil|
          |  [b, e, pre, post]
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses brace-block parameters on the next line") {
      val input =
        """items.map {
          |  |s|
          |  s.to_s
          |}""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses do-end block attached to call") {
      val input = "items.each do |x| puts x; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(LocalVar("items")), "each", Nil, UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("does not confuse keyword prefixes in identifiers inside do-blocks") {
      val input =
        """items.each do |item|
          |  define_method("x")
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("does not split keyword-prefix local vars in chained calls") {
      val input = "method_node = class_node.body.body.first"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses brace block attached to call") {
      val input = "add(1, 2) { |x| puts x }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(None, "add", List(IntLiteral(1), IntLiteral(2)), UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses brace block with trailing space before close") {
      val input = "err_reader = Thread.new{ r.read }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses newline-separated statements") {
      val input =
        """x = 1
          |y = 2""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", IntLiteral(1), UnknownSpan),
          Assign("y", IntLiteral(2), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses newline-separated statements with trailing inline comment") {
      val input =
        """Foo.foo # comment
          |foo""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses receiver parenthesized call with multiline do-end block") {
      val input =
        """items.each(1) do |x|
          |  puts x
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(LocalVar("items")), "each", List(IntLiteral(1)), UnknownSpan),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses multiline if/else without semicolons") {
      val input =
        """if ready
          |  x = 1
          |else
          |  x = 2
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("ready"),
            List(Assign("x", IntLiteral(1), UnknownSpan)),
            List(Assign("x", IntLiteral(2), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses chained dot calls with no-arg methods in expressions") {
      val input = "x = user.profile.name"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(
                Call(
                  Some(LocalVar("user")),
                  "profile",
                  Nil,
                  UnknownSpan
                )
              ),
              "name",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses block attached to chained call expression") {
      val input = "foo.bar(1).baz do |x| puts x; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(
                Some(Call(Some(LocalVar("foo")), "bar", List(IntLiteral(1)), UnknownSpan)),
                "baz",
                Nil,
                UnknownSpan
              ),
              Block(
                List("x"),
                List(ExprStmt(Call(None, "puts", List(LocalVar("x")), UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses command call arg with spaced brace-block chain") {
      val input = "puts tests.map {|path| File.basename(path) }.inspect"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses safe-navigation call with operator method name") {
      val input = "x = timeout&.*(timeout_scale)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(LocalVar("timeout", UnknownSpan)),
              "*",
              List(LocalVar("timeout_scale", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses dot-call shorthand with anonymous keyword forwarding") {
      val input = "assert_equal(false, m.(**{}).frozen?)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses return statement in method body") {
      val input = "def f; return 1; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def("f", Nil, List(Return(Some(IntLiteral(1)), UnknownSpan)), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses return with multiple values") {
      val input = "def f; return out, err; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            Nil,
            List(
              Return(
                Some(ArrayLiteral(List(LocalVar("out", UnknownSpan), LocalVar("err", UnknownSpan)), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bare return with postfix modifier") {
      val input = "return if done"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("done"),
            List(Return(None, UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses return with postfix if and complex condition") {
      val input = """return if RUBY_ENGINE != "ruby""""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses parenthesized return with stacked postfix modifiers") {
      val input = "def obj.test; x = nil; _y = (return until x unless x); end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command arg starting with case expression") {
      val input =
        """puts case x
          |when 1 then :one
          |else :other
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses self expression and self receiver command call") {
      val input = "x = self.name; self.log 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(SelfExpr(UnknownSpan)), "name", Nil, UnknownSpan),
            UnknownSpan
          ),
          ExprStmt(
            Call(Some(SelfExpr(UnknownSpan)), "log", List(IntLiteral(1)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses keyword-named receiver method and chained command args") {
      val input = "x = self.class; self.class.add self"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(SelfExpr(UnknownSpan)), "class", Nil, UnknownSpan),
            UnknownSpan
          ),
          ExprStmt(
            Call(
              Some(Call(Some(SelfExpr(UnknownSpan)), "class", Nil, UnknownSpan)),
              "add",
              List(SelfExpr(UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver calls to self keyword method names") {
      val input = "assert_raise(RuntimeError){tp_store.self}"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver calls to begin/end keyword method names") {
      val input = "x = 1.step.begin; y = 1.step.end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(Call(Some(IntLiteral(1)), "step", Nil, UnknownSpan)), "begin", Nil, UnknownSpan),
            UnknownSpan
          ),
          Assign(
            "y",
            Call(Some(Call(Some(IntLiteral(1)), "step", Nil, UnknownSpan)), "end", Nil, UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses constant path expressions") {
      val input = "x = JSON::Parser"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", ConstRef(List("JSON", "Parser"), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses top-level constant path expressions") {
      val input = "x = ::JSON::Parser"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", ConstRef(List("JSON", "Parser"), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses :: method call on constant path receiver") {
      val input = "x = BOX1::BOX_B::yay"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(ConstRef(List("BOX1", "BOX_B"), UnknownSpan)), "yay", Nil, UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses module/class names with constant paths") {
      val input = "module A::B; class C::D; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ModuleDef(
            "A::B",
            List(ClassDef("C::D", Nil, UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses single-quoted string literals") {
      val input = "msg = 'ok'"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("msg", StringLiteral("ok"), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses adjacent string literals as one argument") {
      val input = """assert_equal("ab", "a" "b")"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("ab", UnknownSpan), StringLiteral("ab", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses percent-quoted string literals") {
      val input = "assert_equal %q{ok}, %{ng}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("ok"), StringLiteral("ng")), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bracket index access expression") {
      val input = "x = ENV[\"HOME\"]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(Some(ConstRef(List("ENV"), UnknownSpan)), "[]", List(StringLiteral("HOME")), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses percent-quoted strings with pipe delimiters") {
      val input = """assert_equal('u', %Q|\u{FC}|)"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses bracket call with keyword label arguments") {
      val input = "paths = Dir[glob_pattern, base: BASE]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "paths",
            Call(
              Some(ConstRef(List("Dir"), UnknownSpan)),
              "[]",
              List(
                LocalVar("glob_pattern", UnknownSpan),
                HashLiteral(List(SymbolLiteral("base", UnknownSpan) -> ConstRef(List("BASE"), UnknownSpan)), UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses class definition with superclass") {
      val input = "class Child < Parent; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ClassDef(
            "Child",
            Nil,
            UnknownSpan,
            Some(ConstRef(List("Parent"), UnknownSpan))
          )
        ), UnknownSpan)
      )
    }

    it("parses begin-rescue-ensure blocks") {
      val input =
        """begin
          |  x = 1
          |rescue Error => e
          |  x = 2
          |ensure
          |  x = 3
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          BeginRescue(
            List(Assign("x", IntLiteral(1), UnknownSpan)),
            List(
              RescueClause(
                List(ConstRef(List("Error"), UnknownSpan)),
                Some("e"),
                List(Assign("x", IntLiteral(2), UnknownSpan)),
                UnknownSpan
              )
            ),
            Nil,
            List(Assign("x", IntLiteral(3), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses do-end block with bare ensure section") {
      val input =
        """Thread.new do
          |  work
          |ensure
          |  cleanup
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            CallWithBlock(
              Call(Some(ConstRef(List("Thread"), UnknownSpan)), "new", Nil, UnknownSpan),
              Block(
                Nil,
                List(
                  BeginRescue(
                    List(ExprStmt(LocalVar("work", UnknownSpan), UnknownSpan)),
                    Nil,
                    Nil,
                    List(ExprStmt(LocalVar("cleanup", UnknownSpan), UnknownSpan)),
                    UnknownSpan
                  )
                ),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses do-end block with rescue clause") {
      val input =
        """Thread.new do
          |  work
          |rescue RangeError
          |  recover
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses def body with bare ensure section") {
      val input =
        """def f
          |  x = 1
          |ensure
          |  x = 2
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            Nil,
            List(
              BeginRescue(
                List(Assign("x", IntLiteral(1, UnknownSpan), UnknownSpan)),
                Nil,
                Nil,
                List(Assign("x", IntLiteral(2, UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses def body with rescue-else-ensure sections") {
      val input =
        """def f
          |  x = 1
          |rescue Error => e
          |  x = 2
          |else
          |  x = 3
          |ensure
          |  x = 4
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            Nil,
            List(
              BeginRescue(
                List(Assign("x", IntLiteral(1, UnknownSpan), UnknownSpan)),
                List(
                  RescueClause(
                    List(ConstRef(List("Error"), UnknownSpan)),
                    Some("e"),
                    List(Assign("x", IntLiteral(2, UnknownSpan), UnknownSpan)),
                    UnknownSpan
                  )
                ),
                List(Assign("x", IntLiteral(3, UnknownSpan), UnknownSpan)),
                List(Assign("x", IntLiteral(4, UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses def body with multiple rescue clauses") {
      val input =
        """def f
          |  x = 1
          |rescue E1
          |  x = 2
          |rescue E2 => err
          |  x = 3
          |ensure
          |  x = 4
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            Nil,
            List(
              BeginRescue(
                List(Assign("x", IntLiteral(1, UnknownSpan), UnknownSpan)),
                List(
                  RescueClause(
                    List(ConstRef(List("E1"), UnknownSpan)),
                    None,
                    List(Assign("x", IntLiteral(2, UnknownSpan), UnknownSpan)),
                    UnknownSpan
                  ),
                  RescueClause(
                    List(ConstRef(List("E2"), UnknownSpan)),
                    Some("err"),
                    List(Assign("x", IntLiteral(3, UnknownSpan), UnknownSpan)),
                    UnknownSpan
                  )
                ),
                Nil,
                List(Assign("x", IntLiteral(4, UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses singleton class definition") {
      val input =
        """class << self
          |  def x; 1; end
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          SingletonClassDef(
            SelfExpr(UnknownSpan),
            List(
              Def("x", Nil, List(ExprStmt(IntLiteral(1), UnknownSpan)), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses alias statement for operator method names in singleton class") {
      val input = "class C; class << self; alias [] new; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses alias for power operator method name") {
      val input = "class C; alias ** +; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses instance/class/global variables and assignments") {
      val input =
        """@x = 1
          |@@y = 2
          |$z = 3
          |w = @x + @@y
          |v = $z""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("@x", IntLiteral(1), UnknownSpan),
          Assign("@@y", IntLiteral(2), UnknownSpan),
          Assign("$z", IntLiteral(3), UnknownSpan),
          Assign("w", BinaryOp(InstanceVar("@x", UnknownSpan), "+", ClassVar("@@y", UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("v", GlobalVar("$z", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses special global variable $?") {
      val input = "status = $?"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("status", GlobalVar("$?", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses unicode local variable names") {
      val input = "α = 1 or flunk"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses dashed special global variables like $-0") {
      val input = "x = $-0; $-0 = \",\""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", GlobalVar("$-0", UnknownSpan), UnknownSpan),
          Assign("$-0", StringLiteral(",", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses multiple assignment from expression") {
      val input = "faildesc, t = super"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          MultiAssign(
            List("faildesc", "t"),
            LocalVar("super", UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses assignment whose rhs is def expression") {
      val input = "$def_retval_in_namespace = def boooo; \"boo\"; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("$def_retval_in_namespace", SymbolLiteral("boooo", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses for-in loop with range and compound assignment") {
      val input =
        """sum = 0
          |for x in (1..5)
          |  sum += x
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("sum", IntLiteral(0), UnknownSpan),
          ForIn(
            "x",
            RangeExpr(IntLiteral(1), IntLiteral(5), exclusive = false, UnknownSpan),
            List(
              Assign("sum", BinaryOp(LocalVar("sum", UnknownSpan), "+", LocalVar("x", UnknownSpan), UnknownSpan), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses open-ended range expressions") {
      val input = "a = items[1..]; b = items[..limit]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "a",
            Call(
              Some(LocalVar("items", UnknownSpan)),
              "[]",
              List(RangeExpr(IntLiteral(1, UnknownSpan), NilLiteral(UnknownSpan), exclusive = false, UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          ),
          Assign(
            "b",
            Call(
              Some(LocalVar("items", UnknownSpan)),
              "[]",
              List(RangeExpr(NilLiteral(UnknownSpan), LocalVar("limit", UnknownSpan), exclusive = false, UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses exclusive range expressions with hex end") {
      val input = "x = 0...0x100"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            RangeExpr(IntLiteral(0, UnknownSpan), IntLiteral(256, UnknownSpan), exclusive = true, UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses begin-rescue with retry") {
      val input =
        """begin
          |  require 'tmpdir'
          |rescue LoadError
          |  retry
          |end""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          BeginRescue(
            List(ExprStmt(Call(None, "require", List(StringLiteral("tmpdir")), UnknownSpan), UnknownSpan)),
            List(
              RescueClause(
                List(ConstRef(List("LoadError"), UnknownSpan)),
                None,
                List(Retry(UnknownSpan)),
                UnknownSpan
              )
            ),
            Nil,
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses no-arg bare call with block chained by method calls") {
      val input = "lambda{ 1 }.call.call"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(
                Call(
                  Some(
                    CallWithBlock(
                      LocalVar("lambda", UnknownSpan),
                      Block(Nil, List(ExprStmt(IntLiteral(1), UnknownSpan)), UnknownSpan),
                      UnknownSpan
                    )
                  ),
                  "call",
                  Nil,
                  UnknownSpan
                )
              ),
              "call",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses block-pass parameter and argument") {
      val input = "def each(&block); [1, 2].each(&block); end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "each",
            List("&block"),
            List(
              ExprStmt(
                Call(
                  Some(ArrayLiteral(List(IntLiteral(1), IntLiteral(2)), UnknownSpan)),
                  "each",
                  List(LocalVar("&block", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses splat and keyword-splat params and call args") {
      val input = "def f(*args, **opts, &block); g(*args, **opts, &block); end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            List("*args", "**opts", "&block"),
            List(
              ExprStmt(
                Call(
                  None,
                  "g",
                  List(LocalVar("args", UnknownSpan), LocalVar("opts", UnknownSpan), LocalVar("&block", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses keyword parameters with defaults in def") {
      val input = "def f(opt = '', timeout: BT.timeout, **argh); end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "f",
            List("opt", "timeout:", "**argh"),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses nameless rest parameters in def") {
      val input = "def method_missing(name, *); name; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "method_missing",
            List("name", "*"),
            List(ExprStmt(LocalVar("name", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses singleton def with parenthesized receiver expression") {
      val input = "def (o = Object.new).each; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses singleton def on nil receiver") {
      val input = "def nil.test_binding; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses forwarding (...) parameter and argument") {
      val input = "def method_missing(...); ::String.public_send(...); end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "method_missing",
            List("..."),
            List(
              ExprStmt(
                Call(
                  Some(ConstRef(List("String"), UnknownSpan)),
                  "public_send",
                  List(LocalVar("...", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bare visibility keyword call in class body") {
      val input = "class C; private; def f; true; end; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ClassDef(
            "C",
            List(
              ExprStmt(Call(None, "private", Nil, UnknownSpan), UnknownSpan),
              Def("f", Nil, List(ExprStmt(BoolLiteral(true, UnknownSpan), UnknownSpan)), UnknownSpan)
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses ruby2_keywords decorated def") {
      val input = "ruby2_keywords def foo(*args); args; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses bare ruby2_keywords call in class body") {
      val input = "class C; ruby2_keywords; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses private call with def expression argument") {
      val input = "private(def foo = :ok)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses lambda literal argument with bare parameter") {
      val input = "add_assertion testsrc, -> as do; as.id = 1; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "add_assertion",
              List(
                LocalVar("testsrc", UnknownSpan),
                LambdaLiteral(
                  List("as"),
                  List(
                    ExprStmt(
                      Call(
                        Some(LocalVar("as", UnknownSpan)),
                        "id=",
                        List(IntLiteral(1, UnknownSpan)),
                        UnknownSpan
                      ),
                      UnknownSpan
                    )
                  ),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses multiline lambda parameters") {
      val input =
        """lmd = ->(x,
          |         y,
          |         z) do
          |  z
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline def parameters") {
      val input =
        """def eval_with_jit(
          |  script,
          |  call_threshold: 1,
          |  timeout: 1000
          |)
          |  script
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline bare def parameters") {
      val input =
        """def source_location_test a=1,
          |  b=2
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses symbol block-pass argument") {
      val input = "ts.each(&:kill)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(LocalVar("ts", UnknownSpan)),
              "each",
              List(SymbolLiteral("kill", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses symbol block-pass argument with keyword symbol") {
      val input = "seq.first(5).map(&:class)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(Call(Some(LocalVar("seq", UnknownSpan)), "first", List(IntLiteral(5)), UnknownSpan)),
              "map",
              List(SymbolLiteral("class", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses method names with punctuation and unary/binary operators") {
      val input = "if !Dir.respond_to?(:mktmpdir) || force; ok! 1; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            BinaryOp(
              UnaryOp(
                "!",
                Call(
                  Some(ConstRef(List("Dir"), UnknownSpan)),
                  "respond_to?",
                  List(SymbolLiteral("mktmpdir", UnknownSpan)),
                  UnknownSpan
                ),
                UnknownSpan
              ),
              "||",
              LocalVar("force", UnknownSpan),
              UnknownSpan
            ),
            List(ExprStmt(Call(None, "ok!", List(IntLiteral(1)), UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses modulo operator in expression") {
      val input = "msg = '%.2f' % sec"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "msg",
            BinaryOp(
              StringLiteral("%.2f", UnknownSpan),
              "%",
              LocalVar("sec", UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses bare no-arg punctuated call in condition") {
      val input = "if block_given?; :ok; else; :ng; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            Call(None, "block_given?", Nil, UnknownSpan),
            List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
            List(ExprStmt(SymbolLiteral("ng", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver attribute assignment as expression statement") {
      val input = "self.columns = indent"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(Some(SelfExpr(UnknownSpan)), "columns=", List(LocalVar("indent", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses constant receiver assignment with postfix if modifier") {
      val input = "BT.tty = $stderr.tty? if BT.tty.nil?"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            Call(
              Some(Call(Some(ConstRef(List("BT"), UnknownSpan)), "tty", Nil, UnknownSpan)),
              "nil?",
              Nil,
              UnknownSpan
            ),
            List(
              ExprStmt(
                Call(
                  Some(ConstRef(List("BT"), UnknownSpan)),
                  "tty=",
                  List(
                    Call(Some(GlobalVar("$stderr", UnknownSpan)), "tty?", Nil, UnknownSpan)
                  ),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses chained receiver assignment") {
      val input = "parser.diagnostics.all_errors_are_fatal = true"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses consecutive opposite-side division expressions") {
      val input =
        """class X
          |  def t
          |    c = SimpleRat(1,3)
          |    assert_equal(SimpleRat(1,6), c / 2)
          |    assert_equal(SimpleRat(6,1), 2 / c)
          |  end
          |end
          |""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses division argument followed by slash-containing string argument") {
      val input = """f(c / a, "x / y")"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses shift expressions with assignment on the rhs") {
      val input = "objs << obj = Object.new"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses beginless range argument without treating it as forwarding arg") {
      val input = "assert_raise_with_message(*exc) {@o.clamp(...2)}"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver logical assignment inside condition") {
      val input = "if (self.columns ||= 0) < n; :ok; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            BinaryOp(
              BinaryOp(
                Call(Some(SelfExpr(UnknownSpan)), "columns", Nil, UnknownSpan),
                "||",
                IntLiteral(0, UnknownSpan),
                UnknownSpan
              ),
              "<",
              LocalVar("n", UnknownSpan),
              UnknownSpan
            ),
            List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses index logical assignment expression") {
      val input = "colors[n] ||= c"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(LocalVar("colors", UnknownSpan)),
              "[]=",
              List(
                LocalVar("n", UnknownSpan),
                BinaryOp(
                  Call(Some(LocalVar("colors", UnknownSpan)), "[]", List(LocalVar("n", UnknownSpan)), UnknownSpan),
                  "||",
                  LocalVar("c", UnknownSpan),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses index compound assignment expression") {
      val input = "memo[0] += i"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(LocalVar("memo", UnknownSpan)),
              "[]=",
              List(
                IntLiteral(0, UnknownSpan),
                BinaryOp(
                  Call(Some(LocalVar("memo", UnknownSpan)), "[]", List(IntLiteral(0, UnknownSpan)), UnknownSpan),
                  "+",
                  LocalVar("i", UnknownSpan),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver compound assignment") {
      val input = "self.columns += 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(SelfExpr(UnknownSpan)),
              "columns=",
              List(
                BinaryOp(
                  Call(Some(SelfExpr(UnknownSpan)), "columns", Nil, UnknownSpan),
                  "+",
                  IntLiteral(1, UnknownSpan),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver assignments to const-style and :: method names") {
      val input = "o.foo = o.Foo = o::baz = nil; o.bar = o.Bar = o::qux = 1"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver assignments to keyword-named methods") {
      val input = "o.begin = -10; o.end = 0"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses grouped multi-assign targets") {
      val input = "(x1.y1.z, x2.x5), _a = [r1, r2], 7"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses grouped multi-assign targets after splat targets") {
      val input = "*x2[1, 2, 3], (x3[4], x4.x5) = 6, 7, [r2, 8]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses nested grouped multi-assign targets") {
      val input = "((x1.y1.z, x1.x5), _a), *x2[1, 2, 3], ((x3[4], x4.x5), _b) = [[r1, 5], 10], 6, 7, [[r2, 8], 11]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses chained receiver logical assignments") {
      val input = "z.x.x ||= 1; z.x.x &&= 2"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses variable minus compound assignment") {
      val input = "w -= 1"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "w",
            BinaryOp(LocalVar("w", UnknownSpan), "-", IntLiteral(1, UnknownSpan), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses ternary operator in call arguments") {
      val input = "x = Test::JobServer.max_jobs(wn > 0 ? wn : 1024)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(ConstRef(List("Test", "JobServer"), UnknownSpan)),
              "max_jobs",
              List(
                IfExpr(
                  BinaryOp(LocalVar("wn", UnknownSpan), ">", IntLiteral(0, UnknownSpan), UnknownSpan),
                  List(ExprStmt(LocalVar("wn", UnknownSpan), UnknownSpan)),
                  List(ExprStmt(IntLiteral(1024, UnknownSpan), UnknownSpan)),
                  UnknownSpan
                )
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses receiver logical assignment with regex-match ternary") {
      val input = """BT.wn ||= /-j(\d+)?/ =~ (ENV["MAKEFLAGS"] || ENV["MFLAGS"]) ? $1.to_i : 1"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses ternary operator with newline before else branch") {
      val input =
        """"abc".gsub(/[ac]/) {
          |  $& == "a" ? "\xc2\xa1".force_encoding("euc-jp") :
          |              "\xc2\xa1".force_encoding("utf-8")
          |}""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses def name ending with question mark") {
      val input = "def empty?; true; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def("empty?", Nil, List(ExprStmt(BoolLiteral(true, UnknownSpan), UnknownSpan)), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses operator method names in def definitions") {
      val input =
        """class Box
          |  def [](n); @args[n]; end
          |  def []=(n, value); @args[n] = value; end
          |  def self.<=>(other); 0; end
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses nested percent-q parentheses strings") {
      val input = "x = %q( Object.const_defined?(:C) )"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", StringLiteral(" Object.const_defined?(:C) "), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses regular expression literal in call arguments") {
      val input = "assert_match /\\\\Aok\\\\z/, value"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_match", List(StringLiteral("\\\\Aok\\\\z"), LocalVar("value", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses regex literals that start with a space character") {
      val input = """s = pat2.gsub(/ /, "")"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses interpolated double-quoted strings with nested quotes") {
      val input = """x = "\e[;#{colors["pass"] || "32"}m""""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("x", StringLiteral("""e[;#{colors["pass"] || "32"}m""", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses backtick command literals with call chaining") {
      val input = "target_version = `#{BT.ruby} -v`.chomp"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "target_version",
            Call(
              Some(StringLiteral("#{BT.ruby} -v", UnknownSpan)),
              "chomp",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses percent-x command literals with call chaining") {
      val input = "x = %x(objdump --section=.text --syms #{path}).split(\"\\n\")"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            Call(
              Some(StringLiteral("objdump --section=.text --syms #{path}", UnknownSpan)),
              "split",
              List(StringLiteral("\n", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses symbol literals with variable-like names") {
      val input = "trace_var(:$a); trace_var(:@x); trace_var(:@@y)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("$a", UnknownSpan)), UnknownSpan), UnknownSpan),
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("@x", UnknownSpan)), UnknownSpan), UnknownSpan),
          ExprStmt(Call(None, "trace_var", List(SymbolLiteral("@@y", UnknownSpan)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses symbol literals with method suffix and operator names") {
      val input = "assert_operator(x, :!=, y); assert_predicate(value, :frozen?)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_operator",
              List(LocalVar("x", UnknownSpan), SymbolLiteral("!=", UnknownSpan), LocalVar("y", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          ),
          ExprStmt(
            Call(
              None,
              "assert_predicate",
              List(LocalVar("value", UnknownSpan), SymbolLiteral("frozen?", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses unicode bare symbol literals") {
      val input = "name = :ą"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("name", SymbolLiteral("ą", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses interpolated double-quoted symbol literals") {
      val input = """name = :"test_#{path}""""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("name", SymbolLiteral("test_#{path}", UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses uppercase percent-Q strings") {
      val input = "message = %Q{ENSURE\\n}"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("message", StringLiteral("ENSURE\\n"), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses percent word-array literals") {
      val input = "keywords = %w[break next redo]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "keywords",
            ArrayLiteral(List(
              StringLiteral("break"),
              StringLiteral("next"),
              StringLiteral("redo")
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses percent symbol-array literals") {
      val input = "keywords = %i[break next redo]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "keywords",
            ArrayLiteral(List(
              SymbolLiteral("break", UnknownSpan),
              SymbolLiteral("next", UnknownSpan),
              SymbolLiteral("redo", UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses regex match operators") {
      val input = "/freebsd/ =~ RUBY_PLATFORM or skip"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            BinaryOp(
              BinaryOp(
                StringLiteral("freebsd", UnknownSpan),
                "=~",
                ConstRef(List("RUBY_PLATFORM"), UnknownSpan),
                UnknownSpan
              ),
              "or",
              LocalVar("skip", UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses =begin/=end block comments as spacing") {
      val input =
        """=begin
          |generated
          |=end
          |ok 1
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "ok", List(IntLiteral(1)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses percent-r regex literals in command calls") {
      val input = """assert_match %r"Invalid #{keyword}\\b", value"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_match",
              List(StringLiteral("Invalid #{keyword}\\\\b"), LocalVar("value", UnknownSpan)),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses percent-r regex literals with colon delimiter") {
      val input = """assert_equal(':', %r:\::.source, bug5484)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline regex literals with flags") {
      val input =
        """if /\A(\S+)\s+
          |   \S+\z/x !~ line
          |  raise line
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call arguments with regex literals that start with spaces") {
      val input = """mount.scan(/ on (\S+) type (\S+) /) { mountpoints << [$1, $2] }"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses bracket arguments with x-flag regex literals that start with spaces") {
      val input = """"foobarbaz"[/  b  .  .  /x]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline or with command-call rhs") {
      val input =
        """/freebsd/ =~ RUBY_PLATFORM or
          |assert_finish 5, %q{ok}
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            BinaryOp(
              BinaryOp(
                StringLiteral("freebsd", UnknownSpan),
                "=~",
                ConstRef(List("RUBY_PLATFORM"), UnknownSpan),
                UnknownSpan
              ),
              "or",
              Call(None, "assert_finish", List(IntLiteral(5), StringLiteral("ok", UnknownSpan)), UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses multiline || continuation with unary rhs") {
      val input =
        """compare = !(current.is_a?(StringNode) ||
          |            current.is_a?(XStringNode)) ||
          |!current.opening&.start_with?("<<")
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses multiline + continuation in command arguments") {
      val input =
        """assert_equal expected, actual,
          |  "file: #{test.filename}, line #{test.line_number}, " +
          |  "type: #{test.type}"
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses backslash-newline continuation in expression") {
      val input =
        """x = 1 + \
          |  2
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses backslash-newline continuation inside lambda block") {
      val input =
        """assert_equal expected, actual, -> {
          |  "expected: #{expected.inspect}\n" \
          |  "actual: #{actual.inspect}"
          |}
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses label-style hash entries") {
      val input = "opts = { frozen_string_literal: true, mode: :strict }"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "opts",
            HashLiteral(List(
              SymbolLiteral("frozen_string_literal", UnknownSpan) -> BoolLiteral(true, UnknownSpan),
              SymbolLiteral("mode", UnknownSpan) -> SymbolLiteral("strict", UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses shorthand label-style hash entries") {
      val input = "assert_equal({x: 1, y: 2}, {x:, y:})"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline hash entries with nested hash values") {
      val input =
        """payload = {
          |  testPath: path,
          |  data: {
          |    lineNumber: self.lineno
          |  }
          |}
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "payload",
            HashLiteral(List(
              SymbolLiteral("testPath", UnknownSpan) -> LocalVar("path", UnknownSpan),
              SymbolLiteral("data", UnknownSpan) -> HashLiteral(List(
                SymbolLiteral("lineNumber", UnknownSpan) -> Call(Some(SelfExpr(UnknownSpan)), "lineno", Nil, UnknownSpan)
              ), UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses call arguments with keyword labels") {
      val input = """Prism.parse("1", command_line: "p", line: 4)"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(ConstRef(List("Prism"), UnknownSpan)),
              "parse",
              List(
                StringLiteral("1", UnknownSpan),
                HashLiteral(List(SymbolLiteral("command_line", UnknownSpan) -> StringLiteral("p", UnknownSpan)), UnknownSpan),
                HashLiteral(List(SymbolLiteral("line", UnknownSpan) -> IntLiteral(4, UnknownSpan)), UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses call arguments with reserved keyword labels") {
      val input = """Time.new(2021, 12, 25, in: "+09:00")"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              Some(ConstRef(List("Time"), UnknownSpan)),
              "new",
              List(
                IntLiteral(2021, UnknownSpan),
                IntLiteral(12, UnknownSpan),
                IntLiteral(25, UnknownSpan),
                HashLiteral(List(SymbolLiteral("in", UnknownSpan) -> StringLiteral("+09:00", UnknownSpan)), UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses call arguments with quoted keyword labels") {
      val input = """f("a": -1)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses no-space symbol command calls after keywords") {
      val input = "if true then not_label:foo end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses no-space symbol command calls inside interpolation") {
      val input = """"#{not_label:foo}""""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call arguments with shorthand keyword labels") {
      val input = "f(x:, y:)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "f",
              List(
                HashLiteral(List(SymbolLiteral("x", UnknownSpan) -> LocalVar("x", UnknownSpan)), UnknownSpan),
                HashLiteral(List(SymbolLiteral("y", UnknownSpan) -> LocalVar("y", UnknownSpan)), UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses call arguments with hash-rocket entries") {
      val input = """assert_normal_exit %q{x}, "msg", ["INT"], :timeout => 10 or break"""
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses bracket calls with mixed literal hash-rocket keys") {
      val input =
        """@cls ||= Hash
          |@h = @cls[
          |  1 => 'one', self => 'self', true => 'true', nil => 'nil',
          |  'nil' => nil
          |]
          |""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command-style call with deep constant-path argument") {
      val input = "extend Test::Unit::Assertions"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "extend", List(ConstRef(List("Test", "Unit", "Assertions"), UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses squiggly heredoc argument with trailing args") {
      val input =
        """assert_equal "false", <<~RUBY, "literal strings are mutable", "--disable-frozen-string-literal"
          |  eval("'test'").frozen?
          |RUBY
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(
              None,
              "assert_equal",
              List(
                StringLiteral("false", UnknownSpan),
                StringLiteral("  eval(\"'test'\").frozen?\n", UnknownSpan),
                StringLiteral("literal strings are mutable", UnknownSpan),
                StringLiteral("--disable-frozen-string-literal", UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses dash heredoc arguments") {
      val input =
        """puts(<<-End)
          |hello
          |End
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "puts", List(StringLiteral("hello\n", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses interpolated mixed heredoc markers in call args") {
      val input =
        """tailcall("#{<<-"begin;"}\n#{<<~"end;"}")
          |begin;
          |  def identity(val)
          |    val
          |  end
          |end;""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses interpolated mixed heredoc markers before do-block call") {
      val input =
        """assert_syntax_error("#{<<~"begin;"}\n#{<<~'end;'}", '') do
          |  begin;
          |    1.times {|&b?| }
          |  end;
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses lowercase plain heredoc terminator adjacent to block end") {
      val input =
        """%w(== !=).each do |m|
          |  assert_redefine_method('String', m, <<-end)
          |    assert_equal :b, ("a" #{m} "b").to_sym
          |    b = 'b'
          |    assert_equal :b, ("a" #{m} b).to_sym
          |    assert_equal :b, (b #{m} "b").to_sym
          |  end
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses single-quoted heredoc marker in arguments") {
      val input =
        """assert_normal_exit(<<'End', "msg") if false
          |  puts :ok
          |End
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses single-quoted heredoc after percent-word args on the same line") {
      val input =
        """assert_separately(%W[- #{srcdir}], __FILE__, __LINE__, <<-'eom', timeout: Float::INFINITY)
          |  dir = ARGV.shift
          |eom
          |""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses quoted heredoc marker with non-identifier delimiter") {
      val input =
        """src = <<-'},'
          |  puts :ok
          |},
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses heredoc with empty quoted delimiter") {
      val input =
        """args = [EnvUtil.rubybin, "--disable=gems", "-e", <<"", :err => File::NULL]
          |  Signal.trap("INT") do |signo|
          |    signo
          |  end
          |
          |""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("does not mis-detect heredoc opener text inside string literals") {
      val input =
        """pairs = ["<<-HERE\n", "\nHERE"]
          |result = Prism.parse(<<~RUBY)
          |  %w[\x81\x5c]
          |RUBY
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("does not mis-detect heredoc opener text inside adjacent string literals") {
      val input = """assert_valid_syntax("{label:<<DOC\n""DOC\n""}", bug11849)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("keeps interpolation markers literal in normalized heredoc content") {
      val input =
        """code = <<~'RUBY'.strip
          |  <<A+B
          |  #{C
          |RUBY
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses unary plus and minus operators") {
      val input = "line = -2; value = +line"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("line", UnaryOp("-", IntLiteral(2, UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("value", UnaryOp("+", LocalVar("line", UnknownSpan), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses exponent and bitwise operators") {
      val input = "value = ~1 & (2**8 | 0b1111_0000)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "value",
            BinaryOp(
              UnaryOp("~", IntLiteral(1, UnknownSpan), UnknownSpan),
              "&",
              BinaryOp(
                BinaryOp(IntLiteral(2, UnknownSpan), "**", IntLiteral(8, UnknownSpan), UnknownSpan),
                "|",
                IntLiteral(240, UnknownSpan),
                UnknownSpan
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses base-prefixed and underscored integers") {
      val input = "a = 0xFF; b = 1_000; c = 0d42; d = 0o12"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("a", IntLiteral(255, UnknownSpan), UnknownSpan),
          Assign("b", IntLiteral(1000, UnknownSpan), UnknownSpan),
          Assign("c", IntLiteral(42, UnknownSpan), UnknownSpan),
          Assign("d", IntLiteral(10, UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses numeric literals with r/i suffixes") {
      val input = "a = 1r; b = 2i; c = 3.5r"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign("a", RationalLiteral(IntLiteral(1, UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("b", ImaginaryLiteral(IntLiteral(2, UnknownSpan), UnknownSpan), UnknownSpan),
          Assign("c", RationalLiteral(FloatLiteral(3.5, UnknownSpan), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses shift operators and postfix modifier on expression statements") {
      val input = "path << \"b\" if n; bits = value >> 2"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            LocalVar("n", UnknownSpan),
            List(
              ExprStmt(
                BinaryOp(LocalVar("path", UnknownSpan), "<<", StringLiteral("b", UnknownSpan), UnknownSpan),
                UnknownSpan
              )
            ),
            Nil,
            UnknownSpan
          ),
          Assign(
            "bits",
            BinaryOp(LocalVar("value", UnknownSpan), ">>", IntLiteral(2, UnknownSpan), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses unary not keyword in logical expressions") {
      val input = "if tests and not ARGV.empty?; :ok; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          IfExpr(
            BinaryOp(
              LocalVar("tests", UnknownSpan),
              "and",
              UnaryOp(
                "not",
                Call(Some(ConstRef(List("ARGV"), UnknownSpan)), "empty?", Nil, UnknownSpan),
                UnknownSpan
              ),
              UnknownSpan
            ),
            List(ExprStmt(SymbolLiteral("ok", UnknownSpan), UnknownSpan)),
            Nil,
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses while with assignment expression condition") {
      val input = "while (node = queue.shift); return node if node; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          WhileExpr(
            AssignExpr(
              "node",
              Call(Some(LocalVar("queue", UnknownSpan)), "shift", Nil, UnknownSpan),
              UnknownSpan
            ),
            List(
              IfExpr(
                LocalVar("node", UnknownSpan),
                List(Return(Some(LocalVar("node", UnknownSpan)), UnknownSpan)),
                Nil,
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses while with unparenthesized assignment expression condition") {
      val input = "while node = queue.shift; return node if node; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses while with low-precedence or in the condition") {
      val input = "while prod_threads.any?(&:alive?) or !q.empty?\n  items << q.pop(true) rescue nil\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses while with parenthesized multi assignment condition") {
      val input = "while (left, right = queue.shift); return left; end"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses compound assignment in expression context") {
      val input = "x = (@count += 1)"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            AssignExpr(
              "@count",
              BinaryOp(InstanceVar("@count", UnknownSpan), "+", IntLiteral(1, UnknownSpan), UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses symbol literals for forwarding markers") {
      val input = "scopes = [:*, :**, :&]"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "scopes",
            ArrayLiteral(
              List(
                SymbolLiteral("*", UnknownSpan),
                SymbolLiteral("**", UnknownSpan),
                SymbolLiteral("&", UnknownSpan)
              ),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses script body after -x style shell preamble") {
      val input =
        """"exec" "${RUBY-ruby}" "-x" "$0" "$@" || true # -*- Ruby -*-
          |#!./ruby
          |ok 1
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(Call(None, "ok", List(IntLiteral(1, UnknownSpan)), UnknownSpan), UnknownSpan)
        ), UnknownSpan)
      )
    }

    it("parses array literal entries split across newlines") {
      val input =
        """pairs = [
          |  ["a", 1],
          |  ["b", 2]
          |]
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "pairs",
            ArrayLiteral(List(
              ArrayLiteral(List(StringLiteral("a", UnknownSpan), IntLiteral(1, UnknownSpan)), UnknownSpan),
              ArrayLiteral(List(StringLiteral("b", UnknownSpan), IntLiteral(2, UnknownSpan)), UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses array literals with trailing comma") {
      val input =
        """pairs = [
          |  ["a", 1],
          |]
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "pairs",
            ArrayLiteral(List(
              ArrayLiteral(List(StringLiteral("a", UnknownSpan), IntLiteral(1, UnknownSpan)), UnknownSpan)
            ), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses parenthesized call args split across newlines") {
      val input =
        """assert_equal(
          |  "x",
          |  "y"
          |)
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          ExprStmt(
            Call(None, "assert_equal", List(StringLiteral("x", UnknownSpan), StringLiteral("y", UnknownSpan)), UnknownSpan),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses singleton def with default params and ||= assignment") {
      val input =
        """def Dir.mktmpdir(prefix_suffix=nil, tmpdir=nil)
          |  tmpdir ||= Dir.tmpdir
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Def(
            "Dir.mktmpdir",
            List("prefix_suffix", "tmpdir"),
            List(
              Assign(
                "tmpdir",
                BinaryOp(
                  LocalVar("tmpdir", UnknownSpan),
                  "||",
                  Call(Some(ConstRef(List("Dir"), UnknownSpan)), "tmpdir", Nil, UnknownSpan),
                  UnknownSpan
                ),
                UnknownSpan
              )
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses constant assignment with call-chain and do block") {
      val input = "BT = Class.new(bt) do; end.new"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "BT",
            Call(
              Some(
                CallWithBlock(
                  Call(Some(ConstRef(List("Class"), UnknownSpan)), "new", List(LocalVar("bt", UnknownSpan)), UnknownSpan),
                  Block(Nil, Nil, UnknownSpan),
                  UnknownSpan
                )
              ),
              "new",
              Nil,
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses constant assignment with multiline do-end block") {
      val input =
        """BT = Class.new(bt) do
          |  def indent=(n)
          |    super
          |  end
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses and-chain condition with constant receiver comparisons") {
      val input =
        """if e and BT.columns > 0 and BT.tty and !BT.verbose
          |  ""
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses comparison on constant receiver call") {
      val input = "BT.columns > 0"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses logical and with constant receiver comparison") {
      val input = "e and BT.columns > 0"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
    }

    it("parses triple-equals with constant paths") {
      val input = "x = Prism::StringNode === y"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          Assign(
            "x",
            BinaryOp(
              ConstRef(List("Prism", "StringNode"), UnknownSpan),
              "===",
              LocalVar("y", UnknownSpan),
              UnknownSpan
            ),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }

    it("parses case when else expression") {
      val input =
        """case prefix_suffix
          |when nil
          |  prefix = "d"
          |when String
          |  prefix = prefix_suffix
          |else
          |  prefix = "x"
          |end
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      val ast = parsed.toOption.get
      assert(
        ast == Program(List(
          CaseExpr(
            Some(LocalVar("prefix_suffix", UnknownSpan)),
            List(
              WhenClause(
                List(NilLiteral(UnknownSpan)),
                List(Assign("prefix", StringLiteral("d", UnknownSpan), UnknownSpan)),
                UnknownSpan
              ),
              WhenClause(
                List(ConstRef(List("String"), UnknownSpan)),
                List(Assign("prefix", LocalVar("prefix_suffix", UnknownSpan), UnknownSpan)),
                UnknownSpan
              )
            ),
            List(Assign("prefix", StringLiteral("x", UnknownSpan), UnknownSpan)),
            UnknownSpan
          )
        ), UnknownSpan)
      )
    }
    it("parses while...do...end") {
      val input = "while x > 0 do\n  x -= 1\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses until...do...end") {
      val input = "until x == 0 do\n  x -= 1\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses parenthesized semicolon expressions before postfix until") {
      val input = "(Thread.pass; sleep 0.01) until q.size == 0"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses parenthesized multiline expressions before postfix while") {
      val input =
        """(
          |  total += 1
          |  total += 2
          |) while false""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses for...in...do...end") {
      val input = "for i in 1..10 do\n  puts i\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses if...then...end") {
      val input = "if x > 0 then\n  puts x\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses unless...then...end") {
      val input = "unless x == 0 then\n  puts x\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses when...then... in case") {
      val input = "case x\nwhen 1 then puts \"one\"\nwhen 2 then puts \"two\"\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("strips __END__ section") {
      val input = "x = 1\n__END__\nsome data\nthat should be ignored"
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      assert(parsed.isRight)
      assert(parsed.toOption.get.statements.length == 1)
    }

    it("does not strip __END__ inside heredoc bodies") {
      val input =
        """eval(<<~END, nil, __FILE__, __LINE__)
          |  1.times do
          |  end
          |__END__
          |  ignored only by Ruby runtime data section, not here
          |END
          |
          |x = 1
          |""".stripMargin
      val parsed = RubySubsetParser.parse(input)
      assert(parsed.isRight)
      assert(parsed.toOption.get.statements.length == 2)
    }

    it("parses begin...end as expression on RHS of assignment") {
      val input = "x = begin\n  1 + 2\nrescue\n  0\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses assignment with newline before begin...end expression") {
      val input = "x =\n  begin\n    1\n  ensure\n    2\n  end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses if-end expressions chained with method calls") {
      val input =
        """if ready
          |  [[1, 2]]
          |else
          |  [[3, 4]]
          |end.each do |pair|
          |  pair
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses if...end as expression on RHS of assignment") {
      val input = "x = if cond\n  1\nelse\n  2\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case...end as expression on RHS of assignment") {
      val input = "x = case y\nwhen 1 then \"one\"\nelse \"other\"\nend"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses quote-delimited percent word and symbol arrays") {
      val input = "a = %w\"x y\"; b = %w'a b'; c = %i\"foo bar\""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses chained assignment on rhs") {
      val input = "a = b = c = 1"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses chained index assignment on rhs") {
      val input = "expected[3] = actual[3] = nil"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline multi-assignment rhs split after comma") {
      val input =
        """a, b = o.method(:foo).source_location[0],
          |       o.method(:bar).source_location[0]""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline multi-assignment targets split after comma") {
      val input =
        """tz, u_mon, u_day,
          |  l_mon, l_day = captures""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses grouped multi-assignment targets with trailing comma") {
      val input = "(message, category), = captures"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multi-assignment targets with chained receiver and index targets") {
      val input = "x1.y1.z, x2[1, 2, 3], self[4] = r1, 6, r2"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses starred and indexed multi assignment") {
      val input = "*a = nil; ENV[n0], e0 = e0, ENV[n0]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses safe-navigation assignment") {
      val input = "o&.x = 6"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses def with backtick method name") {
      val input =
        """def `(command)`
          |  command
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses backtick operator method before later backtick string literals") {
      val input =
        """class T
          |  def `(command)
          |    command
          |  end
          |  def test_xstr; assert_context(Context::String.new("`", "`")); end
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses question-mark character literal") {
      val input = "x = ?a"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses question-mark character literal range in call args") {
      val input = "l.zip(?a..?c)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses unicode escaped question-mark character literal") {
      val input = """assert_equal(?\u0041, ?A)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses hex escaped question-mark character literal") {
      val input = """assert_equal(?\x79, ?y)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses octal escaped question-mark character literal") {
      val input = """assert_equal(?\000, ?\u{0})"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses control and meta question-mark character literals") {
      val input = """assert_equal("\1", ?\C-a); assert_equal("\341", ?\M-a); assert_equal("\201", ?\M-\C-a)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses double-quoted strings containing backticks in call args") {
      val input = """assert_context(Context::String.new("`", "`"))"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses anonymous keyword forwarding in array literals") {
      val input = "def self.a(b: 1, **) [b, **] end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with bare keyword-hash elements") {
      val input = "steps = [10, by: -1, to: nil]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command calls with array arg containing bare keyword-hash elements") {
      val input = "assert_step [10, by: -1], inf: true"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses nil block and keyword rest parameters") {
      val input = "def mnb(&nil) end; def mnk(**nil) end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses block pass with expression") {
      val input = "(1..3).each(&lambda{})"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses single-quoted symbol literal") {
      val input = "o.instance_variable_get(:'@')"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses hash literal with double-splat entry") {
      val input = "defined?({**a})"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in clauses") {
      val input =
        """case x
          |in 0
          |  true
          |else
          |  false
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in with rightward assignment pattern") {
      val input =
        """case x
          |in 0 => a
          |  a
          |else
          |  nil
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in guard clauses") {
      val input =
        """case x
          |in a if a == 0
          |  true
          |else
          |  false
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in guard clauses on array patterns") {
      val input =
        """case value
          |in [x] if x > 0
          |in [0]
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in or-pattern clauses on array patterns") {
      val input =
        """case value
          |in []
          |in [1] | [0]
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in lambda patterns") {
      val input =
        """case x
          |in ->(i) { i == 0 }
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in pin operator patterns") {
      val input =
        """case [0, 0]
          |in a, ^a
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in hash patterns with pin-expression values") {
      val input =
        """case {released_at: Time.new(2018, 12, 25)}
          |in {released_at: ^(Time.new(2010)..Time.new(2020))}
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in hash patterns with punctuated label keys") {
      val input =
        """case {a?: true}
          |in a?: true
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in hash patterns with newline after label colon") {
      val input =
        """case {a: 0}
          |in {a:
          |      2}
          |  false
          |in {a:
          |}
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in patterns with trailing comma before semicolon") {
      val input =
        """case x
          |in 0,;
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in bare splat patterns") {
      val input =
        """case [0, 1, 2]
          |in *, 1, *
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in bracketed splat array patterns") {
      val input =
        """case [0, 1, 2]
          |in [*, 1, *]
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in bracketed rightward assignment array patterns") {
      val input =
        """case [0, 1, 2]
          |in [*, 1 => a, *]
          |  a
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in constant deconstruct patterns") {
      val input =
        """case value
          |in Array(*, 1, *)
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in bracket deconstruct patterns") {
      val input =
        """case value
          |in C[0]
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses case-in bracket deconstruct patterns with shorthand hash labels") {
      val input =
        """case value
          |in Array[a:]
          |  a
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses top-level hash patterns without braces") {
      val input =
        """case value
          |in "a":, **rest
          |  rest
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses top-level bare double-splat hash patterns") {
      val input =
        """case value
          |in **nil
          |  true
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses standalone rightward pattern matching with array patterns") {
      val input = "[0, 1, 2] => [*, 1 => a, *]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses standalone rightward pattern matching with top-level hash patterns") {
      val input = "{a: 1} => a:"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses standalone rightward pattern matching with multiple bindings") {
      val input = "[1, 2] => a, b"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses standalone rightward pattern matching with bracket deconstruct shorthand hash patterns") {
      val input = "{} => Array[a:]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses one-line in-pattern expressions") {
      val input = "assert_equal true, (1 in 1); assert_equal false, (1 in 2)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses unary operator method definition") {
      val input = "class C; def -@; :ok; end; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses block pass with call-chain expression") {
      val input = "Thread.new(&method(:sleep).to_proc)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses for statement with multiple bindings") {
      val input = "for x,y in cache.sort_by {|z| z[0] % 3 }; puts x; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses singleton class expression chained with class_eval") {
      val input = "class << o; self; end.class_eval do; x = 1; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses backtick symbol literal") {
      val input = "marshal_equal(:`)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses percent symbol literals") {
      val input = "values = [%s(a), %s(), %s|b|]"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses percent regex literals with percent delimiter") {
      val input = """assert_raise(ArgumentError, %r%unknown pack directive '\*' in '\*U'$%)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses equality operator without spaces") {
      val input = "expected==temp"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses inequality operator without spaces") {
      val input = "line!=\"# x.txt\""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses singleton defs whose method name is a keyword") {
      val input = "obj = Object.new; def obj.def; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver call with empty args inside brace block") {
      val input = "assert_raise(ArgumentError) { o.foo() }"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses explicit receiver operator calls for bracket access") {
      val input = """h = @cls.[]("a" => 100, "b" => 200)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses bracket calls with array literal hash-rocket keys") {
      val input = """assert_equal([[1], [2]], @cls[[1] => [2]].flatten)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses bracket calls with unary numeric hash-rocket keys") {
      val input = """x = @cls[1 => :a, -1 => :b]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call arguments with receiver-call hash-rocket keys") {
      val input = """system(env, RUBY, '-e', 'exit', 'rlimit_bogus'.to_sym => 123)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses io-popen array calls with trailing hash-rocket options") {
      val input = """io = IO.popen([RUBY, "-e", "print Process.getpgrp", :pgroup=>true])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with trailing symbol hash-rocket options") {
      val input = """args = [RUBY, "-e", "print Process.getpgrp", :pgroup=>true]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with single hash-rocket element") {
      val input = """args = [:a=>true]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with trailing hash-rocket element") {
      val input = """args = [1, :a=>true]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses function calls with single hash-rocket array arg element") {
      val input = """f([:a=>true])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses function calls with trailing hash-rocket array arg element") {
      val input = """f([1, :a=>true])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses io-popen array calls without trailing options") {
      val input = """io = IO.popen([RUBY, "-e", "print Process.getpgrp"])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses io-popen array calls with string mode arg and block") {
      val input =
        """IO.popen([RUBY, '-egets'], 'w') do |f|
          |  Process.wait spawn(*TRUECOMMAND, :pgroup=>f.pid)
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses io-popen array calls with receiver-call option values") {
      val input = """io2 = IO.popen([RUBY, "-e", "print Process.getpgrp", :pgroup=>io1.pid])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver call arguments with local assignments") {
      val input = """ec.primitive_convert(src="a", dst="b", nil, 1, :partial_input=>true)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline single-quoted string argument before do-block") {
      val input =
        """assert_in_out_err(["-Eutf-8:cp932"], '# coding: cp932
          |$stderr = $stdout; raise "\x82\xa0"') do |outs, errs, status|
          |  outs
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses array literals with regex elements") {
      val input = """args = [/    \^/, /\n/]"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses function calls with regex array arguments") {
      val input = """assert_pattern_list([/    \^/, /\n/], e.message)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses regex literals with interpolation segments") {
      val input = """msg = /Invalid #{code[/\A\w+/]}/"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses special global variables with quoted names") {
      val input = """loaded = $".dup; $".clear; loadpath = $:.dup; $:.clear"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses low-precedence and with assignment on the rhs") {
      val input = """e = ENV[k] and h[k] = e"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses block bodies with low-precedence and assignment chains") {
      val input = """MANDATORY_ENVS.each {|k| e = ENV[k] and h[k] = e }"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses defined? with leading semicolon inside parentheses") {
      val input = """assert_equal("expression", defined? (;x))"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses defined? with trailing semicolon inside parentheses") {
      val input = """assert_equal("expression", defined? (x;))"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses no-arg receiver calls with brace blocks") {
      val input = """@h.each_value { |v| res << v }"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver calls with brace blocks and multi-params") {
      val input = """@h.each { |k, v| expected << v }"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses index-receiver calls chained to brace blocks") {
      val input = """@cls[].each_value { |v| res << v }"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses index-receiver calls chained to do blocks") {
      val input =
        """h = @h
          |h.each_pair do |k, v|
          |  assert_equal(v, h.delete(k))
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses hash each-value methods with consecutive brace blocks") {
      val input =
        """def test_each_value
          |  res = []
          |  @cls[].each_value { |v| res << v }
          |  assert_equal(0, [].length)
          |
          |  @h.each_value { |v| res << v }
          |  assert_equal(0, [].length)
          |
          |  expected = []
          |  @h.each { |k, v| expected << v }
          |
          |  assert_equal([], expected - res)
          |  assert_equal([], res - expected)
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses nested index assignment targets") {
      val input = """a_kw[-1][:y] = 2"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses chained calls on empty array literals") {
      val input = """assert_equal(0, [].length)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command-style spawn calls with splat bracket-call args") {
      val input = """Process.wait Process.spawn(*PWD, :out => n)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command-style spawn calls with splat bracket-call args and array redirect targets") {
      val input = """Process.wait Process.spawn(*ECHO["a"], STDOUT=>["out", File::WRONLY|File::CREAT|File::TRUNC, 0644])"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call arguments with array-literal hash-rocket keys") {
      val input = """Process.wait Process.spawn(*ECHO["f"], [Process]=>1)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call arguments with multi-element array hash-rocket keys") {
      val input = """Process.wait Process.spawn(*ECHO["f"], [1, STDOUT]=>2)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses anonymous splat call arguments") {
      val input = """def self.s(*) ->(*a){a}.call(*) end"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses method calls with negative positional args") {
      val input = "assert_equal(5**2 % -8, 5.pow(2,-8))"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses unary minus receiver method calls") {
      val input = "assert_equal((-3)**3 % 8, -3.pow(3,8))"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses call-with-brace-block used as a positional argument") {
      val input = """assert_equal(123, delay { 123 }.call, message(bug6901) { disasm(:delay) })"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver call-with-brace-block used as a positional argument") {
      val input = """assert_nil("".unpack("i") {|x| result = x}, bug4059)"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver chains with closed range slices inside call args") {
      val input = "assert_pattern_list([], e.message.lines[2..-1].join)"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses multiline bigint receiver dot-call") {
      val input =
        """assert_equal(4481650795473624846969600733813414725093,
          |             2120078484650058507891187874713297895455.
          |                pow(5478118174010360425845660566650432540723,
          |                    5263488859030795548286226023720904036518))""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses receiver call with block and bare hash entry inside array arg") {
      val input =
        """IO.popen([env, RUBY, "-e", "puts Process.getrlimit(:CORE)", :rlimit_core=>n]) {|io|
          |  assert_equal("#{n}\n#{n}\n", io.read)
          |}""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses exact optimization call-with-block argument shape") {
      val input = """assert_equal(123, delay { 123 }.call, message(bug6901) {disasm(:delay)})"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses exact parse-test receiver call with block inside assert_equal") {
      val input = """assert_equal(-303, o.foo(1,2,3) {|x| -x } )"""
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses optimization method with mixed heredoc setup and block call args") {
      val input =
        """def test_tailcall_with_block
          |  bug6901 = '[ruby-dev:46065]'
          |
          |  tailcall("#{<<-"begin;"}\n#{<<~"end;"}")
          |  begin;
          |    def identity(val)
          |      val
          |    end
          |
          |    def delay
          |      -> {
          |        identity(yield)
          |      }
          |    end
          |  end;
          |  assert_equal(123, delay { 123 }.call, message(bug6901) {disasm(:delay)})
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses parse-test method with heredoc interpolation and duplicate-arg block") {
      val input =
        """def test_duplicate_argument
          |  assert_syntax_error("#{<<~"begin;"}\n#{<<~'end;'}", '') do
          |    begin;
          |      1.times {|&b?| }
          |    end;
          |  end
          |end""".stripMargin
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses not-match operator without spaces") {
      val input = "str!~/x/"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses command call with postfix unless and no-space equality") {
      val input = "assert_equal expected, temp.upcase!(*flags) unless expected==temp"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses chained postfix rescue and if modifiers") {
      val input = "File.unlink(*tmpfiles) rescue nil if tmpfiles"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses no-space lambda command argument") {
      val input = "cmp->(x) do 0; end"
      assert(RubySubsetParser.parse(input).isRight)
    }

    it("parses literal receiver command call with block and safe navigation chain") {
      val input = "assert_nil((\"a\".sub! \"b\" do end&.foo 1))"
      assert(RubySubsetParser.parse(input).isRight)
    }

  }
}
