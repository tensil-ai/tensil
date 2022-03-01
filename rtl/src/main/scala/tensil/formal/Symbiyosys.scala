package tensil.formal

import java.nio.file.Paths
import java.io.PrintWriter

object Symbiyosys {
  val configTemplate = (moduleName: String) => s"""[tasks]
cover
bmc
prove

[options]
cover:
mode cover
depth 20
--
bmc:
mode bmc
depth 20
--
prove:
mode prove
depth 20
--
[engines]
smtbmc

[script]
read -formal ResetCounter.sv
read -formal bram_2x2.v
read -formal bram_4x2.v
read -formal bram_4x8.v
read -formal bram_dp_2x2.v
read -formal ${moduleName}.sv
prep -top ${moduleName}

[files]
${moduleName}.sv
ResetCounter.sv
bram_2x2.v
bram_4x2.v
bram_4x8.v
bram_dp_2x2.v
"""

  def emitConfig(moduleName: String): Unit = {
    val currentPath = Paths.get(System.getProperty("user.dir"))
    val filePath    = Paths.get(currentPath.toString, "build", moduleName + ".sby")
    new PrintWriter(filePath.toString) {
      print(configTemplate(moduleName))
      close()
    }
    println(s"Generated Symbiyosys config $moduleName.sby.")
  }
}
