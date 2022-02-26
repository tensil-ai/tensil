
package tf2rtl

import chisel3.MultiIOModule
import chisel3.iotesters.Driver

import scala.collection.mutable

object FixedDriver {
  /**
    * Available options listed here from the scala console output of
    *   `chisel3.iotesters.Driver.execute(Array("--help"), () => new tf2rtl.util.FIFO(1, 1))(dut => new chisel3.iotesters.PeekPokeTester(dut) {})`
    * listed here for reference and implementation of future custom options.
    *
    * >>
    * Usage: chisel-testers [options] [<arg>...]
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
    * tester options
    * -tbn, --backend-name <firrtl|treadle|verilator|ivl|vcs>
    * backend to use with tester, default is treadle
    * -tigv, --is-gen-verilog  has verilog already been generated
    * -tigh, --is-gen-harness  has harness already been generated
    * -tic, --is-compiling     has harness already been generated
    * -tiv, --is-verbose       set verbose flag on PeekPokeTesters, default is false
    * -tdb, --display-base <value>
    * numeric base for displaying numbers, default is 10
    * -ttc, --test-command <value>
    * Change the command run as the backend. Quote this if it contains spaces
    * -tmvf, --more-vcs-flags <value>
    * Add specified commands to the VCS command line
    * -tmvf, --more-vcs-c-flags <value>
    * Add specified commands to the CFLAGS on the VCS command line
    * -tvce, --vcs-command-edits <value>
    * a file containing regex substitutions, one per line s/pattern/replacement/
    * -tmif, --more-ivl-flags <value>
    * Add specified commands to the ivl command line
    * -tmicf, --more-ivl-c-flags <value>
    * Add specified commands to the CFLAGS on the ivl command line
    * -tice, --ivl-command-edits <value>
    * a file containing regex substitutions, one per line s/pattern/replacement/
    * -tlfn, --log-file-name <value>
    * write log file
    * -twffn, --wave-form-file-name <value>
    * wave form file name
    * -tts, --test-seed <value>
    * provides a seed for random number generator
    * -tgvo, --generate-vcd-output <value>
    * set this flag to "on" or "off", otherwise it defaults to on for verilator, off for scala backends
    *
    *  firrtl options
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
    *
    * firrtl-interpreter-options
    * -fiwv, --fint-write-vcd  writes vcd execution log, filename will be based on top circuit name
    * -fivsuv, --fint-vcd-show-underscored-vars
    * vcd output by default does not show var that start with underscore, this overrides that
    * -fiv, --fint-verbose     makes interpreter very verbose
    * -fioe, --fint-ordered-exec
    * operates on dependencies optimally, can increase overhead, makes verbose mode easier to read
    * -fiac, --fr-allow-cycles
    * allow combinational loops to be processed, though unreliable, default is false
    * -firs, --fint-random-seed <long-value>
    * seed used for random numbers generated for tests and poison values, default is current time in ms
    * -fimed, --fint-max-execution-depth <long-value>
    * depth of stack used to evaluate expressions
    * -fisfas, --show-firrtl-at-load
    * compiled low firrtl at firrtl load time
    * -filcol, --dont-run-lower-compiler-on-load
    * run lowering compuler when firrtl file is loaded
    *
    * chisel3 options
    * -chnrf, --no-run-firrtl  Stop after chisel emits chirrtl file
    * --full-stacktrace        Do not trim stack trace
    *
    * treadle-options
    * -tiwv, --tr-write-vcd    writes vcd execution log, filename will be base on top
    * -tivsuv, --tr-vcd-show-underscored-vars
    * vcd output by default does not show var that start with underscore, this overrides that
    * -tv, --tr-verbose        makes engine very verbose
    * -tioe, --tr-ordered-exec
    * operates on dependencies optimally, can increase overhead, makes verbose mode easier to read
    * -tiac, --fr-allow-cycles
    * allow combinational loops to be processed, though unreliable, default is false
    * -tirs, --tr-random-seed <long-value>
    * seed used for random numbers generated for tests and poison values, default is current time in ms
    * -tisfas, --show-firrtl-at-load
    * compiled low firrtl at firrtl load time
    * -tilcol, --dont-run-lower-compiler-on-load
    * run lowering compiler when firrtl file is loaded
    * -tivir, --validif-random
    * validIf returns random value when condition is false
    * -ticras, --call-reset-at-start
    * has the tester automatically do a reset on it's own at startup
    * -tirb, --tr-rollback-buffers <int-value>
    * number of rollback buffers, 0 is no buffers, default is 4
    * -tici, --tr-clock-info <string>
    * clock-name[:period[:initial-offset]]
    * -tstw, --tr-symbols-to-watch symbols]
    * symbol[,symbol[...]
    * -tirn, --tr-reset-name <string>
    * name of default reset
    *
    */
  def apply[T <: MultiIOModule](dutGen: () => T, generateVCD: Boolean = false,
                                backend: Backend = Treadle, displayBase: Int = 10)
                               (testGen: T => FixedPeekPokeTester[T]): Boolean = {
    val args = new mutable.ArrayBuffer[String]
    args ++= Array("--backend-name", backend.name)
    args ++= Array("--display-base", displayBase.toString)
    if (generateVCD) {
      args ++= Array("--generate-vcd-output", "on")
    }
    // Uncomment these lines to control VCD options
    // args ++= Array("--target-dir", "test_run_dir/vcd")
    // args ++= Array("--top-name", "vcd")
    Driver.execute(args.toArray, dutGen)(testGen)
  }
}


class Backend(val name: String)

object Backend {
  def apply(name: String): Backend = {
    new Backend(name)
  }
}

object Treadle extends Backend("treadle")

object Verilator extends Backend("verilator")
