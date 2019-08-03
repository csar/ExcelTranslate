import com.typesafe.config.ConfigFactory
import javax.jms.{Message, MessageListener, Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory

object Client extends App {

  val config = ConfigFactory.load()
  val connFactory = new ActiveMQConnectionFactory()

  val conn = connFactory.createConnection()

  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)

  val dest = sess.createQueue(config.getString("queue"))
  val reply = sess.createTemporaryQueue()
  val read = sess.createConsumer(reply)
  val producer = sess.createProducer(dest)
  conn.start()
  val reqIARR = "iarr\u00001"
  val msg = sess.createTextMessage(reqIARR)
  msg.setJMSReplyTo(reply)
  println("SEND:",reqIARR.replace('\u0000','|'))

  var start = System.nanoTime()
  producer.send(msg)

  var r = read.receive()
  println(r.asInstanceOf[TextMessage].getText.replace('\u0000','|'), (System.nanoTime()-start)/1_000_000.0)
  val reqCALC = "calc\u00001\u000016\u00000\u00003500\u000012\u00005\u00005\u000058.73\u0000128\u00001\u00002\u00000\u00000.0\u000050\u00000\u00000.04\u00000\u00001"
  val calc = sess.createTextMessage(reqCALC)
  calc.setJMSReplyTo(reply)
  println("SEND:",reqCALC.replace('\u0000','|'))
    start = System.nanoTime()
    producer.send(calc)
    r = read.receive()
    println(r.asInstanceOf[TextMessage].getText.replace('\u0000', '|'), (System.nanoTime() - start) / 1_000_000.0)



  start = System.nanoTime()
  for(i<- 1 to 10) {
    val tm = sess.createTextMessage(reqCALC)
    tm.setJMSReplyTo(reply)
    producer.send(tm)
  }

  for(i<- 1 to 10) {
    read.receive()
  }
  println("10 repeats" ,(System.nanoTime() - start) / 10_000_000.0)

  conn.close()
  sess.close()
}
