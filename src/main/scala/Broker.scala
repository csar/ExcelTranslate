import org.apache.activemq.broker.BrokerService
import org.apache.activemq.store.memory.MemoryPersistenceAdapter

object Broker extends App {
 val broker = new BrokerService
  broker.addConnector("tcp://localhost:61616")
 broker.setPersistent(false)
//  broker.setPersistenceAdapter(new MemoryPersistenceAdapter)
  broker.start

}
