package com
package github
package kmizu
package hopeg

object HOPEGRunner {
  def main(args: Array[String]): Unit = {
    val grammar = HOPEGParser.parse(
      """
        |S = P("a", "b") !.; P(a, b) = a P(a, b) b | "";
      """.stripMargin)
    val evaluator = new HOPEGEvaluator(grammar)
    val input = "aaaabbbb"
    for(i <- 1 to 10) {
      val input = ("a" * i) + ("b" * i)
      println(evaluator.evaluate(input, 'S).map{in => s"matched to ${input}"}.getOrElse{s"not matched to ${input}"})
    }
  }
}
