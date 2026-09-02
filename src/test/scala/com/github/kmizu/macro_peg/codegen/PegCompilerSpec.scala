package com.github.kmizu.macro_peg.codegen

import org.scalatest.funspec.AnyFunSpec
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

class PegCompilerSpec extends AnyFunSpec {
  private def writeTemp(name: String, content: String): String = {
    val dir = Files.createTempDirectory("pegc")
    val f = dir.resolve(name)
    Files.write(f, content.getBytes(StandardCharsets.UTF_8))
    f.toString
  }

  describe("PegCompiler.parseArgs") {
    it("requires a grammar file") {
      assert(PegCompiler.parseArgs(Nil).isLeft)
      assert(PegCompiler.parseArgs(Seq("--help")).isLeft)
      assert(PegCompiler.parseArgs(Seq("-o", "x.scala")).isLeft)
    }

    it("accepts flag-style options") {
      val Right(o) = PegCompiler.parseArgs(Seq(
        "g.mapeg", "-o", "Out.scala", "--object", "MyParser", "--package", "a.b",
        "--start", "Top", "--backend", "combinator", "--quiet"
      )): @unchecked
      assert(o.grammarFile == "g.mapeg")
      assert(o.outFile == "Out.scala")
      assert(o.objectName == "MyParser")
      assert(o.packageName == Some("a.b"))
      assert(o.startRule == "Top")
      assert(o.backend == Backend.Combinator)
      assert(o.quiet)
    }

    it("defaults to the recursive-descent backend and stdout") {
      val Right(o) = PegCompiler.parseArgs(Seq("g.mapeg")): @unchecked
      assert(o.backend == Backend.RecursiveDescent)
      assert(o.outFile == "-")
      assert(o.objectName == "GeneratedParser")
      assert(o.startRule == "S")
    }

    it("still accepts the legacy positional form") {
      val Right(o) = PegCompiler.parseArgs(Seq("g.mapeg", "P", "Top", "Out.scala", "pkg")): @unchecked
      assert(o.objectName == "P")
      assert(o.startRule == "Top")
      assert(o.outFile == "Out.scala")
      assert(o.packageName == Some("pkg"))
    }

    it("rejects unknown options and backends") {
      assert(PegCompiler.parseArgs(Seq("g.mapeg", "--bogus")).isLeft)
      assert(PegCompiler.parseArgs(Seq("g.mapeg", "--backend", "llvm")).isLeft)
    }
  }

  describe("PegCompiler.run") {
    it("writes a parser for a grammar that declares its own object/start via directives") {
      val grammar = writeTemp("ab.mapeg",
        """%object AbParser;
          |%start S;
          |S = "a" "b" !.;
          |""".stripMargin)
      val out = Paths.get(grammar).resolveSibling("AbParser.scala").toString
      val code = PegCompiler.run(Seq(grammar, "-o", out, "--quiet"))
      assert(code == 0)
      val generated = new String(Files.readAllBytes(Paths.get(out)), StandardCharsets.UTF_8)
      assert(generated.contains("object AbParser"))
      assert(generated.contains("def parseAll"))
    }

    it("reports a missing grammar file with exit code 1") {
      assert(PegCompiler.run(Seq("/nonexistent/definitely-missing.mapeg")) == 1)
    }

    it("reports a grammar error with exit code 2") {
      val grammar = writeTemp("bad.mapeg", "S = Undefined;\n")
      assert(PegCompiler.run(Seq(grammar, "--quiet", "-o", Paths.get(grammar).resolveSibling("x.scala").toString)) == 2)
    }
  }
}
