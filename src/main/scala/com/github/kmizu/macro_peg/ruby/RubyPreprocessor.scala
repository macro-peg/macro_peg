package com.github.kmizu.macro_peg.ruby

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared preprocessing utilities for Ruby source code, used by both
 * the handwritten RubyParser and the generated GeneratedRubyParser.
 */
object RubyPreprocessor {

  private case class PendingHeredoc(
    token: String,
    terminator: String,
    allowIndentedTerminator: Boolean,
    lines: scala.collection.mutable.ArrayBuffer[String]
  )

  private case class HeredocResumeState(
    quoteMode: Char = 0,
    percentOpen: Char = 0,
    percentClose: Char = 0,
    percentDepth: Int = 0,
    percentInterpolation: Boolean = false
  )

  private def percentLiteralStart(line: String, index: Int, limit: Int): Option[(Char, Char, Boolean)] = {
    if(index >= limit || line.charAt(index) != '%') None
    else {
      val percentTypes = Set('q', 'Q', 'w', 'W', 'i', 'I', 'r', 'x', 's')
      val (literalType, delimiterIndex) =
        if(index + 1 < limit && percentTypes.contains(line.charAt(index + 1))) {
          (Some(line.charAt(index + 1)), index + 2)
        } else {
          (None, index + 1)
        }
      if(delimiterIndex >= limit) None
      else {
        val delimiter = line.charAt(delimiterIndex)
        val pairedDelimiter = delimiter match {
          case '{' => Some('}')
          case '(' => Some(')')
          case '[' => Some(']')
          case '<' => Some('>')
          case '%' => Some('%')
          case '|' => Some('|')
          case '"' => Some('"')
          case '\'' => Some('\'')
          case '/' => Some('/')
          case _ => None
        }
        pairedDelimiter.map { close =>
          val interpolation =
            literalType match {
              case Some('q' | 'w' | 'i') => false
              case _ => true
            }
          (delimiter, close, interpolation)
        }
      }
    }
  }

  private def isCodePositionForHeredoc(line: String, index: Int): Boolean = {
    var i = 0
    var quoteMode: Char = 0
    var percentOpen: Char = 0
    var percentClose: Char = 0
    var percentDepth = 0
    var percentInterpolation = false
    var interpolationDepth = 0
    var resumeStack = List.empty[HeredocResumeState]

    while(i < index) {
      val ch = line.charAt(i)
      if(quoteMode != 0) {
        if(ch == '\\' && i + 1 < index) {
          i += 1
        } else if((quoteMode == '"' || quoteMode == '`') && ch == '#' && i + 1 < index && line.charAt(i + 1) == '{') {
          resumeStack = HeredocResumeState(quoteMode = quoteMode) :: resumeStack
          quoteMode = 0
          interpolationDepth = 1
          i += 1
        } else if(ch == quoteMode) {
          quoteMode = 0
        }
      } else if(percentClose != 0) {
        if(ch == '\\' && i + 1 < index) {
          i += 1
        } else if(percentInterpolation && ch == '#' && i + 1 < index && line.charAt(i + 1) == '{') {
          resumeStack =
            HeredocResumeState(
              percentOpen = percentOpen,
              percentClose = percentClose,
              percentDepth = percentDepth,
              percentInterpolation = percentInterpolation
            ) :: resumeStack
          percentOpen = 0
          percentClose = 0
          percentDepth = 0
          percentInterpolation = false
          interpolationDepth = 1
          i += 1
        } else if(ch == percentClose) {
          percentDepth -= 1
          if(percentDepth == 0) {
            percentOpen = 0
            percentClose = 0
            percentInterpolation = false
          }
        } else if(percentOpen != percentClose && ch == percentOpen) {
          percentDepth += 1
        }
      } else if(interpolationDepth > 0) {
        percentLiteralStart(line, i, index) match {
          case Some((open, close, interpolation)) =>
            percentOpen = open
            percentClose = close
            percentDepth = 1
            percentInterpolation = interpolation
            i += (if(line.charAt(i + 1) == open) 1 else 2)
          case None =>
            ch match {
              case '#' => return false
              case '\'' => quoteMode = '\''
              case '"' => quoteMode = '"'
              case '`' => quoteMode = '`'
              case '{' => interpolationDepth += 1
              case '}' =>
                interpolationDepth -= 1
                if(interpolationDepth == 0 && resumeStack.nonEmpty) {
                  val resume = resumeStack.head
                  resumeStack = resumeStack.tail
                  quoteMode = resume.quoteMode
                  percentOpen = resume.percentOpen
                  percentClose = resume.percentClose
                  percentDepth = resume.percentDepth
                  percentInterpolation = resume.percentInterpolation
                }
              case _ =>
            }
        }
      } else {
        percentLiteralStart(line, i, index) match {
          case Some((open, close, interpolation)) =>
            percentOpen = open
            percentClose = close
            percentDepth = 1
            percentInterpolation = interpolation
            i += (if(line.charAt(i + 1) == open) 1 else 2)
          case None =>
            ch match {
              case '#' => return false
              case '\'' => quoteMode = '\''
              case '"' => quoteMode = '"'
              case '`' => quoteMode = '`'
              case _ =>
            }
        }
      }
      i += 1
    }

    quoteMode == 0 && percentClose == 0
  }

  private def encodeDoubleQuoted(value: String): String = {
    val builder = new StringBuilder("\"")
    var index = 0
    while(index < value.length) {
      val c = value.charAt(index)
      c match {
        case '\\' => builder.append("\\\\")
        case '"' => builder.append("\\\"")
        case '\n' => builder.append("\\n")
        case '\r' => builder.append("\\r")
        case '\t' => builder.append("\\t")
        // Keep heredoc payload literal after normalization.
        case '#' if index + 1 < value.length && value.charAt(index + 1) == '{' =>
          builder.append("\\#")
        case _ =>
          builder.append(c)
      }
      index += 1
    }
    builder.append("\"").toString
  }

  def normalizeHeredoc(input: String): String = {
    if(!input.contains("<<")) return input
    val heredocPattern = raw"""(?<![\w\)\]\}'\x22\x60\\])<<([~-]?)(?:'([^'\n]*)'|"([^"\n]*)"|`([^`\n]*)`|([A-Za-z_][A-Za-z0-9_]*))""".r
    val heredocTokenPattern = raw"""__MACROPEG_HEREDOC_\d+__""".r
    val outputLines = scala.collection.mutable.ArrayBuffer.empty[String]
    val replacements = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    val pending = scala.collection.mutable.Queue.empty[PendingHeredoc]
    var nextId = 0
    val lines = input.split("\n", -1)

    lines.foreach { line =>
      if(pending.nonEmpty) {
        val current = pending.front
        val isTerminator =
          if(current.allowIndentedTerminator) line.trim == current.terminator
          else line == current.terminator
        if(isTerminator) {
          pending.dequeue()
          val content =
            if(current.lines.isEmpty) ""
            else current.lines.mkString("\n") + "\n"
          replacements += current.token -> encodeDoubleQuoted(content)
        } else {
          current.lines += line
        }
      } else {
        val matches = heredocPattern.findAllMatchIn(line).filter(m => isCodePositionForHeredoc(line, m.start)).toList
        if(matches.isEmpty) {
          outputLines += line
        } else {
          val tokens = matches.map { m =>
            val token = s"__MACROPEG_HEREDOC_${nextId}__"
            nextId += 1
            val marker = m.group(1)
            val terminator =
              Option(m.group(2))
                .orElse(Option(m.group(3)))
                .orElse(Option(m.group(4)))
                .orElse(Option(m.group(5)))
                .getOrElse("")
            val allowIndented = marker == "-" || marker == "~"
            pending.enqueue(
              PendingHeredoc(token, terminator, allowIndented, scala.collection.mutable.ArrayBuffer.empty[String])
            )
            token
          }
          val replacedLine = (matches zip tokens).reverse.foldLeft(line) { case (acc, (m, token)) =>
            acc.substring(0, m.start) + token + acc.substring(m.end)
          }
          outputLines += replacedLine
        }
      }
    }

    if(pending.nonEmpty) input
    else {
      val normalized = outputLines.mkString("\n")
      if(replacements.isEmpty) normalized
      else {
        val replacementMap = replacements.toMap
        heredocTokenPattern.replaceAllIn(
          normalized,
          m => java.util.regex.Matcher.quoteReplacement(replacementMap.getOrElse(m.matched, m.matched))
        )
      }
    }
  }

  def stripXOptionPreamble(input: String): String = {
    val lines = input.split("\n", -1).toList
    val shebangIndex = lines.indexWhere(_.startsWith("#!"))
    if(shebangIndex > 0 && lines.take(shebangIndex).exists(_.contains("-x"))) {
      lines.drop(shebangIndex + 1).mkString("\n")
    } else input
  }

  def stripEndMarker(input: String): String = {
    if(!input.contains("__END__")) return input
    val lines = input.split("\n", -1)
    val endIdx = lines.indexWhere(_ == "__END__")
    if(endIdx >= 0) lines.take(endIdx).mkString("\n") else input
  }

  /**
   * Apply all preprocessing steps in order:
   * 1. Strip -x option preamble (shebang handling)
   * 2. Normalize heredocs (inline them as string literals)
   * 3. Strip __END__ marker and everything after it
   */
  def preprocess(input: String): String =
    stripEndMarker(normalizeHeredoc(stripXOptionPreamble(input)))
}
