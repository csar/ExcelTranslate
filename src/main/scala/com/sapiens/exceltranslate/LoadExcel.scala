package com.sapiens.exceltranslate

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.Locale

import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.{AreaReference, DateFormatConverter}
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


object Args extends Enumeration {
  type Args = Value
  val CalcSheet, Binding, CaseFile, Iterations = Value
}

import com.sapiens.exceltranslate.Args._


case class InputOutput(input: Seq[Variable], output: Seq[Variable]) {
  val inputsize = input.map(_.cols).sum
  val outputsize = output.map(_.cols).sum

  val stride = input.map(_.rows).max max output.map(_.rows).max
}

object LoadExcel extends App {

  import CellType._

  var arguments = Map(Args.CalcSheet -> args(0))
  for (arg <- args.tail) {
    if (arg.endsWith(".xml")) arguments += Binding -> arg
    else {
      Try(arg.toInt) match {
        case Success(n) =>
          arguments += Iterations -> arg
        case Failure(_) =>
          arguments += CaseFile -> arg
      }
    }
  }
  val wb = WorkbookFactory.create(new FileInputStream(arguments(CalcSheet)))

  val io = BindingFactory(wb)
  val InputOutput(input, output) = io
  val testdata = Try(WorkbookFactory.create(new FileInputStream(arguments(CaseFile))))
  val iterations = Try(arguments(Iterations).toInt).getOrElse(1) max 1


  val evaluator = wb.getCreationHelper().createFormulaEvaluator()

  var runs = Map.empty[Int, List[Seq[(Array[Try[CellValue]], Long)]]]
  val (testwb, dateFormat)  = testdata match {
    case Success(testwb) =>
      val sheet = testwb.getSheetAt(0)

      for (ri <- 1.to(sheet.getLastRowNum, io.stride); row <- Option(sheet.getRow(ri)) if row.getFirstCellNum < io.inputsize) {
        runs += ri -> Nil
      }
      var df :CellStyle = testwb.getCellStyleAt(BuiltinFormats.FIRST_USER_DEFINED_FORMAT_INDEX)
      (testwb,df)
    case Failure(_) => // initialize with values from the sheet
      val testwb = new XSSFWorkbook()
      val resultStyle = testwb.createCellStyle()
      resultStyle.setFillForegroundColor(0x2B.toShort)
      resultStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      val dateFormat = testwb.createCellStyle()

    {
      val df = testwb.createDataFormat()
      val fmt = df.getFormat(DateFormatConverter.convert(Locale.US, "yyyy-MM-dd"))
      dateFormat.setDataFormat(fmt)
    }
      val sheet = testwb.createSheet()
      val header = sheet.createRow(0)
      val example = sheet.createRow(1)
      var colcount = 0
      for (variable <- input) {
        header.createCell(colcount, CellType.STRING).setCellValue(variable.name)
        val (anchor, r0, c0) = (variable.reference.getFirstCell.getSheetName, variable.reference.getFirstCell.getRow, variable.reference.getFirstCell.getCol)
        val sourceSheet = wb.getSheet(anchor)
        for (r <- 0 until variable.rows) {
          val sourceRow = sourceSheet.getRow(r0 + r)
          val targetRow = if (sheet.getRow(r + 1) == null) sheet.createRow(r + 1) else sheet.getRow(r + 1)
          for (c <- 0 until variable.cols) {
            val sourceCell = sourceRow.getCell(c0 + c)
            val ec = targetRow.createCell(colcount + c, variable.cellType)
            variable.dataType match {
              case Numeric => ec.setCellValue(sourceCell.getNumericCellValue)
              case DateType =>
                ec.setCellValue(sourceCell.getDateCellValue)
                ec.setCellStyle(dateFormat)
              case Bool => ec.setCellValue(sourceCell.getBooleanCellValue)
            }
          }
        }
        colcount += variable.cols
      }
      val resultStart = colcount
      for (result <- output) {


        val rh = header.createCell(colcount, CellType.STRING)
        rh.setCellValue(result.name)
        rh.setCellStyle(resultStyle)
        header.createCell(colcount + result.cols, CellType.STRING).setCellValue("ms")
        colcount += result.cols + 1

      }
      if (output.size > 1) {
        // total column
        header.createCell(colcount).setCellValue("Total Time")

      }
      runs += 1 -> Nil
      (testwb, dateFormat)

  }

  val inputDataSheet = testwb.getSheetAt(0)
  // now the real computations
  for (iter <- 1 to iterations; ri <- runs.keys.toList.sorted) {
    val results = compute(ri)
    runs += ri -> (results :: runs(ri))
    println(s"$iter done for row $ri")
  }
  val resultStart = io.inputsize
  // now put the responses to the line
  for ((ri:Int, computations: List[Seq[(Array[Try[CellValue]], Long)]]) <- runs) yield {
    var total = 0.0
    var offset = resultStart
    for (v <- output) {
      val forResult = computations.map(_ (v.no - 1))
      for( r <- 0 until v.rows; c <- 0 until v.cols) {
        val target = inputDataSheet.getRow( ri + r).createCell(offset + c )
        val index = r*v.cols+c
        val field = forResult.map(_._1(index))
        val successes = field.flatMap(_.toOption)
        val fails = field.collect{case NonFatal(e) => e.getMessage}
        val variations =  successes.map(t=> t.formatAsString() -> t).toMap
        if (variations.size==1) {
          v.dataType match {
            case Numeric => target setCellValue successes.head.getNumberValue
            case DateType =>
              target setCellValue successes.head.getNumberValue
              target.setCellStyle(dateFormat)
          }
        } else if ( variations.isEmpty){ // ERROR
          target.setCellValue(fails.head)
          target.setCellStyle(null)
        } else {
          target.setCellValue(variations.values.map(_.formatAsString()).mkString("!"))
          target.setCellStyle(null)

        }

      }
      val times: List[Double] = forResult.map(_._2 / 1000000.0)
      val avg = times.sum / (forResult.size)
      total += avg
      offset += v.cols +1

    }

    if (output.size > 1) {
      val target = inputDataSheet.getRow(ri).createCell(resultStart + output.size * 2)
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
      for ((ri, computations: List[Seq[(Array[Try[CellValue]], Long)]]) <- runs) {
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
    val header = inputDataSheet.getRow(0)
    input.foreach(v => header.createCell(v.no - 1).setCellValue(v.name))
    output.foreach(v => header.createCell(resultStart + v.no * 2 - 2).setCellValue(v.name))
    output.foreach(v => header.createCell(resultStart + v.no * 2 + v.cols - 2).setCellValue("ms"))
    header.createCell(resultStart + output.size * 2).setCellValue("Total")
  }
  val fo = new FileOutputStream(arguments(CaseFile))
  println("Writing output")
  testwb.write(fo)
  fo.close()
  testwb.close()

  if (false) { // make a test
    val testwb = new XSSFWorkbook()
    val resultStyle = testwb.createCellStyle()
    resultStyle.setFillForegroundColor(0x2B.toShort)
    resultStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND)
    val dateFormat = testwb.createCellStyle()

    {
      val df = testwb.createDataFormat()
      val fmt = df.getFormat(DateFormatConverter.convert(Locale.US, "yyyy-MM-dd"))
      dateFormat.setDataFormat(fmt)
    }
    val sheet = testwb.createSheet()
    val header = sheet.createRow(0)
    val example = sheet.createRow(1)
    var colcount = 0
    for (variable <- input) {
      header.createCell(colcount, CellType.STRING).setCellValue(variable.name)
      val (anchor, r0, c0) = (variable.reference.getFirstCell.getSheetName, variable.reference.getFirstCell.getRow, variable.reference.getFirstCell.getCol)
      val sourceSheet = wb.getSheet(anchor)
      for (r <- 0 until variable.rows) {
        val sourceRow = sourceSheet.getRow(r0 + r)
        val targetRow = if (sheet.getRow(r + 1) == null) sheet.createRow(r + 1) else sheet.getRow(r + 1)
        for (c <- 0 until variable.cols) {
          val sourceCell = sourceRow.getCell(c0 + c)
          val ec = targetRow.createCell(colcount + c, variable.cellType)
          variable.dataType match {
            case Numeric => ec.setCellValue(sourceCell.getNumericCellValue)
            case DateType =>
              ec.setCellValue(sourceCell.getDateCellValue)
              ec.setCellStyle(dateFormat)
            case Bool => ec.setCellValue(sourceCell.getBooleanCellValue)
          }
        }
      }
      colcount += variable.cols
    }
    val resultStart = colcount
    // just compute what is there
    reset()
    var totalTime = 0.0
    for (result <- output) {
      print(result.name + ": ")
      val toCompute = result.cells(wb)
      var start = System.nanoTime()
      val results = toCompute.map(c => Try(evaluator.evaluate(c)))
      start = System.nanoTime() - start


      for (r <- 0 until result.rows) {
        val targetRow = if (sheet.getRow(r + 1) == null) sheet.createRow(r + 1) else sheet.getRow(r + 1)
        for (c <- 0 until result.cols) {
          val ec = targetRow.createCell(colcount + c)
          results(r * result.cols + c) match {
            case Success(cv) =>
              result.dataType match {
                case Numeric => ec.setCellValue(cv.getNumberValue)
                case DateType =>
                  ec.setCellValue(DateUtil.getJavaDate(cv.getNumberValue))
                  ec.setCellStyle(dateFormat)
                case Bool => ec.setCellValue(cv.getBooleanValue)
              }
            case Failure(e) =>
              ec.setCellValue(e.getMessage)

          }


        }
      }


      val rh = header.createCell(colcount, CellType.STRING)
      rh.setCellValue(result.name)
      rh.setCellStyle(resultStyle)
      header.createCell(colcount + result.cols, CellType.STRING).setCellValue("ms")
      val time = start / 1000000.0
      totalTime += time
      example.createCell(colcount + result.cols, CellType.NUMERIC).setCellValue(time)
      println(s"${results(0)} in ${time}ms")

      colcount += result.cols + 1

    }
    if (output.size > 1) {
      // total column
      header.createCell(colcount).setCellValue("Total Time")
      example.createCell(colcount).setCellValue(totalTime)

    }
    var outFile = new File(arguments(CaseFile))
    if (outFile.exists()) outFile = new File(args(1).dropRight(4) + System.currentTimeMillis() + ".xlsx")
    testwb.write(new FileOutputStream(outFile))
  }
  if (wb.getSheet(BindingFactory.FormulaIO) == null) {
    val sheet = wb.createSheet(BindingFactory.FormulaIO)
    var row = sheet.createRow(0)
    row.createCell(0).setCellValue("Name")
    row.createCell(1).setCellValue("Input")
    row.createCell(2).setCellValue("Type")
    row.createCell(3).setCellValue("Cell")
    row.createCell(4).setCellValue("Range")
    for (variable <- input) {
      row = sheet.createRow(variable.no)
      row.createCell(0).setCellValue(variable.name)
      row.createCell(1).setCellValue(true)
      row.createCell(2).setCellValue(variable.dataType.toString)
      row.createCell(3).setCellValue(variable.reference.getFirstCell.formatAsString())
      if (!variable.reference.isSingleCell) row.createCell(4).setCellValue(s"${variable.rows} * ${variable.cols}")
    }
    for (variable <- output) {
      row = sheet.createRow(variable.no + input.size)
      row.createCell(0).setCellValue(variable.name)
      row.createCell(1).setCellValue(false)
      row.createCell(2).setCellValue(variable.dataType.toString)
      row.createCell(3).setCellValue(variable.reference.getFirstCell.formatAsString())
      if (!variable.reference.isSingleCell) row.createCell(4).setCellValue(s"${variable.rows} * ${variable.cols}")
    }


    wb.write(new FileOutputStream(new File(arguments(CalcSheet)).getName))
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

  def reset(): Unit = input.flatMap(v => v.cells(wb)).foreach { cell => evaluator.notifyUpdateCell(cell) }

  def setValues(line: Int) = {
    var offset = 0
    for (variable <- input) {
      val targets = variable.cells(wb)
      val parameters = for (r <- 0 until variable.rows; c <- 0 until (variable.cols)) yield {
        inputDataSheet.getRow(line + r).getCell(offset + c)
      }
      for ((target, parameter) <- targets zip parameters) {
        setValue(target, parameter)
      }

      offset += variable.cols
    }
  }

  def compute(line: Int): Seq[(Array[Try[CellValue]], Long)] = {
    setValues(line)
    reset()

    output map { result =>
      var start = System.nanoTime()
      val resultString = result.cells(wb).map(c=> Try(evaluator.evaluate(c)))

      start = System.nanoTime() - start


      (resultString, start)
    }
  }

}


case class Variable(no: Int, name: String, dataType: DataType, reference: AreaReference, rows: Int = 1, cols: Int = 1) {
  def cellType: CellType = dataType.cellType

  def asString(value: CellValue): String = dataType match {
    case Numeric => value.getNumberValue.toString
    case DateType => value.getNumberValue.toString
  }

  def setStringTyped(target: Cell, result: String) = dataType match {
    case Numeric => target.setCellValue(result.toDouble)
    case Alpha => target.setCellValue(result)
    case Bool => target.setCellValue(result.toBoolean)
  }

  def cells(wb: Workbook) = reference.getAllReferencedCells map { cr =>
    wb.getSheet(cr.getSheetName).getRow(cr.getRow).getCell(cr.getCol)
  }
}