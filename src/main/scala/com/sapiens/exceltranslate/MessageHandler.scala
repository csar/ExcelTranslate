package com.sapiens.exceltranslate

import java.util.concurrent.TimeUnit

import akka.pattern.{ask, pipe}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import com.typesafe.config.Config
import javax.jms.{Message, MessageProducer, TextMessage}

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class MessageHandler(timeout: Timeout, sheets:Config) extends Actor with ActorLogging {
  import concurrent.ExecutionContext.Implicits.global
  private var workbooks =  Map.empty[String, ActorRef]

  private def getOrCreate(wbName:String) = workbooks.get(wbName) match {
    case Some(ref) => ref
    case None =>
      val ref =  context.actorOf(Props(classOf[WorkbookManager], wbName, sheets.getConfig(wbName)))
      workbooks += wbName->ref
      ref
  }
  private def split(body:String) : (String, String, String) = {
    val cmd = body.indexOf('\u0000', 0)
    val id = body.indexOf('\u0000', cmd+1)
    if (id<0)
      (body.substring(0,cmd), body.substring(cmd+1),"")
      else

    (body.substring(0,cmd), body.substring(cmd+1, id), body.substring(id+1))
  }
  def marshallInputs(vars: Seq[Variable]):String = s"OK\u0000${vars.size}\u0000${vars.map{v =>
    s"${v.no}\u0000${v.name}\u0000${v.dataType.id}\u0000${v.rows}\u0000${v.cols}"
  }.mkString("\u0000")}"
  def marshallResults(results: Seq[Result]):String = s"OK\u0000${results.size}\u0000${results.map{ vd =>
    val Result(v,data) = vd
    s"${v.no}\u0000${v.name}\u0000${v.dataType.id}\u0000${v.rows}\u0000${v.cols}\u0000${data.mkString("\u0000")}"
  }.mkString("\u0000")}"
  def errorString:PartialFunction[Throwable,String] = {
    case NonFatal(e) =>
      s"KO\u0000${e.getMessage}"
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
             ask(getOrCreate(sheet) , Get(params))(timeout).mapTo[String].map(c=>s"OK\u0000$c").recover(errorString).pipeTo(sender())
           case "find" =>
             sender !  s"KO\u0000command $cmd not implemented"
           case _ =>
             sender !  s"KO\u0000command $cmd undefined"
         }
       } catch {
         case NonFatal(e) =>
           sender() ! s"KO\u0000${e.getMessage}"
       }
     case m:Message =>
       log.warning(s"Received $m - no idea what to do with it")
   }
}

