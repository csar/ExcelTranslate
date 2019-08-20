import com.sapiens.exceltranslate.Listener.transport
import com.sapiens.exceltranslate.{Listener, MessageHandler, Transports}
import com.typesafe.config.ConfigFactory
import javax.jms.{Destination, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory

import scala.util.{Random, Try}

object Client extends App {
  val config = ConfigFactory.load()
  val cqConfig = config.getConfig("client")
  val (sess: Session, dest: Destination, reply: Destination, producer: Producer, consumer: Consumer,correlationID:Option[String]) = if (transport(cqConfig) == Transports.ActiveMQ) {
    val connFactory = new ActiveMQConnectionFactory(cqConfig.getStringList("bind").get(0))

    val conn = connFactory.createConnection()

    val sess = conn.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE)

    val dest = sess.createQueue(cqConfig.getString("queue"))
    val reply = sess.createTemporaryQueue()
    val consumer = sess.createConsumer(reply)
    val producer = sess.createProducer(dest)
    conn.start()
    (Session(sess), dest, reply, Producer(producer), Consumer(consumer), None)
  } else {
    val cf = Listener.mqscf.createConnectionFactory()

    import com.ibm.msg.client.jms.JmsConstants._
    import com.ibm.msg.client.wmq.common.CommonConstants._
    // Set the properties// Set the properties

    cf.setStringProperty(WMQ_HOST_NAME, cqConfig.getString("host"))
    cf.setIntProperty(WMQ_PORT, Try(cqConfig.getInt("port")) getOrElse 1414)
    cf.setStringProperty(WMQ_CHANNEL, cqConfig.getString("channel"))
    cf.setIntProperty(WMQ_CONNECTION_MODE, WMQ_CM_CLIENT)
    cf.setStringProperty(WMQ_QUEUE_MANAGER, cqConfig.getString("manager"))
    cf.setStringProperty(WMQ_APPLICATIONNAME, Try(cqConfig.getString("appname")) getOrElse "ExcelClient (TEST)")
    Try(cqConfig.getConfig("authentication")) map { auth =>
      cf.setBooleanProperty(USER_AUTHENTICATION_MQCSP, true)
      cf.setStringProperty(USERID, auth.getString("user"))
      cf.setStringProperty(PASSWORD, auth.getString("password"))

    } recover {
      case _ =>
        cf.setBooleanProperty(USER_AUTHENTICATION_MQCSP, false)
    }

    val conn = cf.createConnection()
    val sess = conn.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE)

    val reply = sess.createQueue( cqConfig.getString("reply"))
    val CORRELATIONID  = new String(Random.alphanumeric.take(12).toArray)
    val consumer = sess.createConsumer(reply,s"JMSCorrelationID='$CORRELATIONID'")


    val queue = sess.createQueue(  cqConfig.getString("queue"))
    val producer = sess.createProducer(null)
    conn.start()


    (Session(sess), queue, reply, Producer(producer), Consumer(consumer), Some(CORRELATIONID))
  }


  def call(string: String) = {
    val msg = sess.createTextMessage(string)
    msg.setJMSReplyTo(reply)
    correlationID.foreach(msg.setJMSCorrelationID)
    producer.send(dest, msg)
    var r = consumer.receive()
    r.asInstanceOf[TextMessage].getText.replace(MessageHandler.separator, '|')

  }




  val formula = "Annuity calc JL"

  import com.sapiens.exceltranslate.separator

  println("Before", call(s"get${separator}$formula${separator}Control!K7"))
  println("reqann", call(s"get${separator}$formula${separator}Control!B3"))
  println("K21", call(s"get${separator}$formula${separator}Control!K21"))
  println("I21", call(s"get${separator}$formula${separator}Control!I21"))
  println("J21", call(s"get${separator}$formula${separator}Control!J21"))
  println("K22", call(s"get${separator}$formula${separator}Control!K22"))
  println("K17", call(s"get${separator}$formula${separator}Control!K17"))
  println("revprec", call(s"get${separator}$formula${separator}Control!C16"))
  println("D26", call(s"get${separator}$formula${separator}Control!D26"))
  println("H21", call(s"get${separator}$formula${separator}Control!H21"))
  println("initexp", call(s"get${separator}$formula${separator}Control!B28"))
  println("initfixed_exp", call(s"get${separator}$formula${separator}Control!B30"))
  println("app", call(s"get${separator}$formula${separator}Control!B31"))
  println("renexp", call(s"get${separator}$formula${separator}Control!B29"))
  println("G12", call(s"get${separator}$formula${separator}Control!G12"))
  println("G13", call(s"get${separator}$formula${separator}Control!G13"))
  println("H12", call(s"get${separator}$formula${separator}Control!H12"))
  println("H13", call(s"get${separator}$formula${separator}Control!H13"))
  println("G21", call(s"get${separator}$formula${separator}Control!G21"))
  println("H21", call(s"get${separator}$formula${separator}Control!H21"))
  println("'jl+1'!K11", call(s"get${separator}$formula${separator}'jl+1'!K11"))
  println("'jl+1'!K8", call(s"get${separator}$formula${separator}'jl+1'!K8"))
  println("AN", call(s"get${separator}$formula${separator}'jl+1'!K8"))
  println("aia", call(s"get${separator}$formula${separator}'jl+1'!N15"))
  println("'jl+1'!B15", call(s"get${separator}$formula${separator}'jl+1'!B15"))
  println("C39", call(s"get${separator}$formula${separator}Control!C39"))
  println("K7", call(s"get${separator}$formula${separator}'jl+1'!K7"))
  println("H15", call(s"get${separator}$formula${separator}'jl+1'!H15"))
  println("I15", call(s"get${separator}$formula${separator}'jl+1'!I15"))
  println("F15", call(s"get${separator}$formula${separator}'jl+1'!F15"))
  println("E15", call(s"get${separator}$formula${separator}'jl+1'!E15"))

  val reqIARR = s"iarr${separator}$formula"
  val msg = sess.createTextMessage(reqIARR)
  msg.setJMSReplyTo(reply)
  correlationID.foreach(msg.setJMSCorrelationID)

  println("SEND:", reqIARR.replace(MessageHandler.separator, '|'))

  var start = System.nanoTime()
  producer.send(dest, msg)

  var r = consumer.receive()
  println(r.asInstanceOf[TextMessage].getText.replace(MessageHandler.separator, '|'), (System.nanoTime() - start) / 1_000_000.0)
  val reqCALC = s"calc${separator}$formula${separator}16${separator}0${separator}3500${separator}12${separator}5${separator}5${separator}58.73${separator}128${separator}1${separator}2${separator}0${separator}0.0${separator}50${separator}0${separator}0.04${separator}0${separator}1"
  val calc = sess.createTextMessage(reqCALC)
  calc.setJMSReplyTo(reply)
  correlationID.foreach(calc.setJMSCorrelationID)

  println("SEND:", reqCALC.replace(MessageHandler.separator, '|'))
  start = System.nanoTime()
  producer.send(dest, calc)
  r = consumer.receive()
  println(r.asInstanceOf[TextMessage].getText.replace(MessageHandler.separator, '|'), (System.nanoTime() - start) / 1_000_000.0)


  start = System.nanoTime()
  for (i <- 1 to 100) {
    val tm = sess.createTextMessage(reqCALC)
    tm.setJMSReplyTo(reply)
    correlationID.foreach(tm.setJMSCorrelationID)

    producer.send(dest, tm)

  }

  for (i <- 1 to 100) { // this reads all messages and doesn't respect the order!
    consumer.receive()
  }
  println("100 repeats", (System.nanoTime() - start) / 100_000_000.0)

  sess.close()
}


