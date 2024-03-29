package com.sapiens.exceltranslate

import java.io.FileInputStream

import com.sapiens.exceltranslate.Args.{Binding, CalcSheet}
import com.sapiens.exceltranslate.LoadExcel.arguments
import org.apache.poi.poifs.macros.VBAMacroReader
import org.apache.poi.ss.usermodel.CellType._
import org.apache.poi.ss.usermodel.{Sheet, Workbook}
import org.apache.poi.ss.util.{AreaReference, CellReference}

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.{Success, Try, Using}
import scala.util.control.NonFatal
import scala.xml.{NodeSeq, XML}

object BindingFactory {
  val FormulaIO: String = "FormulaIO"
  def fromFormulaIO(formulaIO:Sheet) = Try {
    val wb = formulaIO.getWorkbook
    val input = new ListBuffer[Variable]
    val output = new ListBuffer[Variable]
    for (line <- 1 to formulaIO.getLastRowNum) {
      val row = formulaIO.getRow(line)
      if (row != null && row.getCell(3)!=null && row.getCell(3).getCellType != BLANK) {

        val appendTo = if (Try(row.getCell(1).getBooleanCellValue).recoverWith{case _ => Try(!row.getCell(1).getStringCellValue.equalsIgnoreCase("output"))}.getOrElse(true)) input else output
        val root = row.getCell(3)
        val cr = root.getCellType match {
          case STRING =>
            new CellReference(root.getStringCellValue)
          case FORMULA =>
            new CellReference(root.getCellFormula)
        }
        val dataType = Try(wb.getSheet(cr.getSheetName).getRow(cr.getRow).getCell(cr.getCol).getCellType) match {
          case Success(NUMERIC) =>
            if (Try(row.getCell(2).getStringCellValue == "date").getOrElse(false)) DateType else Numeric
          case Success(STRING) => Alpha
          case Success(BOOLEAN) => Bool
          case _ => row.getCell(2).getStringCellValue.toLowerCase match {
            case "date" => DateType
            case "number" => Numeric
            case "string" => Alpha
            case "boolean" => Bool
          }
        }
        val end = row.getCell(4)
        val (ref: AreaReference, rows: Int, cols: Int) = if(end==null) (new AreaReference(cr.formatAsString(), wb.getSpreadsheetVersion), 1, 1) else {
          end.getCellType match {
            case BLANK =>
              (new AreaReference(cr.formatAsString(), wb.getSpreadsheetVersion), 1, 1)
            case STRING =>
              val text = end.getStringCellValue.trim
              if (text.isEmpty) (new AreaReference(cr.formatAsString(), wb.getSpreadsheetVersion), 1, 1)
              else {
                val er = Try(new CellReference(text)) recoverWith {
                  case NonFatal(_) => Try {
                    val rt = text.takeWhile(_.isDigit)
                    val left = text.drop(rt.size).dropWhile(!_.isDigit)
                    val rows = rt.toInt
                    val cols = left.toInt
                    if (rows * cols == 1) throw new RuntimeException("single cell")
                    new CellReference(cr.getSheetName, cr.getRow + rows - 1, cr.getCol + cols - 1, cr.isRowAbsolute, cr.isColAbsolute)
                  }
                }

                val ar = er map { er =>
                  (new AreaReference(cr, er, wb.getSpreadsheetVersion), er.getRow - cr.getRow + 1, er.getCol - cr.getCol + 1)
                } recover {
                  case NonFatal(_) => (new AreaReference(cr.formatAsString(), wb.getSpreadsheetVersion), 1, 1)
                }
                ar.get
              }
            case FORMULA =>
              val er = new CellReference(end.getCellFormula)
              (new AreaReference(cr, er, wb.getSpreadsheetVersion), er.getRow - cr.getRow + 1, er.getCol - cr.getCol + 1)
          }
        }
        val numerator = appendTo.size + 1
        appendTo append Variable(numerator, Try(row.getCell(0).getStringCellValue).getOrElse(numerator.toString), dataType, ref, rows, cols)
      }
    }

    InputOutput(input.toSeq, output.toSeq)
  }
  def fromMacro(wb:Workbook, fileName:String) = Using(new VBAMacroReader(new FileInputStream(fileName)))(_.readMacros().get("URS_VariableDefinition")) map { text =>

    val xml = Source.fromString(text).getLines().dropWhile(!_.contains("Document")).map(_ drop 1).filterNot(_.isEmpty).mkString("")
    val params = XML.loadString(xml)
    InputOutput(extractVariables(wb, params \ "Input"), extractVariables(wb, params \ "Output"))
  }

  def apply(wb:Workbook, fileName:String = arguments(CalcSheet),bindingXML:String = arguments(Binding)):InputOutput = {

     val formulaIO = wb.getSheet(FormulaIO)

    val bindings: Try[InputOutput] = if (formulaIO == null) {
      fromMacro(wb,fileName)
     } else {
       fromFormulaIO(formulaIO)
     }

     if (bindings.isFailure) {
       try {
         val params = XML.loadFile(bindingXML)
         InputOutput(extractVariables(wb, params \ "Input"), extractVariables(wb, params \ "Output"))
       } catch {
         case NonFatal(e) =>
           System.err.println(s"No bindings found and sheet and no valid xml definition found!", e)
           throw e
       }
     } else {
       try {
         val params = XML.loadFile(bindingXML)
         val custom = InputOutput(extractVariables(wb, params \ "Input"), extractVariables(wb, params \ "Output"))
         println("Using custom bindings instead of inner binding")
         custom
       } catch {
         case NonFatal(e) =>
           bindings.get
       }
     }
   }
  def extractVariables(wb:Workbook,nodes: NodeSeq) = nodes.flatMap(_.descendant).filter(_.label.startsWith("Variable_")).zipWithIndex.map { indexed =>
    val (node, index ) = indexed
    val no = index+1
    val name = (node \ "@VarName").toString
    val cellType = (node \ "@VariableType").toString() match {
      case "number" => Numeric
      case "date" => DateType
    }

    val addr = (node \ "@Address").toString()
    val reference = new AreaReference(addr, wb.getSpreadsheetVersion)

    val rows = Try((node \ "@Rows").toString().toInt).getOrElse(1)
    val cols = Try((node \ "@Cols").toString().toInt).getOrElse(1)
    Variable(no, name, cellType, reference, rows, cols)
  }.sortBy(_.no)

}
