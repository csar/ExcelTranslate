package com.sapiens.exceltranslate
import java.util.concurrent.TimeUnit

import akka.pattern.{AskTimeoutException, ask}
import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.sapiens.exceltranslate.Service.sess
import com.typesafe.config.ConfigFactory
import javax.jms.{Message, MessageListener, Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.jdk.DurationConverters
import scala.util.Try

object Service extends App {
  val log = LoggerFactory.getLogger(getClass)
  args.headOption.foreach(System.setProperty("config.file", _))
  val system = ActorSystem("calc")
  implicit val ec = system.dispatcher
  val config = ConfigFactory.load()
  val  activeMQ = config.getConfig("activeMQ")
  import scala.jdk.CollectionConverters._
  val bindURLs = Try{
    if(activeMQ.getStringList("activemq").isEmpty) List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)
    else activeMQ.getStringList("activemq").asScala.toList
  } getOrElse List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)
  Try(activeMQ.getBoolean("startBroker")) foreach(if(_) {
    val broker = new BrokerService
    broker.addConnector(bindURLs.head)
    broker.setPersistent(false)
    //  broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
    broker.start
  })

  val connFactory = new ActiveMQConnectionFactory("failover://"+bindURLs.mkString(",") )

  val conn = connFactory.createConnection()

  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)

  val dest = sess.createQueue(activeMQ.getString("queue"))
  val reply = sess.createProducer( null)
  val named = Try(activeMQ.getString("reply")).toOption.map(name => sess.createProducer(sess.createQueue(name)))
  val cons = sess.createConsumer(dest)
  def prettyPrint(msgString:String) = msgString.replace(separator,'|')
  conn.start()
  import DurationConverters._
  implicit val timeout = Timeout(config.getDuration("timeout").toScala)
  val handler = system.actorOf(Props(classOf[MessageHandler], timeout,config.getConfig("sheets")))
  cons.setMessageListener(new MessageListener {
    def onMessage(message: Message): Unit = {
      val correlationId = message.getJMSCorrelationID
      val messageId = message.getJMSMessageID
      val start =  System.currentTimeMillis()
      message match {
        case tm: TextMessage => log.debug(s"COR-$correlationId-MSG$messageId- -> ${ prettyPrint(tm.getText)}")
        case other =>
          log.warn(s"COR-$correlationId-MSG$messageId- Message of type ${other.getClass.getCanonicalName} received!")
      }


      (handler ? message).mapTo[String].recover { case _: AskTimeoutException => s"KO${separator}timeout" } foreach { r =>
        log.debug(s"COR-$correlationId-MSG$messageId-  <- ${ prettyPrint(r)}")
        val tm = sess.createTextMessage(r)
        tm.setJMSCorrelationID(correlationId)
        tm.setJMSMessageID(messageId)
        if (message.getJMSReplyTo == null) {
          named match {
            case Some(reply) =>
              log.debug(s"COR-$correlationId-MSG$messageId- answering on fixed queue")
              reply.send(tm)
            case None =>
              log.error(s"COR-$correlationId-MSG$messageId- has no JMSReplyTo set and no reply queue configured")
          }

        } else reply.send(message.getJMSReplyTo, tm)

        log.debug(s"COR-$correlationId-MSG$messageId- processing took ${System.currentTimeMillis()-start}ms")
      }

    }
  })


  system.whenTerminated foreach { _ =>
    conn.close()
  }



}
