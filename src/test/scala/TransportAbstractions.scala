import javax.jms.{Destination, JMSConsumer, JMSProducer, Message, MessageConsumer, MessageProducer, TextMessage}
// Some traits to align the different transports

object Session {
  def apply(session: javax.jms.Session) = new Session {
    def createTextMessage(str: String): TextMessage = session.createTextMessage(str)

    def createTextMessage(): TextMessage = session.createTextMessage()

    def close(): Unit = session.close()
  }
  def apply(session: javax.jms.JMSContext) = new Session {
    def createTextMessage(str: String): TextMessage = session.createTextMessage(str)

    def createTextMessage(): TextMessage = session.createTextMessage()

    def close(): Unit = session.close()
  }
}
trait Session {
  def createTextMessage(str:String) :TextMessage
  def createTextMessage() :TextMessage
  def close():Unit
}
object Producer {
  def apply(p:MessageProducer) = new Producer {
    def send(destination: Destination, message: Message): Unit = p.send(destination,message)
  }
  def apply(p:JMSProducer) = new Producer {
    def send(destination: Destination, message: Message): Unit = p.send(destination, message)
  }
}
trait Producer {
  self =>
  def send(destination: Destination, message: Message):Unit
}
object Consumer {
  def apply(c:MessageConsumer) = new Consumer {
    def receive() = c.receive()
  }
  def apply(c:JMSConsumer) = new Consumer {
    def receive() = c.receive()
  }
}
trait Consumer {
  def receive() :Message
}


