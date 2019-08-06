package com.sapiens.exceltranslate

import java.io.{File, FileInputStream}
import java.lang.NullPointerException

import dyntable.{FormulaParser, Operator}
import org.apache.poi.ss.usermodel.{Cell, CellType, Workbook, WorkbookFactory}
import org.apache.poi.ss.util.CellReference

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object Compiler extends App{
   val wb:Workbook = WorkbookFactory.create(new FileInputStream(args(0)))
   val io =  BindingFactory.fromFormulaIO(wb.getSheet(BindingFactory.FormulaIO))
    var parsers = Map.empty[String, FormulaParser]
    for(i<-0 until wb.getNumberOfSheets) {
      val sheet = wb.getSheetAt(i)
      val parser = new FormulaParser (sheet)
      parsers +=  sheet.getSheetName() -> parser
    }


  io match {
    case Failure(_) =>
      val sheet = wb.getSheetAt(0)

      val parser = parsers(sheet.getSheetName)
      val row = sheet.getRow(0)
      val a1 = row.getCell(0)
      val a2 = row.getCell(1)
      val a3 = row.getCell(2)
      println(parser(a1))
      println(parser(a2))
      println(parser(a3))
    case Success(io) =>
     var unresolved = Map.empty[Expression, Expression]
     var cells = Map.empty[Expression, Static]
      io.input.foreach { variable =>
        variable.reference.getAllReferencedCells foreach { cr =>
          val ref = Reference(cr.getSheetName, cr.getRow, cr.getCol)
          Try {
            val cell = wb.getSheet(ref.sheet).getRow(ref.row).getCell(ref.col)
            val e = variable.dataType.cellType match {
              case CellType.NUMERIC =>
                NumericVariable(cell.getNumericCellValue)
              case CellType.STRING =>
                StringVariable(cell.getStringCellValue)
              case CellType.BOOLEAN =>
                BooleanVariable(cell.getBooleanCellValue)
            }
            unresolved += ref -> e
          } recover {
            case NonFatal(_) =>
              if (wb.getSheet(ref.sheet).getRow(ref.row) == null) wb.getSheet(ref.sheet).createRow(ref.row)
              var cell = wb.getSheet(ref.sheet).getRow(ref.row).getCell(cr.getCol)
              if (cell == null) cell = wb.getSheet(ref.sheet).getRow(ref.row).createCell(cr.getCol)
              unresolved += ref -> (variable.dataType.cellType match {
                case CellType.NUMERIC =>
                  NumericVariable(0)
                case CellType.STRING =>
                  StringVariable("")
                case CellType.BOOLEAN =>
                  BooleanVariable(false)
              })
          }

        }
      }
      io.output.foreach { variable =>
        variable.reference.getAllReferencedCells foreach { cr =>
          val ref = Reference(cr.getSheetName, cr.getRow, cr.getCol)
          unresolved += ref -> ref

        }
      }


//      // bind from inputs and outputs
//      while (unresolved.nonEmpty) {
//        val (ref, cell) = unresolved.head
//        unresolved -= ref
//        val e = parsers(ref.sheet)(cell)
//        cells += ref -> e
//        unresolved ++= e.refs.flatMap(_.deps).filterNot(cells.contains).map(r => r -> r.head(wb))
//      }
//

      implicit val deref = (e:Expression) => if (e.isInstanceOf[Static])
        e
      else
        cells(e)


      def eval(): Boolean = {
        def isStatic(e: Expression) = e.isInstanceOf[Static] || cells(e).isInstanceOf[Static]

        var reduced = false
        for ((ref, e) <- unresolved) {

          if (ref==e) {
            // these are evaluateables
            e match {
              case r:Reference =>
                if (r.single) {
                  reduced = true
                  try {
                    val resolve = parsers(r.sheet)(r.head(wb))
                    unresolved += r -> resolve

                  } catch  {
                    case _:NullPointerException =>
                      cells += r -> VoidExpression // non-existent cell
                  }

                } else {
                  reduced = true
                  unresolved+= r-> r.toMatrix
                }
              case expr =>
                ExcelFunctions(expr) match {
                  case Success(literal) =>
                    reduced = true
                    unresolved-=ref
                    cells += ref -> literal
                  case Failure(Indirection(sheet,target)) =>
                    reduced = true
                    unresolved += ref -> INDIRECT.resolve(parsers(sheet.value),target)

                  case Failure(ex) =>
                    if (Try(e.deps.map(deref).forall(_.isInstanceOf[Static])).getOrElse(false)) {
                      println(ex)
                      ExcelFunctions(expr)
                    }
                    e.deps.filterNot(cells.contains).foreach { e =>
                      reduced = true

                      unresolved += e -> e
                    }


                }
            }
          }
          else cells.get(e) match { // contractions
            case Some(r) =>
              if(r!=e) {
                reduced = true
                cells += ref -> r
            }


            case None =>
              reduced = true
              unresolved += e -> e
          }
        }
        reduced
      }


      while (eval()) {
        println("expressions", cells.values.filter(!_.isInstanceOf[Static]).size)
      }
      println("expressions", cells.values.filter(!_.isInstanceOf[Static]).size)




      // bind
      for( (e,input)<-unresolved if input.isInstanceOf[Input]) {
        unresolved-=e
        cells+= e-> input.asInstanceOf[Input].asLiteral
      }
//      cells = cells.view.mapValues(_ match {
//        case i: Input => i.asLiteral
//        case e => e
//      }
//      ).toMap


      while (eval()) {
        println("expressions", cells.values.filter(!_.isInstanceOf[Static]).size)
      }

      val funcs = cells.values.collect { case f: FunCall => f.name }.toSet -- ExcelFunctions.functions.keySet
      println("Missing", funcs)
      for (variable <- io.output) {
        println(variable.name+":")
        for (r<- 0 until variable.rows) { for(c<- 0 until variable.cols ) {
          val computed = cells(Reference(variable.reference.getFirstCell.getSheetName, r+variable.reference.getFirstCell.getRow,c+variable.reference.getFirstCell.getCol ))
          print(Try(computed.asInstanceOf[Literal].asString).getOrElse("Error: "+computed))
          print('\t')
        }
        }
          println()
      }
  }






}

trait WithArgs {
  def deps:Seq[Expression]
}
trait Simple {
  self:WithArgs =>
  override def deps:Seq[Expression] = Nil
}
sealed trait Static {
  self:WithArgs =>
  override def deps:Seq[Expression] = Nil
}
sealed trait Expression extends  WithArgs {
  def refs:Seq[Reference] = Nil
  def deps:Seq[Expression]
  def isStatic = false
}

case class FunCall(name:String, args:Expression*) extends Expression {
  override def refs: Seq[Reference] = args.flatMap(_ match {
    case r:Reference => List(r)
    case e => e.refs

  })
  def deps = args
}
case class Evaluated(from:Expression) extends Expression  with Static
case class Unary(op:Operator, arg:Expression) extends Expression{
  override def refs: Seq[Reference] = arg.refs
  override def deps = List(arg)
}
case class Binary(op:Operator, left:Expression, right:Expression) extends Expression {
  override def refs: Seq[Reference] = left.refs ++ right.refs
  override def deps = List(left,right)
}
case class Compound(arg:Expression) extends Expression{
  override def refs: Seq[Reference] = arg.refs
  override def deps = List(arg)

}
case class Reference(sheet:String,row:Int,col:Int, width:Int=1,height:Int=1) extends Expression  {
  def toMatrix: Matrix = Matrix(width,height,for(r<- 0 until height) yield {
    for(c <- 0 until width) yield Reference(sheet,row+r,col+c)
  })


  def single = width==1 && height==1

  def deps =  for(c <- 0 until width; r<- 0 until height) yield  {
    Reference(sheet,row+r,col+c)
  }

  def head(wb:Workbook) =
    wb.getSheet(sheet).getRow(row).getCell(col)
}
case class Matrix(width:Int, height:Int, array:Seq[Seq[Expression]]) extends Expression {
  override def deps: Seq[Expression] = array.flatten
}
sealed trait Literal extends Expression with Simple  with Static {
  self :WithArgs =>
  def numeric: Option[Double]
  def bool:Option[Boolean]
  def asString:String
}
case object VoidExpression extends Literal {
  override def numeric: Option[Double] = None

  override def bool: Option[Boolean] = Some(false)
  override def asString: String = ""
}
case class LiteralValues (width:Int, height:Int, values:Seq[Seq[Literal]]) extends Expression with Literal {
  override def asString: String = toString
  def index(row:Int,  col:Int):Literal = values(row-1)(col-1 )
  override def numeric = ???

  override def bool: Option[Boolean] = ???
}
case class StringLiteral(value:String) extends Expression with Literal {
  override def asString: String = value

  override def numeric = None

  override def bool: Option[Boolean] = None
}
case class BooleanLiteral(value:Boolean) extends Expression with Literal{
  override def asString: String = value.toString

  override def bool: Option[Boolean] = Some(value)
  override def numeric  = Some(if(value) 1 else 0 )
}
case class NumericLiteral(value:Double) extends Expression with Literal{
  override def asString: String = value.toString
  override def bool = Some(value!=0)
  override def numeric: Option[Double] = Some(value)
}
trait Input extends Simple {
  self :WithArgs  =>
  def asLiteral :Literal
}
case class StringVariable(value:String) extends Expression with Input {
  override def asLiteral = StringLiteral(value)
}
case class BooleanVariable(value:Boolean) extends Expression with Input{
  override def asLiteral = BooleanLiteral(value)
}
case class NumericVariable(value:Double) extends Expression with Input{
  override def asLiteral = NumericLiteral(value)
}


