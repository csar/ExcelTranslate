package com.sapiens.exceltranslate

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.GregorianCalendar

import dyntable.{Eq, Eval, FormulaParser, Ge}
import org.apache.poi.ss.formula.functions.Days360
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellReference

import scala.math.BigDecimal.RoundingMode
import scala.util.{Success, Try}

object ExcelFunctions {
  val functions = Map(
    "IF" -> IF
    ,"CHOOSE" -> CHOOSE
    , "SUM" -> SUM
    , "NOT" -> NOT
    ,"MIN"->MIN
    ,"MAX"->MAX
    ,"MOD"->MOD
    ,"INT" -> INT
    ,"CONCATENATE" -> CONCATENATE
    ,"RIGHT" -> RIGHT
    ,"ROUND" -> ROUND
    ,"ROUNDDOWN" -> ROUNDDOWN
    ,"ROUNDUP" -> ROUNDUP
    ,"OR" -> OR
    ,"AND" -> AND
    , "COUNTIF" -> COUNTIF
    ,"DATE" -> DATE
    ,"DAYS360" -> DAYS360
    ,"YEAR" -> YEAR
    ,"MONTH" -> MONTH
    , "DAY" -> DAY
    ,"FLOOR" -> FLOOR
    ,"CEILING" -> CEILING
    , "TRUNC" -> TRUNC
    , "VLOOKUP" -> VLOOKUP
    , "INDEX" -> INDEX
    , "INDIRECT" -> INDIRECT
    // VLOOKUP, FLOOR, TRUNC, INDEX
  )

  def apply(e:Expression) (implicit deref: Expression=>Expression) : Try[Literal] = Try(e match {
    case Unary(op,arg) => op.eval(deref(arg)).get
    case Binary(op,a,b) => op.eval(deref(a),deref(b)).get // todo precedence rules, yo!
    case f:FunCall => functions(f.name).eval(f.args.map(deref):_*).get
    case Compound(e) => deref(e).asInstanceOf[Literal]
    case Matrix(rows,cols, values) => LiteralValues(rows,cols,values.map(_.map(v =>deref(v).asInstanceOf[Literal])))
  })



}

case object INDEX extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val array =  arg(0).asInstanceOf[LiteralValues]
    val elem = if (arg.size<3 ) {
      val rc = arg(1).asInstanceOf[NumericLiteral].value.toInt
      if (array.width==1) if(rc<1||rc>array.height) VoidExpression else array.index(rc,1)
      if (array.height==1) if(rc<1||rc>array.width) VoidExpression else array.index(1,rc)
      else VoidExpression
    } else {
      val r = arg(1).asInstanceOf[NumericLiteral].value.toInt
      val c = arg(2).asInstanceOf[NumericLiteral].value.toInt
      if(r<1 || r> array.height || c<1 || c>array.width) VoidExpression else array.index(r,c)
    }

    elem
  }
}
case class Indirection(origin:StringLiteral, ref:Literal) extends RuntimeException
case object INDIRECT extends Eval {
  def resolve(parser:FormulaParser,  target: Literal): Reference = {
        target match {
          case StringLiteral(a1text) =>

            val r =  parser(a1text)
            r.asInstanceOf[Reference]
        }
  }

  def eval(arg: Expression*): Try[Literal] = Try {
    arg(1) match {
      case ref: StringLiteral =>
        throw Indirection(arg(0).asInstanceOf[StringLiteral], ref)
      case _: Literal => VoidExpression
    }
  }
}

case object VLOOKUP extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try {
    arg(1) match {
      case array: LiteralValues =>
        val col = arg(2).asInstanceOf[NumericLiteral].value.toInt
        if (col < 1 || col > array.width)
          NumericLiteral(Double.NaN)
        else {
          val search = arg(0).asInstanceOf[Literal]
          val approximate = if (arg.size < 4) true else arg(3).asInstanceOf[BooleanLiteral].value
          if (approximate) {
            var ok: Literal = VoidExpression
            for (row <- 1 to array.height) {

              if (Ge.eval(search, array.index(row, 1)).get == BooleanLiteral(true)) ok = array.index(row, col)
            }
            ok
          } else {
            for (row <- 1 to array.height) {
              if (Eq.eval(search, array.index(row, 1)).get == BooleanLiteral(true))
                return Try(array.index(row, col))
            }
            VoidExpression
          }
        }

      case _: Literal => VoidExpression
    }
  }
}

case object YEAR extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val days =  arg(0).asInstanceOf[NumericLiteral].value.toLong
    val now = DATE.epoch plusDays days
    NumericLiteral(now.getYear)
  }
}

case object MONTH extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val days =  arg(0).asInstanceOf[NumericLiteral].value.toLong
    val now = DATE.epoch plusDays days
    NumericLiteral(now.getMonthValue)
  }
}

case object DAY extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val days =  arg(0).asInstanceOf[NumericLiteral].value.toLong
    val now = DATE.epoch plusDays days
    NumericLiteral(now.getDayOfMonth)
  }
}

case object CONCATENATE extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.map(_.asString))
      case l:Literal => Some(l.asString)
    })
    StringLiteral(numbers.mkString(""))
  }
}

case object RIGHT extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val text =  arg(0).asInstanceOf[Literal].asString
    val count = arg(1).asInstanceOf[NumericLiteral].value.toInt
    StringLiteral(text takeRight count)
  }
}

case object AND extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.flatMap(_.numeric)).map(_.asInstanceOf[BooleanLiteral].value)
      case BooleanLiteral(v) => Some(v)
      case NumericLiteral(n) => Some(n!=0)
    })
    BooleanLiteral(numbers.forall(identity))
  }
}
case object OR extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.flatMap(_.numeric)).map(_.asInstanceOf[BooleanLiteral].value)
      case BooleanLiteral(v) => Some(v)
      case NumericLiteral(n) => Some(n!=0)
    })
    BooleanLiteral(numbers.exists(identity))
  }
}
case object COUNTIF extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val data = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatten
      case l:Literal => Some(l)
    })
    val (key::toSearch) =  data.reverse
    NumericLiteral(toSearch.filter(v => Eq.eval(key,v).get.asInstanceOf[BooleanLiteral].value).size)
  }
}

case object SUM extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.flatMap(_.numeric))
      case StringLiteral(v) => Try(v.toDouble).toOption
      case l:Literal => l.numeric
    })
    NumericLiteral(numbers.sum)
  }
}

case object ROUND extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try {
    val original = arg(0).asInstanceOf[NumericLiteral]
    if (original.value.isNaN || original.value.isInfinite) original
    else {
      val num = BigDecimal(original.value)
      val places = arg(1).asInstanceOf[NumericLiteral].value.toInt
      NumericLiteral(num.setScale(places, RoundingMode.HALF_UP).doubleValue)
    }
  }
}
case object ROUNDUP extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val original  = arg(0).asInstanceOf[NumericLiteral]
    if (original.value.isNaN || original.value.isInfinite) original
    else {
      val num = BigDecimal(original.value)
      val places = arg(1).asInstanceOf[NumericLiteral].value.toInt
      NumericLiteral(num.setScale(places, RoundingMode.UP).doubleValue)
    }
  }
}
case object ROUNDDOWN extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try {
    val original = arg(0).asInstanceOf[NumericLiteral]
    if (original.value.isNaN || original.value.isInfinite) original
    else {
      val num = BigDecimal(original.value)
      val places = arg(1).asInstanceOf[NumericLiteral].value.toInt
      NumericLiteral(num.setScale(places, RoundingMode.DOWN).doubleValue)
    }
  }
}
case object CEILING extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try {
    val original = arg(0).asInstanceOf[NumericLiteral]
    if (original.value.isNaN || original.value.isInfinite) original
    else {
      val num = original.value
      val factor = arg(1).asInstanceOf[NumericLiteral].value
      // should hande different signs
      NumericLiteral(Math.ceil(num / factor) * factor)
    }
  }
}
case object FLOOR extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try {
    val original = arg(0).asInstanceOf[NumericLiteral]
    if (original.value.isNaN || original.value.isInfinite) original
    else {
      val num = original.value
      val factor = arg(1).asInstanceOf[NumericLiteral].value
      // should hande different signs
      NumericLiteral(Math.floor(num / factor) * factor)
    }
  }
}

case object MOD extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[NumericLiteral].value.toLong
    val right = arg(1).asInstanceOf[NumericLiteral].value.toLong
    NumericLiteral(left % right)
  }
}
case object DATE extends Eval {
  val epoch = LocalDate.of(1900,1,1)
  def eval(arg: Expression*): Try[Literal] = Try{
    var year = arg(0).asInstanceOf[NumericLiteral].value.toInt
    val month = arg(1).asInstanceOf[NumericLiteral].value.toInt -1
    val day = arg(2).asInstanceOf[NumericLiteral].value.toInt -1
    if (year<1900) year+=1900
    val ld =  LocalDate.of(year,1,1) plusMonths month plusDays day
    NumericLiteral(    ChronoUnit.DAYS.between(epoch,ld))
  }
}
case object DAYS360 extends Eval {

  def eval(arg: Expression*): Try[Literal] = Try{
    var first = arg(0).asInstanceOf[NumericLiteral].value
    val last = arg(1).asInstanceOf[NumericLiteral].value
    val method = if(arg.size<=2) false else arg(2).asInstanceOf[BooleanLiteral].value

    NumericLiteral(    last-first ) //TODO this is not correct
  }
}

case object MIN extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.flatMap(_.numeric))
      case StringLiteral(str) => Try(str.toDouble).toOption
      case l:Literal => l.numeric
    })

    NumericLiteral(if(numbers.nonEmpty) numbers.min else 0)
  }
}
case object MAX extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val numbers = arg.flatMap(_ match {
      case LiteralValues(_,_,values) => values.flatMap(_.flatMap(_.numeric))
      case StringLiteral(str) => Try(str.toDouble).toOption
      case l:Literal => l.numeric
    })

    NumericLiteral(if(numbers.nonEmpty) numbers.max else 0)
  }
}

case object NOT extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val n = arg(0).asInstanceOf[BooleanLiteral].value
    BooleanLiteral(!n)
  }
}
case object INT extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val n = arg(0).asInstanceOf[NumericLiteral].value
    NumericLiteral(n.floor)
  }
}
case object TRUNC extends Eval {
  def eval(arg: Expression*): Try[Literal] = if (arg.size==1) ROUNDDOWN.eval(arg(0),NumericLiteral(0)) else ROUNDDOWN.eval(arg:_*)
}
case object CHOOSE extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val n = arg(0).asInstanceOf[NumericLiteral].value.toInt
    arg(n).asInstanceOf[Literal]
  }
}

case object IF extends Eval {
  def eval(arg: Expression*): Try[Literal] = Try{
    val cond = arg(0).asInstanceOf[Literal].bool.get
    if (cond) arg(1).asInstanceOf[Literal]
    else if (arg.size<3) BooleanLiteral(false)
    else arg(2).asInstanceOf[Literal]
  }
}


