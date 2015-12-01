package com
package github
package kmizu
package hopeg

object HOPEGRunner {
  def main(args: Array[String]): Unit = {
    val grammar = HOPEGParser.parse(
      """
        |S="AB" !. | "CD";
      """.stripMargin)
    println(grammar)
    val evaluator = new HOPEGEvaluator(grammar)
    println(evaluator.evaluate("AB", 'S))
  }
}
