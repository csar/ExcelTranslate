package com.sapiens.exceltranslate

import javax.jms.{Message, TextMessage}

import scala.util.Try

object MessageParser {
  def apply(msg: Message) :Command = msg match {
    case t:TextMessage =>
      Try {
        val body = t.getText
        val atoms = body.split('\u0000')
        atoms.head match  {
          case "input" =>
            InputDescription(atoms(1))
          case "calc" =>
            Calculation(atoms(1),atoms.drop(2))
          case _ =>
            Unparseable
        }
      } getOrElse Unparseable
    case _ =>
      Unparseable
  }
}


sealed trait Command

case object Unparseable extends Command

/**
 * Command to return the input definition.
 * Parsed from
 * input identifier
 *
 * @param calculation
 */
case class InputDescription(calculation:String) extends Command

/**
 * Run the calculation with the given input
 * Parsed from calc identifier [Var ...]
 * where var is
 * no value
 * vars are separated by an additionol \0
 * value is a sequence text followed by \0, there must be rows*cols entries
 *
 * Example
 * calc\0PREMIUM\01\0this is text\0\0this is more text\0\0\02\03.9\0\0
 * @param calculation
 * @param input
 */
case class Calculation(calculation:String,input:Seq[String]) extends Command

