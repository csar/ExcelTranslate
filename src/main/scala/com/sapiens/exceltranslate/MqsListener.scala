package com.sapiens.exceltranslate

import akka.util.Timeout
import com.ibm.msg.client.jms.JmsFactoryFactory
import com.typesafe.config.Config
import javax.jms.Destination

import scala.concurrent.ExecutionContext
import scala.util.Try

class MqsListener(mqSeries: Config)(implicit ec: ExecutionContext, timeout: Timeout, cff: JmsFactoryFactory) extends Listener {
  val cf = cff.createConnectionFactory()

  import com.ibm.msg.client.jms.JmsConstants._
  import com.ibm.msg.client.wmq.common.CommonConstants._
  // Set the properties// Set the properties

  cf.setStringProperty(WMQ_HOST_NAME, mqSeries.getString("host"))
  cf.setIntProperty(WMQ_PORT, Try(mqSeries.getInt("port")) getOrElse 1414)
  cf.setStringProperty(WMQ_CHANNEL, mqSeries.getString("channel"))
  cf.setIntProperty(WMQ_CONNECTION_MODE, WMQ_CM_CLIENT)
  cf.setStringProperty(WMQ_QUEUE_MANAGER, mqSeries.getString("manager"))
  cf.setStringProperty(WMQ_APPLICATIONNAME, Try(mqSeries.getString("appname")) getOrElse "ExcelTranslate")
  Try(mqSeries.getConfig("authentication")) foreach { auth =>
    cf.setBooleanProperty(USER_AUTHENTICATION_MQCSP, true)
    cf.setStringProperty(USERID, auth.getString("user"))
    cf.setStringProperty(PASSWORD, auth.getString("password"))

  }

  val conn = cf.createConnection()
  val sess = conn.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE)
  val sync = conn.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE)
  val queue = sess.createQueue(mqSeries.getString("queue"))
  val consumer = sess.createConsumer(queue)

  val reply = sync.createQueue(mqSeries.getString("reply"))
  val producer = sync.createProducer(reply)

  consumer.setMessageListener(this)


  conn.start()


  override def reply(replyTo: Destination, correlationId: String, messageId: String, text: String): Unit = {
    val tm = sync.createTextMessage(text)
    tm.setJMSCorrelationID(correlationId)
    tm.setJMSMessageID(messageId)
    producer.send(tm)
  }


  def close() = conn.close

}
