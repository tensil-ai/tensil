/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io.OutputStream
import tensil.tools.GraphPrinter

class FrontendGraphPrinter(stream: OutputStream, name: String)
    extends GraphPrinter(stream, name) {

  private var layerName: Option[String] = None

  def currentLayerName = layerName

  def startLayer(name: String): Unit = {
    layerName = Some(name)
  }

  def endLayer() = layerName = None
}
