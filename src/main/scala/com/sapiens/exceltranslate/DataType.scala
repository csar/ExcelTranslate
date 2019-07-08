package com.sapiens.exceltranslate

import org.apache.poi.ss.usermodel.CellType

sealed trait DataType {
  def cellType: CellType
}
object Numeric extends DataType {
  val cellType = CellType.NUMERIC

  override def toString: String = "number"
}

object DateType extends DataType {
  val cellType = CellType.NUMERIC
  override def toString: String = "date"
}

object Bool extends DataType {
  val cellType = CellType.BOOLEAN
  override def toString: String = "bool"
}

object Alpha extends DataType {
  val cellType = CellType.STRING
  override def toString: String = "string"
}
