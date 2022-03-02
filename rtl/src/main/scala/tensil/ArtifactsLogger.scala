/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import scala.collection._

object ArtifactsLogger {
  val artifacts = new mutable.ArrayBuffer[String]

  def log(artifactFileName: String): Unit = artifacts += artifactFileName
}
