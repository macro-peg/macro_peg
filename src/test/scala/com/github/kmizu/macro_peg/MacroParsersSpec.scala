package com.github.kmizu.macro_peg

import org.scalatest.{DiagrammedAssertions, FunSpec}
import com.github.kmizu.macro_peg.combinator.MacroParsers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.{Arbitrary, Gen}

class MacroParsersSpec extends FunSpec with DiagrammedAssertions with GeneratorDrivenPropertyChecks {
  describe("MacroParsers Example") {
    it("palindrome") {
      object Palindrome {
        lazy val S: MacroParser[Any] = P("".s) ~ !any
        def P(r: MacroParser[Any]): MacroParser[Any] = "a".s ~ refer(P("a".s ~ r)) / "b".s ~ refer(P("b".s ~ r)) / r
      }
      val S = Palindrome.S
      assert(S("a").drop == ParseFailure("", "a"))
      assert(S("b").drop == ParseFailure("", "b"))
      assert(S("ab").drop == ParseFailure("", "ab"))
      assert(S("abba").drop == ParseSuccess(None, ""))
      assert(S("abbb").drop == ParseFailure("", "abbb"))
      val gen = for {
        s1 <- Gen.listOf(Gen.oneOf('a', 'b'))
        s = s1.mkString
        s2 <- s + s.reverse
      } yield s2

      forAll(gen) {
        case p => assert(S(p).drop == ParseSuccess(None, ""))
      }
    }
    it("sequence without repetition") {
      object SequenceWithoutRepetition {
        lazy val S: P[Any] = Modifiers(!"".s) ~ !any
        def Modifiers(AlreadyLooked: P[Any]): P[Any] = (!AlreadyLooked) ~ (
          "a".s ~ refer(Modifiers(AlreadyLooked / "a".s))
        / "b".s ~ refer(Modifiers(AlreadyLooked / "b".s))
        / "c".s ~ refer(Modifiers(AlreadyLooked / "c".s))
        / "".s
        )
      }
      val S = SequenceWithoutRepetition.S
      assert(S("a").drop == ParseSuccess(None, ""))
      assert(S("b").drop == ParseSuccess(None, ""))
      assert(S("c").drop == ParseSuccess(None, ""))
      assert(S("aa").drop == ParseFailure("", "aa"))
      assert(S("bb").drop == ParseFailure("", "bb"))
      assert(S("cc").drop == ParseFailure("", "cc"))
      assert(S("ab").drop == ParseSuccess(None, ""))
      assert(S("ac").drop == ParseSuccess(None, ""))
      assert(S("ba").drop == ParseSuccess(None, ""))
      assert(S("bc").drop == ParseSuccess(None, ""))
      assert(S("ca").drop == ParseSuccess(None, ""))
      assert(S("cb").drop == ParseSuccess(None, ""))

      val src = Seq("a", "b", "c")
      val gen = for {
        x <- Gen.oneOf[String](src)
        src2 = src.filterNot(_ == x)
        y <- Gen.oneOf[String](src2)
        src3 = src2.filterNot(_ == y)
        z <- Gen.oneOf(src3)
        result = List(x, y, z).mkString
      } yield result
      forAll(gen) {
        case g => assert(S(g).drop == ParseSuccess(None, ""))
      }
    }
    it("a^n b^n c^n") {
      object AnBnCn {
        lazy val S: P[Any] = (refer(A) ~ !"b".s).and ~ string("a").+ ~ refer(B) ~ !any
        lazy val A: P[Any] = "a".s ~ refer(A).? ~ "b".s
        lazy val B: P[Any] = "b".s ~ refer(B).? ~ "c".s
      }
      val S = AnBnCn.S
      assert(S("").drop == ParseFailure("" , ""))
      assert(S("abc").drop == ParseSuccess(None , ""))
      assert(S("abb").drop == ParseFailure("", "abb"))
      assert(S("bba").drop == ParseFailure("", "bba"))
      assert(S("aabbcc").drop == ParseSuccess(None , ""))

      val gen = for {
        n <- Gen.choose(1, 10)
        a <- Gen.listOfN(n, Gen.const("a"))
        b <- Gen.listOfN(n, Gen.const("b"))
        c <- Gen.listOfN(n, Gen.const("c"))
      } yield (a ++ b ++ c).mkString
      forAll(gen) {
        case g => assert(S(g).drop == ParseSuccess(None, ""))
      }
    }
    it("min-xml") {
      object MinXML {
        lazy val I: P[Any] = range('a'to'z','A'to'Z', Seq('_')) ~ range('a'to'z','A'to'Z',Seq('_'),'0'to'9').*
        lazy val S: P[Any] = "<".s ~ I.evalCC {tag =>
          ">".s ~ S.* ~ "</".s ~ tag ~ ">".s
        }
      }
      val S = MinXML.S
      assert(S("<foo></foo>").drop == ParseSuccess(None, ""))
      assert(S("<foo></bar>").drop == ParseFailure("", "bar>"))
      assert(S("<foo><bar></bar></foo>").drop == ParseSuccess(None, ""))
      assert(S("<foo><bar></foo></bar>").drop == ParseFailure("",  "<bar></foo></bar>"))
    }
    it("undefined variable is parse error") {
      object L {
        lazy val Spacing: P[Any] = ("\r".s | "\t".s | " ".s | "\r".s | "\n".s).*
        def OPEN: P[Any] = "(".s ~ Spacing
        def CLOSE: P[Any] = ")".s ~ Spacing
        def EQ: P[Any] = "=".s ~ Spacing
        def SEMI_COLON: P[Any] = ";".s ~ Spacing
        def VAL: P[Any] = "val".s ~ Spacing
        lazy val S: P[Any] = Statements(!"".s) ~ !any
        def Statements(table: P[Any]): P[Any] = VAL ~ Identifier.evalCC{i => EQ ~ Expression(table) ~ SEMI_COLON ~ refer(Statements(table | i)).? } / Expression(table) ~ SEMI_COLON ~ refer(Statements(table)).?
        def Expression(table: P[Any]): P[Any] = refer(Primary(table)) ~ (range(Seq('+','-','*','/')) ~ Spacing ~ refer(Primary(table))).*
        def Primary(table: P[Any]): P[Any] =  table.and ~ Identifier | IntegerLiteral | (OPEN ~ refer(Expression(table)) ~ CLOSE)
        lazy val Identifier: P[Any] = range('a'to'z','A'to'Z',Seq('_')) ~ range('a'to'z','A'to'Z','0'to'9',Seq('_')).* ~ Spacing
        lazy val IntegerLiteral: P[Any] = range('0'to'9').+ ~ Spacing
      }
      val S = L.S
      assert(S("(s * 1);").drop == ParseFailure("", "s * 1);"))
      assert(S("val s = 1; (s * 1);").drop == ParseSuccess(None, ""))
    }
    it("calculator") {
      object L {
        lazy val S: P[Int] = refer(E)
        lazy val E: P[Int] = refer(A)
        lazy val A: P[Int] = (refer(M) ~ ("+".s ~ refer(M) | "-".s ~ refer(M)).*).map{case x ~~ xs=>
          xs.foldLeft(x) {
            case (ys, op ~~ r) => if(op == "+") ys + r else ys - r
          }
        }
        lazy val M: P[Int] = (refer(P) ~ ("*".s ~ refer(P) | "/".s ~ refer(P)).*).map{case x ~~ xs=>
            xs.foldLeft(x) {
              case (ys, op ~~ r) => if(op == "*") ys * r else ys / r
            }
        }
        lazy val P: P[Int] =
          ("(".s ~ refer(E) ~ ")".s).map{ case _ ~~ n ~~ _ => n} /
          refer(N)
        lazy val N: P[Int] = range('0'to'9').+.map{_.mkString.toInt}
      }
      val S = L.S
      assert(S("1+1") == ParseSuccess(2, ""))
      assert(S("1+2*3") == ParseSuccess(7, ""))
      assert(S("(1+2)*3") == ParseSuccess(9, ""))
      assert(S("(2*4)/2") == ParseSuccess(4, ""))
    }
  }
}
