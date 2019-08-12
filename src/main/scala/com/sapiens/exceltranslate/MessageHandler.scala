package com.sapiens.exceltranslate

import java.io.File
import java.util.concurrent.TimeUnit

import akka.pattern.{ask, pipe}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import com.typesafe.config.Config
import javax.jms.{Message, MessageProducer, TextMessage}

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
object MessageHandler {
  val separator = '\u0006'
  val separatorString = "\u0006"
}
class MessageHandler(timeout: Timeout, sheets:Config) extends Actor with ActorLogging {
  import concurrent.ExecutionContext.Implicits.global
  private var workbooks =  Map.empty[String, ActorRef]

  private def getOrCreate(wbName:String) = workbooks.get(wbName) match {
    case Some(ref) => ref
    case None =>
      val ref =  context.actorOf(Props(classOf[WorkbookManager], wbName, Try(sheets.getConfig(wbName)).getOrElse(Service.config.getConfig("sheetDefaults"))))
      workbooks += wbName->ref
      ref
  }
  private def split(body:String) : (String, String, String) = {
    val cmd = body.indexOf(MessageHandler.separator, 0)
    val id = body.indexOf(MessageHandler.separator, cmd+1)
    if (id<0)
      (body.substring(0,cmd), body.substring(cmd+1),"")
      else

    (body.substring(0,cmd), body.substring(cmd+1, id), body.substring(id+1))
  }
  def marshallInputs(vars: Seq[Variable]):String = s"OK$separator${vars.size}$separator${vars.map{v =>
    s"${v.no}$separator${v.name}$separator${v.dataType.id}$separator${v.rows}$separator${v.cols}"
  }.mkString(separatorString)}"
  def marshallResults(results: Seq[Result]):String = s"OK$separator${results.size}$separator${results.map{ vd =>
    val Result(v,data) = vd
    s"${v.no}$separator${v.name}$separator${v.dataType.id}$separator${v.rows}$separator${v.cols}$separator${data.mkString(separatorString)}"
  }.mkString(separatorString)}"
  def errorString:PartialFunction[Throwable,String] = {
    case NonFatal(e) =>
      s"KO$separator${e.getMessage}"
   }
   def receive:Receive = {
     case t:TextMessage  =>
       log.debug(s"Received $t")
       val (cmd,sheet,params) = split(t.getText)
       try {
         cmd match {
           case "iarr" =>
             ask(getOrCreate(sheet) ,Inputs)(timeout).mapTo[Seq[Variable]].map(marshallInputs).recover(errorString).pipeTo(sender())
           case "oarr" =>
             ask(getOrCreate(sheet) ,Outputs)(timeout).mapTo[Seq[Variable]].map(marshallInputs).recover(errorString).pipeTo(sender())
           case "calc" =>
             ask(getOrCreate(sheet) , Eval(params))(timeout).mapTo[Seq[Result]].map(marshallResults).recover(errorString).pipeTo(sender())
           case "get" =>
             ask(getOrCreate(sheet) , Get(params))(timeout).mapTo[String].map(c=>s"OK$separator$c").recover(errorString).pipeTo(sender())
           case "find" =>
             sender !  s"KO${separator}command $cmd not implemented"
           case _ =>
             sender !  s"KO${separator}command $cmd undefined"
         }
       } catch {
         case NonFatal(e) =>
           sender() ! s"KO$separator${e.getMessage}"
       }
     case m:Message =>
       log.warning(s"Received $m - no idea what to do with it")
   }
}

