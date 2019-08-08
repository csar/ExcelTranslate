package com.sapiens.exceltranslate

import java.io.{File, FileInputStream}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Stash, Status}
import akka.util.Timeout
import com.sapiens.exceltranslate.WorkerState.WorkerState
import com.typesafe.config.Config
import org.apache.poi.ss.usermodel.{Workbook, WorkbookFactory}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise, blocking}
import scala.util.{Failure, Success, Try}

trait Action
trait Meta extends Action
case object Inputs extends Meta
case object Outputs extends Meta
case class Eval(varString:String) extends Action
case class Get(ref:String) extends Action

case object Dequeue
case object CheckRemove

class WorkbookManager(id:String, config: Config) extends Actor with ActorLogging  {
  implicit val ec = context.dispatcher
  var io :InputOutput=_
  var workers = Map.empty[ActorRef, WorkerState.WorkerState]
  val work = new mutable.Queue[(ActorRef,Action)]
  var metas = List.empty[(ActorRef,Action)]

  val cancellable = context.system.scheduler.schedule(Duration(1, TimeUnit.MINUTES), Duration(Try(config.getDuration("keepAlive", TimeUnit.SECONDS) ) getOrElse 100,TimeUnit.SECONDS), self, CheckRemove )
  override def postStop():Unit = {
    cancellable.cancel()
  }

  def newWorkbook = Future {
    blocking {
      val file = config.getString("file")
     WorkbookFactory.create(new FileInputStream(file))
    }
  }
  def createInstance(wbf:Future[Workbook] = newWorkbook, output:Seq[Variable]=io.output):Unit = {
    log.info(s"Creating WorkbookInstance $id")
    workers += context.actorOf(Props(classOf[WorkbookInstance],id,  wbf, output)) -> WorkerState.New
  }
  override def preStart(): Unit = {
    // make the first Workbook instance to capture the IO definition
    newWorkbook map { workbook=>
        io = BindingFactory(workbook, config.getString("file"),"")
        createInstance(Future successful workbook, io.output)
        for( (ref, action) <- metas.reverse) {
          self.tell(action,ref)
        }
        io

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

  val maxWorkers = Try(config.getInt("maxWorkers")) getOrElse 1
  val creationDamping = Try(config.getDouble("creationDamping")) getOrElse 1.0
  val keepMin = Try(config.getInt("keepMin")) getOrElse 1
  def ready(io:InputOutput): Receive = {
    case Dequeue if work.nonEmpty =>
      val ready = workers.filter(_._2==WorkerState.Ready).keys.iterator
      while(work.nonEmpty && ready.nonEmpty)
      {

        val (caller, action) = work.dequeue()
        val call = Try(action match {
          case Eval(params) =>
            val atoms = params.split(MessageHandler.separator)
            var index = 1 //skip the size
            var binding = Map.empty[Variable,Array[String]]
            for (vi <- 0 until atoms(0).toInt) {
              val variable = io.input(vi)
              val arrSize = variable.cols*variable.rows
              binding += variable -> atoms.slice(index,index+arrSize)
              index+=arrSize
            }
            Calculate(binding)
          case g:Get => g
        })
        call match {
          case Success(call) =>
            val worker = ready.next()
            workers += worker-> WorkerState.Busy
            worker.tell(call,caller)
          case Failure(exception) =>
            caller ! Status.Failure(exception)
        }
      }
    // check here if we should create an instance
    if(work.nonEmpty && workers.size<maxWorkers) {
      val initializing = workers.values.filter(_==WorkerState.New).size * creationDamping
      // no readies here
      if (initializing<1)  {
        createInstance()
      } else  log.debug(s"Damped instance creation for $id")


    }
    case Inputs =>
      sender() ! io.input
    case Outputs =>
      sender() ! io.output
    case e:Action =>
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
    case CheckRemove if workers.size>work.size && workers.size> keepMin=>
      workers.find(_._2==WorkerState.New) match {
        case Some((ref,_)) =>
          log.info(s"Termination new instance $id")
          ref ! PoisonPill
          workers -= ref
        case None =>
          workers.find(_._2==WorkerState.Ready).map(_._1) foreach { ref =>
            log.info(s"Termination ready instance $id")
            ref ! PoisonPill
            workers -= ref
          }
      }
    case state: WorkerState =>
      import WorkerState._
      state match {
        case Terminating =>
          log.info(s"Instance $id terminated")
          workers -= sender()
        case Ready =>
          if (workers(sender())==WorkerState.New) log.info(s"Instance $id ready")
          workers += sender() -> state
          self ! Dequeue
        case _ => //
      }
  }
  override def receive: Receive = workermanagement orElse {
    case m:Meta =>
      metas ::= (sender(),m)
    case e:Action =>
      work += sender() -> e
   }
}

object WorkerState extends Enumeration {
  type WorkerState = Value
  val New,Ready,Busy,Terminating = Value
}