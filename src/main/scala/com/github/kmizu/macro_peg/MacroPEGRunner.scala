package com
package github
package kmizu
package macro_peg

object MacroPEGRunner {
  def main(args: Array[String]): Unit = {
    tryGrammar(
      "palindrome",
      MacroPEGParser.parse("""
     |S = P("") !.; P(r) = "a" P("a" r) / "b" P("b" r) / r;
     """.stripMargin), "a", "b", "aa", "bb", "ab", "abba", "abbb"
    )

    tryGrammar(
      "a / b",
      MacroPEGParser.parse("""
     |S = APPLY2(ALTER, "a", "b") !.; ALTER(x, y) = x / y; APPLY2(F, x, y) = F(F(x)) ;
     """.stripMargin), "a", "b", "c"
    )

    tryGrammar(
      "u rule",
      MacroPEGParser.parse("""
     |S = u((x -> x), "x"); u(f, x) = f(!x .) (u(f, x))?;
      """.stripMargin), "yy", "x"
    )
    tryGrammar(
       "arithmetic",
      MacroPEGParser.parse(
       """
      |S = (Plus0("") / Mul0("1")) !.;
      |// the number of occurence of '1 represents a natural number.
      |// a+b=c
      |Plus0(Left) = Plus1(Left, "") / &(Left "1") Plus0(Left "1");
      |
      |Plus1(Left, Right)
      |  = &(Left "+" Right "=") Plus2(Left, Right)
      |  / &(Left "+" Right "1") Plus1(Left, Right "1");
      |
      |Plus2(Left, Right)
      |  = Left "+" Right "=" Left Right;
      |
      |// check a*b=c
      |Mul0(Left)
      |  = &(Left "*") Mul1(Left, "", "")
      |  / &(Left "1") Mul0(Left "1");
      |
      |Mul1(Left, Right, Prod)
      |  = &(Left "*" Right "=") Mul2(Left, Right, Prod)
      |  / &(Left "*" Right "1") Mul1(Left, Right "1", Prod Left);
      |
      |Mul2(Left, Right, Prod)
      |  = Left "*" Right "=" Prod;
      |
      """.stripMargin), "1+1=11", "111+11=11111", "111+1=11111",  "111*11=111111", "11*111=111111", "1*111=1")
      tryGrammar(
        "modifiers",
        MacroPEGParser.parse(
        """
          |S = Modifiers(!"") !.;
          |Modifiers(AlreadyLooked) = (!AlreadyLooked) (
          |    Token("public") Modifiers(AlreadyLooked / "public")
          |  / Token("static") Modifiers(AlreadyLooked / "static")
          |  / Token("final") Modifiers(AlreadyLooked / "final")
          |  / ""
          |);
          |Token(t) = t Spacing;
          |Spacing = " "*;
      """.stripMargin), "public static final", "public public", "public static public", "final static public", "final final")
  }

  def tryGrammar(name: String, grammar: Ast.Grammar, inputs: String*): Unit = {
    val evaluator = MacroPEGEvaluator(grammar)
    println("grammar: " + name)
    println()
    for(input <- inputs) {
      println("input:" + input)
      println(evaluator.evaluate(input, 'S).map{in => s"matched to ${input}"}.getOrElse{s"not matched to ${input}"})
      println()
    }
  }
}
