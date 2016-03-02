package com
package github
package kmizu
package hopeg

object HOPEGRunner {
  def main(args: Array[String]): Unit = {
    tryGrammar(
      "palindrome",
      HOPEGParser.parse("""
     |S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r;
     """.stripMargin), "a", "b", "aa", "bb", "ab", "abba", "abbb"
    )

    tryGrammar(
      "a / b",
      HOPEGParser.parse("""
     |S = APPLY2(ALTER, "a", "b") !.; ALTER(x, y) = x / y; APPLY2(F, x, y) = F(F(x)) ;
     """.stripMargin), "a", "b", "c"
    )

    tryGrammar(
      "u rule",
      HOPEGParser.parse("""
     |S = u((x -> x), "x"); u(f, x) = f(!x .) (u(f, x))?;
      """.stripMargin), "yy", "x"
    )
    tryGrammar(
       "arithmetic",
      HOPEGParser.parse(
       """
      |S = Plus0("");
      |// '1'を並べた数で自然数を表すとする。
      |// a+b=c$をチェックする。
      |Plus0(Left) = Plus1(Left, "") / &(Left "1") Plus0(Left "1");
      |
      |Plus1(Left, Right)
      |  = &(Left "+" Right "=") Plus2(Left, Right)
      |  / &(Left "+" Right "1") Plus1(Left, Right "1");
      |
      |Plus2(Left, Right)
      |  = Left "+" Right "=" Left Right "$";
      |
      |// a*b=c$をチェックする。
      |Mul0(Left)
      |  = &(Left "*") Mul1(Left, "", "")
      |  / &(Left "1") Mul0(Left "1");
      |
      |Mul1(Left, Right, Prod)
      |  = &(Left "+" Right "=") Mul2(Left, Right, Prod)
      |  / &(Left "+" Right "1") Mul1(Left, Right "1", Prod Left);
      |
      |Mul2(Left, Right, Prod)
      |  = Left "+" Right "=" Prod "$";
      |
      |
      |// a-b=c$をチェックする。
      |Minus0(Right)
      |  = &(.* "-" Right "=") Minus1(Right, "")
      |  / &(.* "-" Right "1") Minus0(Right "1");
      |
      |Minus1(Right, Diff)
      |  = &(.* "-" Right "=" Diff "$") Minus2(Right, Diff)
      |  / &(.* "-" Right "=" Diff "1") Minus1(Right, Diff "1");
      |
      |Minus2(Right, Diff)
      |  = Right Diff "-" Right "=" Diff "$";
      |
      |// a/b=cも同様にチェックできるはず。
      """.stripMargin), "1+1=11$", "111+11=11111$", "111+1=11111$")
  }

  def tryGrammar(name: String, grammar: Ast.Grammar, inputs: String*): Unit = {
    val evaluator = HOPEGEvaluator(grammar)
    println("grammar: " + name)
    println()
    for(input <- inputs) {
      println("input:" + input)
      println(evaluator.evaluate(input, 'S).map{in => s"matched to ${input}"}.getOrElse{s"not matched to ${input}"})
      println()
    }
  }
}
