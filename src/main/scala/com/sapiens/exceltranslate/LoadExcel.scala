package com.sapiens.exceltranslate

import java.io.{File, FileInputStream, FileOutputStream}

import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.{CellAddress, CellReference}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scala.xml.{NodeSeq, XML}

object LoadExcel extends App {

  import CellType._

  val wb = WorkbookFactory.create(new FileInputStream(args(0)))
  val params = XML.loadFile(args(1))
  val testdata = Try(WorkbookFactory.create(new FileInputStream(args(2))))
  val iterations = Try(args(if (testdata.isFailure) 2 else 3).toInt).getOrElse(1) max 1

  def extractVariables(nodes: NodeSeq) = nodes.flatMap(_.descendant).filter(_.label.startsWith("Variable_")).map { node =>
    val no = node.label.drop(9).toInt
    val name = (node \ "@VarName").toString
    val cellType = (node \ "@VariableType").toString() match {
      case "number" => CellType.NUMERIC
    }

    val addr = (node \ "@Address").toString()
    val reference = new CellReference(addr)
    val rows = Try((node \ "@Rows").toString().toInt).getOrElse(1)
    val cols = Try((node \ "@Cols").toString().toInt).getOrElse(1)
    Variable(no, name, cellType, reference, rows, cols)
  }.sortBy(_.no)

  val input = extractVariables(params \ "Input")
  val output = extractVariables(params \ "Output")


  val evaluator = wb.getCreationHelper().createFormulaEvaluator()
  testdata match {
    case Success(testwb) =>
      val sheet = testwb.getSheetAt(0)
      var runs = Map.empty[Int, List[Seq[(Try[String], Long)]]]
      for (ri <- 1 to sheet.getLastRowNum; row <- Option(sheet.getRow(ri)) if row.getFirstCellNum < input.size) {
        runs += ri -> Nil
      }
      for (iter <- 1 to iterations; ri <- runs.keys.toList.sorted) {
        val row = sheet.getRow(ri)
        val results = compute(row)
        runs += ri -> (results :: runs(ri))
        println(s"$iter done for row $ri")
      }
      val resultStart = input.size
      // now put the responses to the line
      for ((ri, computations: List[Seq[(Try[String], Long)]]) <- runs) yield {
        var total = 0.0
        val line = sheet.getRow(ri)
        for (r <- output) {
          val target = line.createCell(resultStart + r.no * 2 - 2)
          val forResult = computations.map(_ (r.no - 1))
          val times: List[Double] = forResult.map(_._2 / 1_000_000.0)
          val avg = times.sum / (forResult.size)
          total += avg
          line.createCell(resultStart + r.no * 2 - 1).setCellValue(avg)
          val outcomes = forResult.map(_._1)
          val successes = outcomes.flatMap(_.toOption)
          if (successes.isEmpty) {
            val errorText = outcomes.collect { case Failure(e) => e.getMessage }.head
            target.setCellValue(errorText)
          } else {
            val variations = successes.toSet
            if (variations.size > 1) {
              target.setCellValue(variations.mkString(", "))
            } else {
              r.setStringTyped(target, successes.head)
            }
          }

        }

        if (output.size > 1) {
          val target = line.createCell(resultStart + output.size * 2)
          target.setCellValue(total)
        }


      }
      println("Results set")
      if (iterations > 1) {
        while (Try(testwb.getSheetAt(1)).isSuccess) {
          testwb.removeSheetAt(1)
        }
        for (v <- output) {
          val sheet = testwb.createSheet(v.name)
          val header = sheet.createRow(0)
          for (iteration <- 0 to iterations) {
            header.createCell(iteration).setCellValue(iteration + 1)
          }
          header.createCell(iterations + 0).setCellValue("Average")
          header.createCell(iterations + 1).setCellValue("Min")
          header.createCell(iterations + 2).setCellValue("Max")
          header.createCell(iterations + 3).setCellValue("Variance")
          for ((ri, computations: List[Seq[(Try[String], Long)]]) <- runs; calc: Seq[Double] = computations.map(_ (v.no - 1)).map(_._2 / 1000000.0).reverse) {
            val row = sheet.createRow(ri)
            for (iteration <- 0 until iterations) {
              row.createCell(iteration).setCellValue(calc(iteration))
            }
            val avg = calc.sum / calc.size
            row.createCell(iterations + 0).setCellValue(avg)
            row.createCell(iterations + 1).setCellValue(calc.min)
            row.createCell(iterations + 2).setCellValue(calc.max)
            row.createCell(iterations + 3).setCellValue(calc.map(_ - avg).map(t => t * t).sum / (calc.size - 1))
          }
          println(s"Details set for ${v.name}")

        }
        if (output.size > 1) {
          val sheet = testwb.createSheet("Compute All Results")
          val header = sheet.createRow(0)
          header.createCell(iterations + 0).setCellValue("Average")
          header.createCell(iterations + 1).setCellValue("Min")
          header.createCell(iterations + 2).setCellValue("Max")
          header.createCell(iterations + 3).setCellValue("Variance")
          for (iteration <- 1 to iterations) {
            header.createCell(iteration - 1).setCellValue(iteration)
          }
          for ((ri, computations: List[Seq[(Try[String], Long)]]) <- runs) {
            val calc = computations.reverse.map(_.map(_._2 / 1000000.0).sum)
            val row = sheet.createRow(ri)
            for (iteration <- 0 until iterations) {
              row.createCell(iteration).setCellValue(calc(iteration))
            }
            val avg = calc.sum / calc.size
            row.createCell(iterations + 0).setCellValue(avg)
            row.createCell(iterations + 1).setCellValue(calc.min)
            row.createCell(iterations + 2).setCellValue(calc.max)
            row.createCell(iterations + 3).setCellValue(calc.map(_ - avg).map(t => t * t).sum / (calc.size - 1))
          }

          println(s"Aggregation set")

        }
        val header = sheet.getRow(0)
        input.foreach(v => header.createCell(v.no - 1).setCellValue(v.name))
        output.foreach(v => header.createCell(resultStart + v.no * 2 - 2).setCellValue(v.name))
        output.foreach(v => header.createCell(resultStart + v.no * 2 - 1).setCellValue("ms"))
        header.createCell(resultStart + output.size * 2).setCellValue("Total")
      }
      val fo = new FileOutputStream(args(2))
      println("Writing output")
      testwb.write(fo)
      fo.close()
      testwb.close()

    case Failure(_) =>
      // make a test
      val reference = new XSSFWorkbook()
      val resultStyle = reference.createCellStyle()
      resultStyle.setFillForegroundColor(0x2B.toShort)
      resultStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      val sheet = reference.createSheet()
      val header = sheet.createRow(0)
      val example = sheet.createRow(1)
      var colcount = 0
      for (variable <- input) {
        header.createCell(colcount, CellType.STRING).setCellValue(variable.name)
        val ec = example.createCell(colcount, variable.cellType)

        variable.cellType match {
          case NUMERIC => ec.setCellValue(variable.cell(wb).getNumericCellValue)
        }
        colcount += 1
      }
      val resultStart = colcount
      // just compute what is there
      reset()
      for (result <- output) {
        print(result.name + ": ")
        var start = System.nanoTime()
        val resultString = Try(result.asString {

          val r = evaluator.evaluate(result.cell(wb))
          start = System.nanoTime() - start
          val rc = example.createCell(colcount, result.cellType)
          result.cellType match {
            case NUMERIC => rc.setCellValue(r.getNumberValue)
          }
          r
        }).recover { case NonFatal(e) =>
          start = System.nanoTime() - start
          example.createCell(colcount, CellType.STRING).setCellValue(e.getMessage)
          e.getMessage
        }.get


        val rh = header.createCell(colcount, CellType.STRING)
        rh.setCellValue(result.name)
        rh.setCellStyle(resultStyle)
        header.createCell(colcount + 1, CellType.STRING).setCellValue("ms")
        example.createCell(colcount + 1, CellType.NUMERIC).setCellValue(start / 1_000_000.0)
        println(s"$resultString in ${start / 1_000_000.0}ms")

        colcount += 2

      }
      if ((colcount - resultStart) > 2) {
        // total column
        header.createCell(colcount).setCellValue("Total Time")
        val formula = (resultStart + 1).to(colcount, 2).map(c => new CellAddress(1, c).formatAsString()).mkString("+")

        example.createCell(colcount, FORMULA).setCellFormula(formula)

      }
      var outFile = new File(args(1).dropRight(3) + "xlsx")
      if (outFile.exists()) outFile = new File(args(1).dropRight(4) + System.currentTimeMillis() + ".xlsx")
      reference.write(new FileOutputStream(outFile))
  }

  def setValue(target: Cell, source: Cell): Unit = {
    source.getCellType match {
      case NUMERIC =>
        target.setCellValue(source.getNumericCellValue)
      case BLANK =>
        target.setBlank()
      case STRING =>
        target.setCellValue(source.getStringCellValue)
      case BOOLEAN =>
        target.setCellValue(source.getBooleanCellValue)
    }
  }

  def reset(): Unit = input.foreach(v => evaluator.notifyUpdateCell(v.cell(wb)))

  def setValues(row: Row) = {
    for (variable <- input) {
      val target = variable.cell(wb)
      val parameter = row.getCell(variable.no - 1)
      setValue(target, parameter)

    }
  }

  def compute(row: Row): Seq[(Try[String], Long)] = {
    setValues(row)
    reset()

    output map { result =>
      var start = System.nanoTime()
      val resultString = Try(result.asString {

        val r = evaluator.evaluate(result.cell(wb))
        start = System.nanoTime() - start
        r
      }).recoverWith { case NonFatal(e) =>
        start = System.nanoTime() - start
        Failure(e)
      }


      (resultString, start)
    }
  }

}


case class Variable(no: Int, name: String, cellType: CellType, reference: CellReference, rows: Int = 1, cell: Int = 1) {

  import CellType._

  def asString(value: CellValue): String = cellType match {
    case NUMERIC => value.getNumberValue.toString
  }

  def setStringTyped(target: Cell, result: String) = cellType match {
    case NUMERIC => target.setCellValue(result.toDouble)
    case STRING => target.setCellValue(result)
    case BOOLEAN => target.setCellValue(result.toBoolean)
  }

  def cell(wb: Workbook) = wb.getSheet(reference.getSheetName).getRow(reference.getRow).getCell(reference.getCol)
}