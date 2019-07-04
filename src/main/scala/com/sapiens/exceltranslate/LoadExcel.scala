package com.sapiens.exceltranslate

import java.io.{File, FileInputStream, FileOutputStream}

import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.{CellAddress, CellReference}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, NodeSeq, XML}

object LoadExcel extends App {
  import CellType._
  val wb = WorkbookFactory.create(new FileInputStream(args(0)))
  val params = XML.loadFile(args(1))
  val testdata = Try(WorkbookFactory.create(new File(args(2))))
  def extractVariables(nodes:NodeSeq) = nodes.flatMap(_.descendant).filter(_.label.startsWith("Variable_")).map {node =>
    val no = node.label.drop(9).toInt
    val name =  (node \ "@VarName").toString
    val cellType = (node \ "@VariableType").toString() match {
      case "number" => CellType.NUMERIC
    }

    val addr =  (node \ "@Address").toString()
    val reference = new CellReference(addr)
    val rows = Try((node \ "@Rows").toString().toInt).getOrElse(1)
    val cols = Try((node \ "@Cols").toString().toInt).getOrElse(1)
    Variable(no,name, cellType, reference , rows, cols)
  }.sortBy(_.no)
  val input = extractVariables(params \ "Input" )
  val output = extractVariables(params \ "Output" )



  val evaluator = wb.getCreationHelper().createFormulaEvaluator()
  testdata match {
    case Success(testwb) =>
    case Failure(_ ) =>
      // make a test
      val reference = new XSSFWorkbook()
      val resultStyle = reference.createCellStyle()
      resultStyle.setFillForegroundColor(0x2B.toShort)
      resultStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      val sheet = reference.createSheet()
      val header = sheet.createRow(0)
      val example = sheet.createRow(1)
      var colcount=0
      for (variable <- input) {
        header.createCell(colcount, CellType.STRING).setCellValue(variable.name)
        val ec = example.createCell(colcount, variable.cellType)

        variable.cellType match {
          case NUMERIC => ec.setCellValue(variable.cell(wb).getNumericCellValue)
        }
        colcount +=1
      }
      val resultStart = colcount
      // just compute what is there
      for (result <- output) {
        print(result.name+": ")
        var start = System.nanoTime()
        val resultString = Try( result.asString {

          val r = evaluator.evaluate(result.cell(wb))
          start = System.nanoTime()-start
          val rc = example.createCell(colcount, result.cellType)
          result.cellType match {
            case NUMERIC => rc.setCellValue(r.getNumberValue)
          }
          r
        }).recover{case NonFatal(e) =>
          start = System.nanoTime()-start
          example.createCell(colcount, CellType.STRING).setCellValue(e.getMessage)
          e.getMessage}.get


        val rh = header.createCell(colcount, CellType.STRING)
          rh.setCellValue(result.name)
          rh.setCellStyle(resultStyle)
        header.createCell(colcount+1, CellType.STRING).setCellValue("ms")
        example.createCell(colcount+1,CellType.NUMERIC ).setCellValue(start/1_000_000.0)
        println(s"$resultString in ${start/1_000_000.0}ms")

        colcount+=2

      }
      if ( (colcount-resultStart)>2) {
        // total column
        header.createCell(colcount).setCellValue("Total Time")
        val formula =  (resultStart+1).to(colcount,2).map(c=> new CellAddress(1,c).formatAsString()).mkString("+")

         example.createCell(colcount, FORMULA).setCellFormula( formula)

      }
      var outFile = new File(args(1).dropRight(3)+"xlsx")
      if (outFile.exists())  outFile = new File(args(1).dropRight(4)+System.currentTimeMillis()+ ".xlsx")
      reference.write(new FileOutputStream(outFile))
  }
  //  val cr = new CellReference("Control!$K$7")
//  val controlSheet = wb.getSheet(cr.getSheetName)
//  val resultCell = controlSheet.getRow(cr.getRow).getCell(cr.getCol)
//  val start = System.nanoTime()
//  val result = evaluator.evaluate(resultCell)
//  val duration = System.nanoTime()-start
//  val micro = 1_000_000
//
//  println(result, duration/ 1_000_000.0, "ms")

}


case class Variable(no:Int, name:String,cellType:CellType, reference :CellReference, rows:Int=1,cell:Int=1 ) {
  import CellType._
  def asString(value: CellValue): String = cellType match {
    case NUMERIC => value.getNumberValue.toString
  }
  def cell(wb:Workbook) = wb.getSheet(reference.getSheetName).getRow(reference.getRow).getCell(reference.getCol)
}