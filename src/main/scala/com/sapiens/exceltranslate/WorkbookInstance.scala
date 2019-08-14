package com.sapiens.exceltranslate

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging}
import org.apache.poi.ss.usermodel.{CellType, FormulaEvaluator, Workbook}
import org.apache.poi.ss.util.CellReference

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class WorkbookInstance(id:String,wbf:Future[Workbook], output:Seq[Variable]) extends Actor with ActorLogging {
  var  wb:Workbook = _
  var evaluator:FormulaEvaluator = _
  var currentBinding = Map.empty[Variable,Array[String]] withDefaultValue Array[String]()

  override def preStart(): Unit = wbf.foreach{ wb =>
    this.wb = wb
    evaluator = wb.getCreationHelper().createFormulaEvaluator()
    log.info(s"Workbook instance ready ($id, $self)")
    context.parent ! WorkerState.Ready
  }(ExecutionContext.global)
  override def postStop():Unit = {
    wb.close()
    context.parent ! WorkerState.Terminating
  }

  def bind(values: Map[Variable, Array[String]]) = for( (variable, data)<- values) {
    val existing = currentBinding(variable)
    for( ((cell, string),idx) <- (variable.cells(wb) zip data).zipWithIndex) {
      variable.dataType match {
        case Alpha =>
          cell.setCellValue(string)
        case Numeric | DateType =>
          cell.setCellValue(string.toDouble)
        case Bool =>
          string.toBooleanOption match {
            case Some(bool) =>
              cell.setCellValue(bool)
            case None =>
              cell.setCellValue(string!="0" || string.nonEmpty)
          }
      }
      if (!existing.isDefinedAt(idx) || existing(idx)!=string ) {
        evaluator notifyUpdateCell cell
      }
      currentBinding += variable -> data
    }
  }

  def compute() = {
     output map { variable =>
      val values = variable.cells(wb).map(evaluator.evaluate).map { cv =>
        if (cv.getCellType == CellType.ERROR) ""
        else variable.dataType match {
          case Alpha => cv.getStringValue
          case Bool => if (cv.getBooleanValue) "1" else "0"
          case Numeric | DateType => cv.getNumberValue.toString
          case _ => ""
        }
      }
      Result(variable, values)
    }
  }

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    val start = System.nanoTime()
    super.aroundReceive(receive, msg)
    log.debug(s"${msg.getClass.getSimpleName} took ${(System.nanoTime()-start)/1_000_000.0}ms")
  }
  override def receive: Receive = {
    case Get(ref) =>
      try {
        val cr = new CellReference(ref)
        val cell = wb.getSheet(cr.getSheetName).getRow(cr.getRow).getCell(cr.getCol)
        val cv = evaluator.evaluate(cell)
        import org.apache.poi.ss.usermodel.CellType._
        val content = (cv.getCellType: @unchecked) match {
          case BLANK => ""
          case NUMERIC => cv.getNumberValue.toString
          case BOOLEAN => cv.getBooleanValue.toString
          case STRING => cv.getStringValue
          case ERROR => "Error:" + cv.getErrorValue

        }
        sender() ! content
      } catch {
        case NonFatal(e) =>
          sender() ! Failure(e)
      }
      context.parent ! WorkerState.Ready
    case t:Target => try {
      bind(t.values)
      val parameterCell = t.vary.cells(wb).head
      var current = parameterCell.getNumericCellValue
      val targetCell = t.target.cells(wb).head

      def eval(x:Double) = {
        parameterCell.setCellValue(x)
        val cv = evaluator.evaluate(targetCell)
        if (cv.getCellType == CellType.ERROR) None
        else Some(cv.getNumberValue-t.value)
      }

      var result = eval(current)
      var low = t.min
      var high = t.max
      var lowResult = Double.NaN
      var highResult = Double.NaN
      if(result.isEmpty) {
          // need to find a start

      }

      val last = result.get
      if (last<t.value) {
        lowResult = last
        low = current
      } else {
        highResult = last
        high = current
      }
      def squeezed = (high-low)<t.step
      def goodEnough:Boolean = {
        def absOK = Math.abs(result.get-t.value)<t.toleranceAbs
        def relOK = (result.get/t.value).abs match {
          case illegal if illegal.isInfinity || illegal.isNaN || illegal==0 => !t.toleranceRel.isNaN
          case rel => if (rel>1) 1/rel<t.toleranceRel else rel<t.toleranceRel
        }
        absOK || relOK || squeezed
      }

      var iterations = t.maxIterations

      while(iterations>0 && !goodEnough) {

        // next guess
        if(highResult.isNaN) {
          // low must be set
          current *= t.value/lowResult
        }
        else if(lowResult.isNaN) {
          // high must be set
          current *= highResult/t.value
        } else {
          // actual newton
          highResult-t.value
        }

        iterations -= 1
      }

      if (iterations==0 && !goodEnough) {
        throw new RuntimeException(s"Solve didn't converge in ${t.maxIterations} steps")
      } else {
        if (squeezed ) {
          if(current==high &&  highResult-t.value>t.value-lowResult) targetCell setCellValue low
          if(current==low &&  highResult-t.value<t.value-lowResult) targetCell setCellValue high
        }
        sender ! compute()
      }
    } catch {
      case NonFatal(e) =>
        sender() ! Failure(e)
    }
      context.parent ! WorkerState.Ready

    case Calculate(values) => try {
      bind(values)
//      log.info("Thinking")
//      Thread.sleep(1000)

      sender ! compute()
    } catch {
      case NonFatal(e) =>
        sender() ! Failure(e)
    }
      context.parent ! WorkerState.Ready
    }

}

case class Calculate(values: Map[Variable,Array[String]])
case class Target(values: Map[Variable,Array[String]], target: Variable, value:Double, toleranceAbs:Double, toleranceRel:Double, vary:Variable, step:Double=0.01,  min:Double=Double.MinValue, max:Double=Double.MaxValue, maxIterations:Int = 10)
case class Result(variable:Variable , values: Array[String])