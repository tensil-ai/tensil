package tensil.util

import java.io.PrintWriter
import java.nio.file.Paths

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage, DesignAnnotation}
import chisel3.internal.Builder
import firrtl.{AnnotationSeq, EmittedVerilogCircuitAnnotation}
import firrtl.annotations.DeletedAnnotation

import scala.collection.mutable

object Driver {

  /**
    * Available options listed here from the sbt output of
    *   `runMain tensil.xillybus.conv2.Top --help`
    * listed here for reference and implementation of future custom options.
    *
    * >>
    * Usage: chisel3 [options] [<arg>...]
    *
    * common options
    * -tn, --top-name <top-level-circuit-name>
    * This options defines the top level circuit, defaults to dut when possible
    * -td, --target-dir <target-directory>
    * This options defines a work directory for intermediate files, default is .
    * -ll, --log-level <error|warn|info|debug|trace>
    * This options defines global log level, default is None
    * -cll, --class-log-level <FullClassName:[error|warn|info|debug|trace]>[,...]
    * This options defines class log level, default is Map()
    * -ltf, --log-to-file      default logs to stdout, this flags writes to topName.log or firrtl.log if no topName
    * -lcn, --log-class-names  shows class names and log level in logging output, useful for target --class-log-level
    * --help                   prints this usage text
    * <arg>...                 optional unbounded args
    *
    * chisel3 options
    * -chnrf, --no-run-firrtl  Stop after chisel emits chirrtl file
    * --full-stacktrace        Do not trim stack trace
    *
    * firrtl options
    * -i, --input-file <firrtl-source>
    * use this to override the default input file name , default is empty
    * -o, --output-file <output>
    * use this to override the default output file name, default is empty
    * -faf, --annotation-file <input-anno-file>
    * Used to specify annotation files (can appear multiple times)
    * -foaf, --output-annotation-file <output-anno-file>
    * use this to set the annotation output file
    * -X, --compiler <high|middle|low|verilog|mverilog|sverilog|none>
    * compiler to use, default is verilog
    * --info-mode <ignore|use|gen|append>
    * specifies the source info handling, default is append
    * -fct, --custom-transforms <package>.<class>
    * runs these custom transforms during compilation.
    * -fil, --inline <circuit>[.<module>[.<instance>]][,..],
    * Inline one or more module (comma separated, no spaces) module looks like "MyModule" or "MyModule.myinstance
    * -firw, --infer-rw        Enable readwrite port inference for the target circuit
    * -frsq, --repl-seq-mem -c:<circuit>:-i:<filename>:-o:<filename>
    * Replace sequential memories with blackboxes + configuration file
    * -clks, --list-clocks -c:<circuit>:-m:<module>:-o:<filename>
    * List which signal drives each clock of every descendent of specified module
    * -fsm, --split-modules    Emit each module to its own file in the target directory.
    * --no-check-comb-loops    Do NOT check for combinational loops (not recommended)
    * --no-dce                 Do NOT run dead code elimination
    * --no-dedup               Do NOT dedup modules
    */
  def apply[T <: RawModule](
      compiler: Compiler = Verilog,
      targetDir: String = "build",
      outputFile: String = ""
  )(dutGen: () => T): String = {
    val args = new mutable.ArrayBuffer[String]
    // args ++= Array("--compiler", compiler.name)
    args ++= Array("--target-dir", targetDir)
//  args ++= Array("--output-file", outputFile)
    val stage   = new ChiselStage()
    val modName = moduleName(dutGen)
    val rtl = compiler match {
      case Verilog             => stage.emitVerilog(dutGen(), args.toArray)
      case SystemVerilog       => stage.emitSystemVerilog(dutGen(), args.toArray)
      case High | Middle | Low => stage.emitFirrtl(dutGen(), args.toArray)
    }
    val suffix = compiler match {
      case Verilog             => "v"
      case SystemVerilog       => "sv"
      case High | Middle | Low => "fir"
    }
    val currentPath = Paths.get(System.getProperty("user.dir"))
    val out = if (outputFile.isEmpty) {
      modName + "." + suffix
    } else {
      outputFile
    }
    val filePath = Paths.get(currentPath.toString, targetDir, out)
    new PrintWriter(filePath.toString) {
      print(rtl)
      close()
    }
    rtl
  }

  def moduleName[T <: RawModule](dutGen: () => T): String = {
    val annos       = ChiselGeneratorAnnotation(dutGen).elaborate
    val circuitAnno = annos.head
    val designAnno  = annos.last
    designAnno match {
      case DesignAnnotation(dut) => dut.name
    }
  }
}

class Compiler(val name: String)

object Compiler {
  def apply(name: String): Compiler = {
    new Compiler(name)
  }
}

object SystemVerilog extends Compiler("sverilog")
object Verilog       extends Compiler("verilog")
object High          extends Compiler("high")
object Middle        extends Compiler("middle")
object Low           extends Compiler("low")
