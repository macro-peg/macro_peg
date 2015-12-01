package com
package github
package kmizu
package hopeg

object HOPEGRunner {
  def main(args: Array[String]): Unit = {
    val grammar = HOPEGParser.parse(
      """
        |S="ABCDE";
      """.stripMargin)
    println(grammar)
  }
}
