import java.io.{File, FileOutputStream, FileWriter}
import java.nio.file.Paths

import com.google.common.base.VerifyException
import com.sapiens.exceltranslate.{BindingFactory, InputOutput, Variable}
import com.sapiens.exceltranslate.BindingFactory.FormulaIO
import com.typesafe.config.{Config, ConfigFactory, ConfigList}
import org.apache.poi.ss.usermodel.WorkbookFactory

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal

object CheckFiles extends App {

  val config =  ConfigFactory.load()
  var bc = Map.empty[String,String]

  val bindings = Try(ConfigFactory.parseFile(new File("binding.conf")).withFallback(ConfigFactory.parseString("sheetDefaults{}")).resolve().getConfig("sheets"))
  val writeBinding = config.getBoolean("writeBinding")
  val excelDir = new File(config.getString("excelDir"))

  for (file <- excelDir.list() if !file.startsWith("~") ; dot = file.lastIndexOf('.') if dot>0 ) {
    val formula = file take dot

    val existingBind =  bindings.map(_.getConfig(formula).getConfig("binding"))

    val excelFile = new File(excelDir,file)
    val io = Try {
       WorkbookFactory.create(excelFile)
    } recoverWith {
      case NonFatal(e) =>
        println(s"$file can not be loaded as a workbook: $e ")
        Failure(e)
    } flatMap { wb =>
      existingBind.map( InputOutput.fromConfig(wb)) recoverWith  { case _ =>
        try {
          BindingFactory.fromFormulaIO(wb.getSheet(FormulaIO)) recoverWith {
            case NonFatal(e) =>
              // println(s"No FormulaIO in $file, $e")
              BindingFactory.fromMacro(wb, excelFile.getPath)
          }
        } finally {
          wb.close()
        }
      }

    }  recoverWith {
      case ve:VerifyException => Failure(ve)
      case NonFatal(e) =>
        Console.err.println(s"No macro binding in $file - $e")
        Failure(new NoBinding(e))

    }

    io match {
      case Success(io) =>
        val config = io.toConfig(file,writeBinding , " ${sheetDefaults} ")
        bc.get(formula) match {
          case None =>
            bc += formula -> config
          case Some(cf) =>
            Console.err.println(s"Formula $formula defined more than once, prefixing formula in binding with '#!'!")
            bc += ("#!"+formula) -> config
        }
      case Failure(e) =>

        Console.err.println(e)
    }


  }


  Using(new FileWriter("binding.conf")) {writer =>
    writer write "sheets = {"

    val entries = bc map  { kv =>
         val (formula, conf) = kv
         s""" "$formula" : $conf"""
    }
    writer write entries.toList.sorted.mkString("\n","\n","\n")
    writer write "}\n"

  }


}

trait VerifyException {
  self:RuntimeException =>
}

class NotAWorkbook(e:Throwable) extends RuntimeException(e) with VerifyException
class NoBinding(e:Throwable) extends RuntimeException(e) with VerifyException