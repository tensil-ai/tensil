/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

case class TCUOptions(
    sampleBlockSize: Int = 0,
    decoderTimeout: Int = 100,
    validateInstructions: Boolean = false,
    enableStatus: Boolean = false
) {
  def enableSample = sampleBlockSize > 0
}
