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

  def call(string: String) = {
    val msg = sess.createTextMessage(string)
    msg.setJMSReplyTo(reply)
    producer.send(msg)
    var r = read.receive()
    r.asInstanceOf[TextMessage].getText

  }
  println("Before", call("get\u00001\u0000Control!$K$7"))
  println("reqann", call("get\u00001\u0000Control!$B$3"))
  println("K21", call("get\u00001\u0000Control!$K$21"))
  println("I21", call("get\u00001\u0000Control!$I$21"))
  println("J21", call("get\u00001\u0000Control!$J$21"))
  println("K22", call("get\u00001\u0000Control!$K$22"))
  println("K17", call("get\u00001\u0000Control!$K$17"))
  println("revprec", call("get\u00001\u0000Control!$C$16"))
  println("D26", call("get\u00001\u0000Control!$D$26"))
  println("H21", call("get\u00001\u0000Control!$H$21"))
  println("initexp", call("get\u00001\u0000Control!$B$28"))
  println("initfixed_exp", call("get\u00001\u0000Control!$B$30"))
  println("app", call("get\u00001\u0000Control!$B$31"))
  println("renexp", call("get\u00001\u0000Control!$B$29"))
  println("G12", call("get\u00001\u0000Control!G12"))
  println("G13", call("get\u00001\u0000Control!G13"))
  println("H12", call("get\u00001\u0000Control!H12"))
  println("H13", call("get\u00001\u0000Control!H13"))
  println("G21", call("get\u00001\u0000Control!G21"))
  println("H21", call("get\u00001\u0000Control!H21"))
  println("'jl+1'!K11", call("get\u00001\u0000'jl+1'!K11"))
  println("'jl+1'!K8", call("get\u00001\u0000'jl+1'!K8"))
  println("AN", call("get\u00001\u0000'jl+1'!K8"))
  println("aia", call("get\u00001\u0000'jl+1'!N15"))
  println("'jl+1'!B15", call("get\u00001\u0000'jl+1'!B15"))
  println("C39", call("get\u00001\u0000Control!$C$39"))
  println("K7", call("get\u00001\u0000'jl+1'!K7"))
  println("H15", call("get\u00001\u0000'jl+1'!H15"))
  println("I15", call("get\u00001\u0000'jl+1'!I15"))
  println("F15", call("get\u00001\u0000'jl+1'!F15"))
  println("E15", call("get\u00001\u0000'jl+1'!E15"))

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
  for(i<- 1 to 100) {
    val tm = sess.createTextMessage(reqCALC)
    tm.setJMSReplyTo(reply)
    producer.send(tm)
  }

  for(i<- 1 to 100) {
    read.receive()
  }
  println("10 repeats" ,(System.nanoTime() - start) / 10_000_000.0)

  conn.close()
  sess.close()
}
