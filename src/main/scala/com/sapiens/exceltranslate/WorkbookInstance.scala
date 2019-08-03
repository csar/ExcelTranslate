package com.sapiens.exceltranslate

import akka.actor.{Actor, ActorLogging}
import org.apache.poi.ss.usermodel.Workbook

class WorkbookInstance(wb:Workbook, output:Seq[Variable]) extends Actor with ActorLogging {
  val evaluator = wb.getCreationHelper().createFormulaEvaluator()
  var currentBinding = Map.empty[Variable,Array[String]] withDefaultValue Array[String]()

  override def preStart(): Unit = context.parent ! WorkerState.Ready
  override def postStop():Unit = context.parent ! WorkerState.Terminating
  override def receive: Receive = {
    case Calculate(values) => {
      for( (variable, data)<- values) {
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


      val results = output map {variable =>
        val values =variable.cells(wb).map(evaluator.evaluate).map{cv =>
          variable.dataType  match {
            case Alpha => cv.getStringValue
            case Bool => if(cv.getBooleanValue) "1" else "0"
            case Numeric|DateType => cv.getNumberValue.toString
            case _ => ""
          }
        }
        Result(variable,values)
      }
      //val binding =  currentBinding.map(b => Result(b._1,b._2))
      sender ! results
      context.parent ! WorkerState.Ready
    }
  }
}

case class Calculate(values: Map[Variable,Array[String]])
case class Result(variable:Variable , values: Array[String])