package com.github.kmizu.macro_peg.ruby

object DiagnosticRunner {
  def main(args: Array[String]): Unit = {
    // Test specific snippets via parse()
    val tests = Seq(
      "x = foo 1\n",
      "x = foo\n",
      "foo 1\n",
      "$:.unshift x\n",
      "x.method arg\n",
      "a = b c\n",
      "puts 1\n",
      "x = y z 1\n"
    )

    println("=== Full parse tests ===")
    for (code <- tests) {
      val result = GeneratedRubyParser.parseAll(code)
      val status = result match {
        case Right(v) => s"OK: ${v.toString.take(60)}"
        case Left(e) => s"FAIL: $e"
      }
      println(s"  '${code.trim}': $status")
    }
  }
}
