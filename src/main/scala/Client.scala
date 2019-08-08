import com.sapiens.exceltranslate.MessageHandler
import com.typesafe.config.ConfigFactory
import javax.jms.{Message, MessageListener, Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory

object Client extends App {

  val config = ConfigFactory.load()
  val connFactory = new ActiveMQConnectionFactory("tcp://alis-lnx-bl.sapiens.int:61616")

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
  println("Before", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!K7"))
  println("reqann", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!B3"))
  println("K21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!K21"))
  println("I21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!I21"))
  println("J21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!J21"))
  println("K22", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!K22"))
  println("K17", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!K17"))
  println("revprec", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!C16"))
  println("D26", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!D26"))
  println("H21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!H21"))
  println("initexp", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!B28"))
  println("initfixed_exp", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!B30"))
  println("app", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!B31"))
  println("renexp", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!B29"))
  println("G12", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!G12"))
  println("G13", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!G13"))
  println("H12", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!H12"))
  println("H13", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!H13"))
  println("G21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!G21"))
  println("H21", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!H21"))
  println("'jl+1'!K11", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!K11"))
  println("'jl+1'!K8", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!K8"))
  println("AN", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!K8"))
  println("aia", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!N15"))
  println("'jl+1'!B15", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!B15"))
  println("C39", call(s"get${MessageHandler.separator}1${MessageHandler.separator}Control!C39"))
  println("K7", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!K7"))
  println("H15", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!H15"))
  println("I15", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!I15"))
  println("F15", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!F15"))
  println("E15", call(s"get${MessageHandler.separator}1${MessageHandler.separator}'jl+1'!E15"))

  val reqIARR = s"iarr${MessageHandler.separator}1"
  val msg = sess.createTextMessage(reqIARR)
  msg.setJMSReplyTo(reply)
  println("SEND:",reqIARR.replace(MessageHandler.separator,'|'))

  var start = System.nanoTime()
  producer.send(msg)

  var r = read.receive()
  println(r.asInstanceOf[TextMessage].getText.replace(MessageHandler.separator,'|'), (System.nanoTime()-start)/1_000_000.0)
  val reqCALC = s"calc${MessageHandler.separator}1${MessageHandler.separator}16${MessageHandler.separator}0${MessageHandler.separator}3500${MessageHandler.separator}12${MessageHandler.separator}5${MessageHandler.separator}5${MessageHandler.separator}58.73${MessageHandler.separator}128${MessageHandler.separator}1${MessageHandler.separator}2${MessageHandler.separator}0${MessageHandler.separator}0.0${MessageHandler.separator}50${MessageHandler.separator}0${MessageHandler.separator}0.04${MessageHandler.separator}0${MessageHandler.separator}1"
  val calc = sess.createTextMessage(reqCALC)
  calc.setJMSReplyTo(reply)
  println("SEND:",reqCALC.replace(MessageHandler.separator,'|'))
    start = System.nanoTime()
    producer.send(calc)
    r = read.receive()
    println(r.asInstanceOf[TextMessage].getText.replace(MessageHandler.separator, '|'), (System.nanoTime() - start) / 1_000_000.0)



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
