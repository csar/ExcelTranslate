package com.sapiens.exceltranslate

import akka.util.Timeout
import com.sapiens.exceltranslate.Service.log
import com.typesafe.config.Config
import javax.jms.{Destination, Session}
import org.apache.activemq.ActiveMQConnectionFactory

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.Try

class AmqListener(activeMQ: Config)(implicit ec: ExecutionContext, timeout: Timeout) extends Listener {
  val bindURLs = Try {
    if (activeMQ.getStringList("bind").isEmpty) List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)
    else activeMQ.getStringList("bind").asScala.toList
  } getOrElse List(ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL)


  val connFactory = new ActiveMQConnectionFactory("failover://" + bindURLs.mkString(","))

  val conn = connFactory.createConnection()

  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)

  val dest = sess.createQueue(activeMQ.getString("queue"))
  val reply = sess.createProducer(null)
  val named = Try(activeMQ.getString("reply")).toOption.map(name => sess.createProducer(sess.createQueue(name)))
  val cons = sess.createConsumer(dest)

  cons setMessageListener this
  conn.start()


  override def reply(destination: Destination, correlationId: String, messageId: String, r: String): Unit = {
    val tm = sess.createTextMessage(r)
    tm.setJMSCorrelationID(correlationId)
    tm.setJMSMessageID(messageId)
    if (destination == null) {
      named match {
        case Some(reply) =>
          log.debug(s"COR-$correlationId-MSG$messageId- answering on fixed queue")
          reply.send(tm)
        case None =>
          log.error(s"COR-$correlationId-MSG$messageId- has no JMSReplyTo set and no reply queue configured")
      }

    } else reply.send(destination, tm)
  }

  def close() = conn.close

}
