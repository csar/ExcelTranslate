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
import scala.util.control.NonFatal
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
  var file:String = _
  var workers = Map.empty[ActorRef, WorkerState.WorkerState]
  val work = new mutable.Queue[(ActorRef,Action)]
  var metas = List.empty[(ActorRef,Action)]

  val cancellable = context.system.scheduler.schedule(Duration(1, TimeUnit.MINUTES), Duration(Try(config.getDuration("keepAlive", TimeUnit.SECONDS) ) getOrElse 100,TimeUnit.SECONDS), self, CheckRemove )
  override def postStop():Unit = {
    cancellable.cancel()
  }

  def newWorkbook = Future {
    blocking {
      log.debug(s"Loading $file as workbook")
     WorkbookFactory.create(new FileInputStream(file))
    }
  }
  def createInstance(wbf:Future[Workbook] = newWorkbook, output:Seq[Variable]=io.output):Unit = {
    log.info(s"Creating WorkbookInstance $id current #workers=${workers.size}")
    workers += context.actorOf(Props(classOf[WorkbookInstance],id,  wbf, output)) -> WorkerState.New
  }

  def init(candidates:List[String]) : Future[InputOutput] = if (candidates.isEmpty) Future.failed(new RuntimeException(s"Couldn't locate or load workbook for formula $id")) else {
    file = candidates.head
    newWorkbook map { workbook=>
      io = Try(InputOutput.fromConfig(workbook)(config.getConfig("binding"))) getOrElse  {
        BindingFactory(workbook, file,"")
      }
      createInstance(Future successful workbook, io.output)
      for( (ref, action) <- metas.reverse) {
        self.tell(action,ref)
      }
      io

    } recoverWith {
      case NonFatal(_) =>
        val msg = s"Loading of $file failed for formula $id"
        log.warning(msg)
        init(candidates.tail)
    }
  }
  override def preStart(): Unit = {
    // make the first Workbook instance to capture the IO definition
    val excelDir =  new File(Service.config.getString("excelDir"))
    val candidates = Try(config.getString("file")).map(new File(excelDir , _).getCanonicalPath).map(List(_)).getOrElse {
      log.info(s"No config $id, checking in excelDir=$excelDir")
      excelDir.listFiles().filter(_.isFile).filter { file =>
        val name = file.getName
        name.startsWith(id) && name.substring(id.length).filter(_=='.').size==1 && name.substring(id.length).startsWith(".")
      }.map { _.getPath}.toList
    }
    init(candidates) onComplete {
      case Success(io) =>
        context become ready(io).orElse(workermanagement)
        replay
      case Failure(exception) =>
        file = null
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
      } else  log.debug(s"Damped instance creation for $id current #workers=${workers.size}, #queue=${work.size}")


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
          log.info(s"Termination new instance $id current #workers=${workers.size}")
          ref ! PoisonPill
          workers -= ref
        case None =>
          workers.find(_._2==WorkerState.Ready).map(_._1) foreach { ref =>
            log.info(s"Termination ready instance $id current #workers=${workers.size}")
            ref ! PoisonPill
            workers -= ref
          }
      }
    case state: WorkerState =>
      import WorkerState._
      state match {
        case Terminating =>
          log.info(s"Instance $id terminated current #workers=${workers.size}")
          workers -= sender()
        case Ready =>
          if (workers(sender())==WorkerState.New) log.info(s"Instance $id ready current #workers=${workers.size}")
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