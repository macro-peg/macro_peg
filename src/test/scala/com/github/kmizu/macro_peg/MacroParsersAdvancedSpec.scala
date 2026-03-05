package com.github.kmizu.macro_peg

import com.github.kmizu.macro_peg.combinator.MacroParsers._
import org.scalatest.diagrams.Diagrams
import org.scalatest.funspec.AnyFunSpec

class MacroParsersAdvancedSpec extends AnyFunSpec with Diagrams {
  describe("MacroParsers advanced diagnostics") {
    it("supports cut to prevent fallback branch") {
      val withCut = ("a".s ~ "b".s.cut) / ("a".s ~ "c".s)
      val withoutCut = ("a".s ~ "b".s) / ("a".s ~ "c".s)

      assert(withoutCut("ac").isInstanceOf[ParseSuccess[_]])
      assert(withCut("ac").isInstanceOf[ParseFailure])
    }

    it("supports label") {
      val parser = "a".s.label("letter-a")
      val result = parser("b").asInstanceOf[ParseFailure]
      assert(result.expected == List("letter-a"))
    }

    it("supports recover") {
      val parser = ("a".s ~ "b".s).recover("a".s ~ "c".s)
      assert(parser("ac").isInstanceOf[ParseSuccess[_]])
    }

    it("supports trace and formatted failure") {
      val parser = ("a".s ~ "b".s).trace("AB")
      val failure = parser("ax").asInstanceOf[ParseFailure]
      assert(failure.ruleStack.contains("AB"))

      val rendered = formatFailure("ax", failure)
      assert(rendered.contains("1:2"))
    }

    it("supports <~ and ~> convenience operators") {
      val parser = "(".s ~> "abc".s <~ ")".s
      assert(parser("(abc)") == ParseSuccess("abc", ""))
      assert(parser("(ab)").isInstanceOf[ParseFailure])
    }

    it("supports success and sepBy helpers") {
      val parser = sepBy0(range('0' to '9').+.map(_.mkString), ",".s)
      assert(parser("1,22,333") == ParseSuccess(List("1", "22", "333"), ""))
      assert(parser("") == ParseSuccess(Nil, ""))
      (success(42) ~ "x".s)("x") match {
        case ParseSuccess(pair, "") =>
          assert(pair._1 == 42)
          assert(pair._2 == "x")
        case other =>
          fail(s"unexpected parse result: $other")
      }
    }

    it("detects direct non-consuming recursion with guard") {
      object G {
        lazy val A: P[String] = guard("A")(refer(A))
      }
      val failure = G.A("").asInstanceOf[ParseFailure]
      assert(failure.message.contains("infinite recursion detected"))
    }

    it("detects indirect non-consuming recursion with guard") {
      object G {
        lazy val A: P[String] = guard("A")(refer(B))
        lazy val B: P[String] = guard("B")(refer(A))
      }
      val failure = G.A("").asInstanceOf[ParseFailure]
      assert(failure.message.contains("infinite recursion detected"))
    }

    it("detects direct non-consuming recursion via refer without explicit guard") {
      object G {
        lazy val A: P[String] = refer(A)
      }
      val failure = G.A("").asInstanceOf[ParseFailure]
      assert(failure.message.contains("infinite recursion detected"))
    }

    it("detects indirect non-consuming recursion via refer without explicit guard") {
      object G {
        lazy val A: P[String] = refer(B)
        lazy val B: P[String] = refer(A)
      }
      val failure = G.A("").asInstanceOf[ParseFailure]
      assert(failure.message.contains("infinite recursion detected"))
    }

    it("memoizes repeated parser invocation at the same input position") {
      var calls = 0
      val base =
        ("".s.map { _ =>
          calls += 1
          ()
        } ~ !any).map(_ => ())
      val memoized = base.memo
      val parser = memoized / memoized
      parseAll(parser, "x")
      assert(calls == 1)
    }
  }
}
