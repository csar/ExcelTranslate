package com.sapiens.exceltranslate


import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport



/**
  * Based on the code found: https://groups.google.com/forum/#!topic/spray-user/RkIwRIXzDDc
  */
class DTJsonConverter extends RootJsonFormat[DataType] {
  override def write(obj:DataType): JsValue = JsString(obj.toString)

  override def read(json: JsValue): DataType = {
    json match {
      case JsNumber(id) => id.intValue match {
        case 3 => DateType
        case 2 => Bool
        case 1 => Numeric
        case 0 =>  Alpha
        case _ =>
          throw DeserializationException(s"Expected a value from 0-3 instead of $id")
      }
      case JsString(id) => id match {
        case "date" => DateType
        case "bool" => Bool
        case "number" => Numeric
        case "string" =>  Alpha
        case _ =>
          throw DeserializationException(s"Expected a value 'date','string','bool' or 'number' instead of $id")
      }
      case somethingElse => throw DeserializationException(s"Expected a value from enum DataType instead of $somethingElse")
    }
  }
}

object JsonFormats extends SprayJsonSupport with DefaultJsonProtocol{
  implicit val _dt =  new DTJsonConverter
  implicit val _value = jsonFormat4(Value)
  implicit val _bind = jsonFormat5(Bind)
}


case class Value(`type` : Option[DataType], number:Option[Array[Option[BigDecimal]]], bool:Option[Array[Boolean]], string:Option[Array[String]]) {
  def tokens:Seq[String] = `type` match {
    case Some(dt) => dt match {
      case Alpha => string.get
      case Bool => bool.get.map(_.toString)
      case Numeric => number.get.map(_.map(_.toString).getOrElse(""))

    }
    case None =>
      if (bool.isDefined ) bool.get.map(_.toString)
      else if (number.isDefined) number.get.map(_.map(_.toString).getOrElse(""))
      else string.get
  }
}


case class Bind(ordinal:Int, name:String, `type` : DataType, rows:Int, cols:Int)