package com.github.kmizu.macro_peg.ruby

import com.github.kmizu.macro_peg.ruby.RubyAst.Program

object RubyFullParser {
  def parse(input: String): Either[String, Program] =
    RubySubsetParser.parse(input)
}
