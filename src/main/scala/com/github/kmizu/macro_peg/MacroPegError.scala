package com.github.kmizu.macro_peg

import Ast.Position

sealed trait MacroPegError {
  def pos: Position
  def message: String
}

case class ParseError(pos: Position, message: String) extends MacroPegError

case class TypeError(pos: Position, message: String) extends MacroPegError


