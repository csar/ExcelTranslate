package com.sapiens.exceltranslate

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive, Directive0, Route}
import spray.json.JsArray
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.directives.HeaderDirectives
import akka.pattern.ask
import akka.util.Timeout
import javax.jms.TextMessage

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


case class TMWrapper(tokens:String*) extends TextMessage {
  def setText(str:String) :Unit = ???
  def getText() = tokens.mkString(MessageHandler.separatorString)

  override def clearProperties(): Unit = {}
  def acknowledge(): Unit = ???
  def clearBody(): Unit = ???
  def getBody[T](x$1: Class[T]): T = ???
  def getBooleanProperty(x$1: String): Boolean = ???
  def getByteProperty(x$1: String): Byte = ???
  def getDoubleProperty(x$1: String): Double = ???
  def getFloatProperty(x$1: String): Float = ???
  def getIntProperty(x$1: String): Int = ???
  def getJMSCorrelationID(): String = ???
  def getJMSCorrelationIDAsBytes(): Array[Byte] = ???
  def getJMSDeliveryMode(): Int = ???
  def getJMSDeliveryTime(): Long = ???
  def getJMSDestination(): javax.jms.Destination = ???
  def getJMSExpiration(): Long = ???
  def getJMSMessageID(): String = ???
  def getJMSPriority(): Int = ???
  def getJMSRedelivered(): Boolean = ???
  def getJMSReplyTo(): javax.jms.Destination = ???
  def getJMSTimestamp(): Long = ???
  def getJMSType(): String = ???
  def getLongProperty(x$1: String): Long = ???
  def getObjectProperty(x$1: String): Object = ???
  def getPropertyNames(): java.util.Enumeration[_] = ???
  def getShortProperty(x$1: String): Short = ???
  def getStringProperty(x$1: String): String = ???
  def isBodyAssignableTo(x$1: Class[_]): Boolean = ???
  def propertyExists(x$1: String): Boolean = ???
  def setBooleanProperty(x$1: String, x$2: Boolean): Unit = ???
  def setByteProperty(x$1: String, x$2: Byte): Unit = ???
  def setDoubleProperty(x$1: String, x$2: Double): Unit = ???
  def setFloatProperty(x$1: String, x$2: Float): Unit = ???
  def setIntProperty(x$1: String, x$2: Int): Unit = ???
  def setJMSCorrelationID(x$1: String): Unit = ???
  def setJMSCorrelationIDAsBytes(x$1: Array[Byte]): Unit = ???
  def setJMSDeliveryMode(x$1: Int): Unit = ???
  def setJMSDeliveryTime(x$1: Long): Unit = ???
  def setJMSDestination(x$1: javax.jms.Destination): Unit = ???
  def setJMSExpiration(x$1: Long): Unit = ???
  def setJMSMessageID(x$1: String): Unit = ???
  def setJMSPriority(x$1: Int): Unit = ???
  def setJMSRedelivered(x$1: Boolean): Unit = ???
  def setJMSReplyTo(x$1: javax.jms.Destination): Unit = ???
  def setJMSTimestamp(x$1: Long): Unit = ???
  def setJMSType(x$1: String): Unit = ???
  def setLongProperty(x$1: String, x$2: Long): Unit = ???
  def setObjectProperty(x$1: String, x$2: Object): Unit = ???
  def setShortProperty(x$1: String, x$2: Short): Unit = ???
  def setStringProperty(x$1: String, x$2: String): Unit = ???
}
class Api(apikeys:Option[java.util.List[String]])(implicit val timeout:Timeout, ex:ExecutionContext) {
  import Service.handler
  import JsonFormats._
  private def unMarshallBinds(str:String) = {
    val tokens = str.split(MessageHandler.separator)
    tokens.head match {
      case "OK" =>
        for ( c <-  0 until tokens(1).toInt) yield {
          val offset = 2+c*5
          Bind(tokens(offset+0).toInt,tokens(offset+1),
            DataType(tokens(offset+2).toInt),
            tokens(offset+3).toInt,tokens(offset+4).toInt)
        }
      case _ =>
        throw new RuntimeException(tokens(1))

    }

  }
  private def unMarshallResults(str:String) = {
    println(str)
    val tokens = str.split(MessageHandler.separator)
    tokens.head match {
      case "OK" =>
        var offset = 2
        for ( c <-  0 until tokens(1).toInt) yield {
          val size = tokens(offset+4).toInt*tokens(offset+3).toInt
          val dt = DataType(tokens(offset+2).toInt)
          var data =  tokens.slice(offset+5, offset+5+size)
          while (data.size<size) data = data.appended("") // handle trailing empty value
          offset += 5+size
          Value(Some(dt), if (dt == Numeric) Some(data.map(str => Try(BigDecimal(str)).toOption)) else None,
            if (dt == Bool) Some(data.map(_=="1")) else None,
            if (dt == Alpha) Some(data) else None)
        }
      case _ =>
        throw new RuntimeException(tokens(1))

    }

  }
  def withAuthorization:Directive0 =  apikeys match {
    case Some(list) if !list.isEmpty =>
      optionalHeaderValueByName("Authorization") flatMap {
        case Some(value) =>
          val ok = Try {
            val Array (kind, token) = value.split(' ')

            (kind equalsIgnoreCase "token") && (list contains token)
          } getOrElse false

          if (ok)
            pass
          else
            reject(AuthorizationFailedRejection)

        case None =>
          reject(AuthorizationFailedRejection)
      }
    case _  =>
      pass
  }

  val serve:Route = pathPrefix("api") {
    withAuthorization {
    pathPrefix(Segment) { sheet =>
      get {
        path("input") {
          val fut = handler.ask(TMWrapper("iarr", sheet)).mapTo[String].map(unMarshallBinds)

          onComplete(fut) {
            case Success(result) =>
              complete(result)
            case Failure(t) =>
              complete(StatusCodes.BadRequest, t.getMessage)
          }
        } ~
          path("output") {
            val fut = handler.ask(TMWrapper("oarr", sheet)).mapTo[String].map(unMarshallBinds)

            complete(fut)
          } ~
          path("calculate") {
            entity(as[List[Value]]) { parameters =>
              val values = parameters.flatMap(_.tokens).toList
              val fut = handler.ask(TMWrapper(("calc" :: sheet :: (parameters.size.toString) :: values): _*)).mapTo[String].map(unMarshallResults)

              complete(fut)
            }
          }
      }
    }
    }
  }
}
