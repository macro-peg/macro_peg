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
      "S = F((x -> x x x)) .!; F(f) = f(X);",
      HOPEGParser.parse("""
     |S = F((x -> x x x)); F(f) = f("X");
      """.stripMargin), "XXX", "YYY", "ZZZ"
    )
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
