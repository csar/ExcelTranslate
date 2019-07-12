package dyntable

import com.sapiens.exceltranslate._
import dyntable.Ps.toNumber
import org.apache.poi.hssf.usermodel.HSSFName
import org.apache.poi.ss.usermodel.{Cell, CellType, Sheet}
import org.apache.poi.ss.util.{AreaReference, CellReference}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.parsing.combinator.JavaTokenParsers

case class Dim(v:Int,relative:Boolean)
class FormulaParser(sheet:Sheet)  extends JavaTokenParsers{
  val sheetIndex = sheet.getWorkbook.getSheetIndex(sheet)
  private def excelToInt(aaa:String) :Int = aaa.toUpperCase().foldLeft(-1) { (col,letter) =>
    val add =  (letter-'A').toInt
//    if (col < 0) add
//    else if (col<26) (add+1)*26+col
//    else if (col<26*26) (add+1)*26*26+col
//    else
      (col+1)*26+add
  }
  def quotedSheetName: Parser[String] =
    rep1(("'"+"""([^'\x00-\x1F\x7F]|\\u[a-fA-F0-9]{4})+"""+"'").r) <~ '!' ^^ ( q =>   q.map(_.tail.dropRight(1)).mkString("'"))
  def sheetName:Parser[String] =  ident <~"!"

  def col:Parser[(Dim,Dim)] = opt('$') ~ regex("[A-Z]+".r) ~  opt('$') ~ wholeNumber ^^ { case cr ~ cc ~ rr ~ rn =>

    (Dim(excelToInt(cc), cr.isDefined), Dim(rn.toInt-1, rr.isDefined))
  }

  def optToPerc(o:Option[_]):Double = if (o.isDefined) 0.01 else 1.0
  val truefalse = "(?i)true|false".r
  def expr:Parser[Expression] = term|atom
  def literal = literalF|literalI|literalS|literalB
  def literalS: Parser[StringLiteral] = stringLiteral ^^ {case s => StringLiteral(s)}
  def literalF: Parser[NumericLiteral] = floatingPointNumber ~ opt("%") ^^ {case s ~ perc =>  NumericLiteral(s.toDouble*optToPerc(perc))}
  def literalI: Parser[NumericLiteral] = wholeNumber ~ opt("%")  ^^ {case s ~ perc=>  NumericLiteral(s.toDouble*optToPerc(perc))}
  def literalB: Parser[BooleanLiteral] = truefalse ^^ {case s =>  BooleanLiteral(s.toBoolean)}
  def eqop :Parser[Operator] = "=" ^^ (_ => Eq)
  def neqop :Parser[Operator] = "<>" ^^ (_ => Nq)
  def leop :Parser[Operator] = "<=" ^^ (_ => Le)
  def geop :Parser[Operator] = ">=" ^^ (_ => Ge)
  def ltop :Parser[Operator] = "<" ^^ (_ => Lt)
  def gtop :Parser[Operator] = ">" ^^ (_ => Gt)
  def ctop :Parser[Operator] = "&" ^^ (_ => Ct)
  def plop :Parser[Operator] = "+" ^^ (_ => Ps)
  def miop :Parser[Operator] = "-" ^^ (_ => Ms)
  def muop :Parser[Operator] = "*" ^^ (_ => Ts)
  def dvop :Parser[Operator] = "/" ^^ (_ => Dv)
  def pwop :Parser[Operator] = "^" ^^ (_ => Pow)
  def atom:Parser[Expression] = negated | literal | funcall |area| defined | ref | compound
  def negated : Parser[Unary] = miop~atom ^^ {case op ~ e => Unary(op,e)}
  def binaryOp:Parser[Operator] = neqop|leop|geop|eqop|ltop|gtop|ctop|plop|miop|muop|dvop|pwop
  //def binary : Parser[Binary] = expr ~ binaryOp ~ expr ^^ {case left ~ op ~ right => for( a<-left;b<-right) yield Binary(op,a,b)}
  def compound:Parser[Compound] = "("~> expr <~")" ^^ {case e =>  Compound(e)}

  def fixPrecedence(b:Binary):Binary = b match {
    case Binary(op1, left, Binary(op2, mid, right)) if op1.precendence<=op2.precendence  =>
      Binary(op2,fixPrecedence(Binary(op1,left,mid)), right)
    case _ => b
  }
  def term:Parser[Binary] = atom ~ binaryOp ~ (term|atom) ^^ {
    case e ~ op ~ t =>
          val b = fixPrecedence(Binary(op,e,t))
          if(b.toString ==Binary(Ps,Binary(Ms,Reference("Main+1",36,3,1,1),Reference("Control",34,1,1,1)),NumericLiteral(4.0)) )
            println("What was it?")
      b
  }
//  def args2:Parser[List[Expression]] =   atom ~  ("," ~> args)  ^^ {
//    case e ~ list => e :: list
//  }

  def args:Parser[List[Expression]] =   rep1sep(expr ,",")

  def funcall:Parser[FunCall] = ident ~ ("(" ~>opt(expr)<~")"^^(_.toList)| "("~>args<~")" )  ^^ {case name~args =>
    if (name=="INDIRECT")
      FunCall(name, (StringLiteral(sheet.getSheetName)::args):_*)  // add the origin to INDIRECT to resolve in case the ref is local
    else
      FunCall(name, args:_*)
  }

  def defined:Parser[Reference] = opt( sheetName|quotedSheetName) ~ ident ^^ { case sheetName ~ name =>
    import scala.jdk.CollectionConverters._
    val names = sheet.getWorkbook.getAllNames.asScala.filter(_.getNameName==name).filter(_.getRefersToFormula.nonEmpty)
    val sheetIdx = sheet.getWorkbook.getSheetIndex(sheetName.getOrElse(sheet.getSheetName))
    val global =  names.find(_.getSheetIndex == -1)
    val forSheet =  names.find(_.getSheetIndex == sheetIdx)
//    if (name == "PA92")
//      println("look")
    Try {
      val formula = forSheet.getOrElse(global.get)
      val area = new AreaReference( formula.getRefersToFormula,sheet.getWorkbook.getSpreadsheetVersion)
      val tl:CellReference =  area.getFirstCell
      val br:CellReference =  area.getLastCell
      val (s,r,c) = (tl.getSheetName(), tl.getRow, tl.getCol)
      val (h,w) = (br.getRow-r+1, br.getCol-c+1)
      Reference(s,r,c,w,h)
    } recover {
      case _ =>
        val cr = new CellReference(name)
        Reference(sheetName.getOrElse(sheet.getSheetName),cr.getRow, cr.getCol)
    } get

  }
  def area :Parser[Reference] = ref ~ ':' ~ ref ^^ {case from ~ _ ~ to =>
      from.copy(width = to.col-from.col+1, height = to.row-from.row+1)
  }
  def ref :Parser[Reference] = opt( sheetName|quotedSheetName) ~ col ^^ {
    case Some(name) ~ cr =>

        Reference(name, cr._2.v, cr._1.v)

    case _ ~ cr =>
      Reference(sheet.getSheetName, cr._2.v, cr._1.v)
  }
//  def sheetName:Parser[String]= ("'" ~ rep(ident ~ whiteSpace) ~ "'") | stringLiteral ^^ {
//    case nameWirhtSpaces => nameWirhtSpaces
//    case  simpleName => simpleName
//  }
  def apply(formula:String) : Expression = {
    println("parsing", formula)
    val result = parseAll(expr, formula).get
    if (formula contains "INDIRECT")
      println(result)
    result
  }
   def apply(input:Cell) : Expression = if (input==null) VoidExpression
    else {
      import CellType._
      input.getCellType match {
        case BLANK => VoidExpression
        case STRING => StringLiteral(input.getStringCellValue)
        case NUMERIC => NumericLiteral(input.getNumericCellValue)
        case BOOLEAN => BooleanLiteral(input.getBooleanCellValue)
        case FORMULA =>
          apply(input.getCellFormula)
      }
    }
}
trait Eval {
  def eval(arg:Expression*):Try[Literal]
}

sealed trait Operator extends Eval {
  val symbol:String
  val precendence:Int}

case object Eq extends Operator {
  val symbol="="
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a==b
      case (BooleanLiteral(a),BooleanLiteral(b)) => a==b
      case (StringLiteral(a),StringLiteral(b)) => a==b
      case _ => false
    }
    BooleanLiteral(result)
  }
}
case object Nq extends Operator {val symbol="<>"
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a==b
      case (BooleanLiteral(a),BooleanLiteral(b)) => a==b
      case (StringLiteral(a),StringLiteral(b)) => a==b
      case _ => false
    }
    BooleanLiteral(!result)
  }}
case object Lt extends Operator {val symbol="<"
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a<b
      case (BooleanLiteral(a),BooleanLiteral(b)) => !a && b
      case (StringLiteral(a),StringLiteral(b)) => a<b
      case _ => false
    }
    BooleanLiteral(result)
  }}
case object Le extends Operator {val symbol="<="
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a<=b
      case (BooleanLiteral(a),BooleanLiteral(b)) => a==b || (!a && b)
      case (StringLiteral(a),StringLiteral(b)) => a<=b
      case _ => false
    }
    BooleanLiteral(result)
  }}
case object Gt extends Operator {val symbol=">"
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a>b
      case (BooleanLiteral(a),BooleanLiteral(b)) => a && !b
      case (StringLiteral(a),StringLiteral(b)) => a>b
      case _ => false
    }
    BooleanLiteral(result)
  }}
case object Ge extends Operator {val symbol=">="
  override val precendence: Int = 100
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    val result = (left,right) match {
      case (NumericLiteral(a),NumericLiteral(b)) => a>=b
      case (BooleanLiteral(a),BooleanLiteral(b)) => a==b || (a && !b)
      case (StringLiteral(a),StringLiteral(b)) => a>=b
      case _ => false
    }
    BooleanLiteral(result)
  }}
case object Ct extends Operator {val symbol="&"
  override val precendence: Int = 90
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = arg(0).asInstanceOf[Literal]
    val right = arg(1).asInstanceOf[Literal]
    StringLiteral(left.asString + right.asString )
  }}
case object Ps extends Operator {val symbol="+"
  override val precendence: Int = 80

  def toNumber(l:Expression) = Try(l match {
    case NumericLiteral(v) => v
    case BooleanLiteral(v) => if(v) 1 else 0
    case VoidExpression => 0

  }
  )
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = toNumber(arg(0))
    val right = toNumber(arg(1))
    NumericLiteral(left.get+right.get)
  }}
case object Ms extends Operator {val symbol="-"
  override val precendence: Int = 80
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = toNumber(arg(0))
    if(arg.size==1) NumericLiteral(-left.get)
    else {
      val right = toNumber(arg(1))
      NumericLiteral(left.get - right.get)
    }
  }}
case object Ts extends Operator {val symbol="*"
  override val precendence: Int = 70
  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = toNumber(arg(0))
    val right = toNumber(arg(1))
    NumericLiteral(left.get*right.get)
  }}
case object Pow extends Operator {val symbol="^"
  override val precendence: Int = 60

  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = toNumber(arg(0))
    val right = toNumber(arg(1))
    NumericLiteral(Math.pow(left.get,right.get))
  }}
case object Dv extends Operator {val symbol="/"
  override val precendence: Int = 70

  override def eval(arg: Expression*): Try[Literal] = Try{
    val left = toNumber(arg(0))
    val right = toNumber(arg(1))
    NumericLiteral(left.get/right.get)
  }}
