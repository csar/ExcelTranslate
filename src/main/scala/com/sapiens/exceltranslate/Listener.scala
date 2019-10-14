package com.sapiens.exceltranslate

import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import com.ibm.msg.client.jms.JmsFactoryFactory
import com.sapiens.exceltranslate.Service.log
import com.typesafe.config.Config
import javax.jms.{Destination, Message, MessageListener, TextMessage}

import scala.concurrent.ExecutionContext
import scala.util.Try

abstract class Listener(implicit ec: ExecutionContext, timeout: Timeout) extends AutoCloseable with MessageListener {
  def prettyPrint(msgString: String) = msgString.replace(separator, '|')

  def reply(destination: Destination, correlationId: String, messageId: String, text: String): Unit

  def onMessage(message: Message): Unit = {
    val correlationId = message.getJMSCorrelationID
    val messageId = message.getJMSMessageID
    val start = System.currentTimeMillis()
    message match {
      case tm: TextMessage => log.debug(s"COR-$correlationId-MSG$messageId- -> ${prettyPrint(tm.getText)}")
      case other =>
        log.warn(s"COR-$correlationId-MSG$messageId- Message of type ${other.getClass.getCanonicalName} received!")
    }

    import Service.handler
    (handler ? message).mapTo[String].recover { case _: AskTimeoutException => s"KO${separator}timeout" } foreach { r =>
      log.debug(s"COR-$correlationId-MSG$messageId-  <- ${prettyPrint(r)}")
      reply(message.getJMSReplyTo, correlationId, messageId, r)

      log.debug(s"COR-$correlationId-MSG$messageId- processing took ${System.currentTimeMillis() - start}ms")
    }

  }
}

object Listener {

  import Transports._
  import com.ibm.msg.client.jms.JmsConstants._

  def transport(config: Config) = if (Try(config.getValue("channel")).isSuccess) WebSphereMQ else if (Try(config.getValue("port")).isSuccess) REST else ActiveMQ

  implicit val mqscf = JmsFactoryFactory.getInstance(WMQ_PROVIDER)

  def apply(config: Config)(implicit ec: ExecutionContext, timeout: Timeout): Listener = transport(config) match {
    case WebSphereMQ => new MqsListener(config)
    case ActiveMQ => new AmqListener(config)
    case REST => new HttpListener(config)
  }


}


object Transports extends Enumeration {
  type Transport = Value
  val Unknown, ActiveMQ, WebSphereMQ, REST = Value
}