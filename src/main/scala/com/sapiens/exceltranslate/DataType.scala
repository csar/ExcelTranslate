package com.sapiens.exceltranslate

import org.apache.poi.ss.usermodel.CellType

sealed trait DataType {
  def cellType: CellType
  def id:Int
}
object Numeric extends DataType {
  val cellType = CellType.NUMERIC
  val id = 1
  override def toString: String = "number"
}

object DateType extends DataType {
  val cellType = CellType.NUMERIC
  val id = 3
  override def toString: String = "date"
}

object Bool extends DataType {
  val cellType = CellType.BOOLEAN
  val id = 2
  override def toString: String = "bool"
}

object Alpha extends DataType {
  val cellType = CellType.STRING
  val id = 0

  override def toString: String = "string"
}
