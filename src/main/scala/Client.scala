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
  val msg = sess.createTextMessage("iarr\u00001")
  msg.setJMSReplyTo(reply)
  //msg.setJMSCorrelationID("id")
  producer.send(msg)
  var start = System.nanoTime()
  var r = read.receive()
  println(r.asInstanceOf[TextMessage].getText.replace('\u0000','|'), System.nanoTime()-start)
  val calc = sess.createTextMessage(s"calc\u00001\u000016\u00000\u00003500\u000012\u00005\u00005\u000058.73\u0000128\u00001\u00002\u00000\u00000.0\u000050\u00000\u00000.04\u00000\u00001")
  calc.setJMSReplyTo(reply)
  producer.send(calc)
  r = read.receive()
  println(r.asInstanceOf[TextMessage].getText.replace('\u0000','|'), System.nanoTime()-start)

  conn.close()
  sess.close()
}
