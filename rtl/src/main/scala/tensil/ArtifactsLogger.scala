package tensil

import scala.collection._

object ArtifactsLogger {
  val artifacts = new mutable.ArrayBuffer[String]

  def log(artifactFileName: String): Unit = artifacts += artifactFileName
}
