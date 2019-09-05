package com.sapiens.exceltranslate



import java.net.URI
import java.nio.file.{Paths, WatchEvent, WatchKey, WatchService}
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.config.{ConfigFactory, ConfigObject}
import org.apache.activemq.broker.BrokerService
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters
import scala.util.Try

object Service extends App {
  val log = LoggerFactory.getLogger(getClass)
  args.headOption.foreach(System.setProperty("config.file", _))
  val system = ActorSystem("calc")
  implicit val ec = system.dispatcher
  val config = ConfigFactory.load()

  Try(config.getString("startBroker")).map(new URI(_)) foreach {url=>
    val broker = new BrokerService
    broker.addConnector(url)
    broker.setPersistent(false)
    broker.start
  }

  import DurationConverters._
  implicit val timeout = Timeout(config.getDuration("timeout").toScala)

  val handler = system.actorOf(Props(classOf[MessageHandler], timeout, config.getConfig("sheets")))

  Try(config.getDuration("watchInterval", TimeUnit.SECONDS)) foreach { seconds =>
    val excelPath = Paths.get(config.getString("excelDir"))
    val watch = excelPath.getFileSystem.newWatchService()
    import  java.nio.file.StandardWatchEventKinds._
    excelPath.register(watch,ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
    val delay = Duration(seconds, TimeUnit.SECONDS)
    system.scheduler.schedule(delay,delay) {
      var key:Option[WatchKey] = None
      do {
        key = Option(watch.poll())
        for( polled <- key){
          for(event <- polled.pollEvents().asScala ) {
            log.debug(s"received ${event.kind()} for ${event.context()}")
            handler ! event
          }
          polled.reset()
        }

      } while (key.isDefined)
    }

  }

  val listeners = config.getConfig("listeners")


  val connections = for (name <- listeners.entrySet().asScala.map(_.getKey).map(_.takeWhile(_ != '.')).toSet[String]) yield {
    val listenerConfig = listeners.getValue(name) match {
      case o :ConfigObject =>
        log.debug(s"Found listener $name configured by object")
        listeners.getConfig(name)
      case v =>
        val ref  = v.unwrapped()
        log.debug(s"Found listener $name configured by '$ref'")

        config.getConfig(ref.toString)
    }

    Listener(listenerConfig)
  }

  system.whenTerminated foreach { _ =>
    connections.foreach(_.close())
  }



}






