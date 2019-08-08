package com.sapiens.exceltranslate
import java.util.concurrent.TimeUnit

import akka.pattern.{AskTimeoutException, ask}
import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.sapiens.exceltranslate.Service.sess
import com.typesafe.config.ConfigFactory
import javax.jms.{Message, MessageListener, Session}
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

import scala.util.Try

object Service extends App {
  args.headOption.foreach(System.setProperty("config.file", _))
  val system = ActorSystem("calc")
  implicit val ec = system.dispatcher
  val config = ConfigFactory.load()
  import scala.jdk.CollectionConverters._
  val bindURLs = Try{
    if(config.getStringList("activemq").isEmpty) List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)
    else config.getStringList("activemq").asScala.toList
  } getOrElse List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)
  Try(config.getBoolean("startBroker")) foreach(if(_) {
    val broker = new BrokerService
    broker.addConnector(bindURLs.head)
    broker.setPersistent(false)
    //  broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
    broker.start
  })

  val connFactory = new ActiveMQConnectionFactory("failover://"+bindURLs.mkString(",") )

  val conn = connFactory.createConnection()

  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)

  val dest = sess.createQueue(config.getString("queue"))
  val reply = sess.createProducer(Try(sess.createQueue(config.getString("reply"))) getOrElse null)
  val cons = sess.createConsumer(dest)

  conn.start()
  implicit val timeout = Timeout(1,TimeUnit.MINUTES)
  val handler = system.actorOf(Props(classOf[MessageHandler], timeout,config.getConfig("sheets")))
  cons.setMessageListener(new MessageListener {
    def onMessage(message: Message): Unit = (handler ? message).mapTo[String].recover{case _:AskTimeoutException => s"KO${separator}timeout"} foreach {r =>
      println(r.replace(separator,'|'))
      val tm = sess.createTextMessage(r)
      tm.setJMSCorrelationID(message.getJMSCorrelationID)
      if(message.getJMSReplyTo==null) reply.send(tm)
      else reply.send(message.getJMSReplyTo, tm)
    }
  })


  system.whenTerminated foreach { _ =>
    conn.close()
  }



}
