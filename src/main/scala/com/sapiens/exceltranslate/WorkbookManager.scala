package com.sapiens.exceltranslate

import java.io.{File, FileInputStream}

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Status}
import akka.util.Timeout
import com.sapiens.exceltranslate.WorkerState.WorkerState
import com.typesafe.config.Config
import org.apache.poi.ss.usermodel.WorkbookFactory

import scala.collection.mutable
import scala.concurrent.{Future, Promise, blocking}
import scala.util.{Failure, Success, Try}

trait Action
case object Inputs extends Action
case object Outputs extends Action
case class Eval(varString:String) extends Action

case object Dequeue

class WorkbookManager(timeout:Timeout, config: Config) extends Actor with ActorLogging  {
  implicit val ec = context.dispatcher
  var io :InputOutput=_
  var workers = Map.empty[ActorRef, WorkerState.WorkerState]
  val work = new mutable.Queue[(ActorRef,Action)]
  var metas = List.empty[(ActorRef,Action)]


  override def preStart(): Unit = {

    Future {
      blocking {
        val file = config.getString("file")
        val workbook = WorkbookFactory.create(new FileInputStream(file))
        io = BindingFactory(workbook, file,"")
        workers += context.actorOf(Props(classOf[WorkbookInstance],workbook, io.output)) -> WorkerState.New
        for( (ref, action) <- metas.reverse) {
          self.tell(action,ref)
        }
        io
      }
    } onComplete {
      case Success(io) =>
        context become ready(io).orElse(workermanagement)
        replay
      case Failure(exception) =>
        context become failure(exception )
        replay
    }
  }

  private def execute():Unit = if (work.nonEmpty){
    workers.find(_._2==WorkerState.Ready) match {
      case None => // check if we start a new worker
      case Some(worker) =>

    }
  }

  def ready(io:InputOutput): Receive = {
    case Dequeue =>

      for( (worker, state) <- workers if state==WorkerState.Ready && work.nonEmpty) {
        workers += worker-> WorkerState.Busy
        val (caller, action) = work.dequeue()
        val call = action match {
          case Eval(params) =>
            val atoms = params.split('\u0000')
            var index = 1 //skip the size
            var binding = Map.empty[Variable,Array[String]]
            for (vi <- 0 until atoms(0).toInt) {
              val variable = io.input(vi)
              val arrSize = variable.cols*variable.rows
              binding += variable -> atoms.slice(index,index+arrSize)
              index+=arrSize
            }
            Calculate(binding)
        }

        worker.tell(call,caller)
      }

    case Inputs =>
      sender() ! io.input
    case Outputs =>
      sender() ! io.output
    case e:Eval =>
      work enqueue Tuple2(sender(),e)
      self ! Dequeue
  }
  def failure(exception: Throwable): Receive = {
    case _:Action =>
      sender() ! Status.Failure(exception)
    case Dequeue =>
      for ((ref,_) <- work.dequeueAll(_ => true) ) ref ! Status.Failure(exception)
  }
  def replay:Unit = {
      for( (ref,cmd) <- metas) self.tell(cmd,ref)
      self ! Dequeue
  }
  def workermanagement:Receive = {
    case state: WorkerState =>
      import WorkerState._
      state match {
        case Terminating => workers -= sender()
        case Ready =>
          workers += sender() -> state
          self ! Dequeue
        case _ => //
      }
  }
  override def receive: Receive = workermanagement orElse {
    case e:Eval=>
      work += sender() -> e
    case Inputs =>
      metas ::= (sender(),Inputs)
    case Outputs =>
      metas ::= (sender(),Outputs)
   }
}

object WorkerState extends Enumeration {
  type WorkerState = Value
  val New,Ready,Busy,Terminating = Value
}