package com.sapiens.exceltranslate

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.sapiens.exceltranslate.Service.log
import com.typesafe.config.Config
import javax.jms.{Destination, Session}
import org.apache.activemq.ActiveMQConnectionFactory
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.Try

class HttpListener(config: Config)(implicit ec: ExecutionContext, timeout: Timeout) extends Listener {
  val log = LoggerFactory.getLogger(getClass)

  val bind:String = Try {
    config.getString("bind")
  } getOrElse "0.0.0.0"

  val port = config.getInt("port")

  val apikeys = Try(config.getStringList("apikeys")).toOption
  import Service.system
  implicit val materializer = ActorMaterializer()
  val server = Http().bindAndHandle( (new Api(apikeys)).serve, bind, port)
   log.info(s"Bind HTTP to $bind and $port")
  override def reply(destination: Destination, correlationId: String, messageId: String, r: String): Unit = ???

  def close() = server.foreach(_.terminate(Duration(1, TimeUnit.SECONDS)))

}
