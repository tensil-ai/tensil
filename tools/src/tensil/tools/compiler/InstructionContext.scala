/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.tools.TracepointsMap

object InstructionContext {
  private def remapTracepoints(
      tracepointsMaps: Map[
        InstructionAddress,
        TracepointsMap
      ],
      currentAddress: InstructionAddress,
      newAddress: InstructionAddress
  ): Map[
    InstructionAddress,
    TracepointsMap
  ] =
    if (tracepointsMaps.isDefinedAt(currentAddress))
      tracepointsMaps.map(p =>
        if (p._1 == currentAddress) (newAddress, p._2) else p
      )
    else
      tracepointsMaps

  def injectInstructionAddress(
      context: Option[InstructionContext],
      addressToInject: InstructionAddress
  ): Option[InstructionContext] =
    Some(
      InstructionContext(
        address = Some(addressToInject),
        tracepointsMaps =
          if (context.isDefined)
            if (
              context.get.address.isDefined && context.get.tracepointsMaps.isDefined
            )
              Some(
                remapTracepoints(
                  context.get.tracepointsMaps.get,
                  context.get.address.get,
                  addressToInject
                )
              )
            else
              context.get.tracepointsMaps
          else None
      )
    )

  def injectInstructionTracepointsMaps(
      context: Option[InstructionContext],
      tracepointsMapsToInject: Map[
        InstructionAddress,
        TracepointsMap
      ]
  ): Option[InstructionContext] =
    Some(
      InstructionContext(
        address = if (context.isDefined) context.get.address else None,
        tracepointsMaps = Some(tracepointsMapsToInject)
      )
    )

}

case class InstructionContext(
    address: Option[InstructionAddress] = None,
    tracepointsMaps: Option[
      Map[InstructionAddress, TracepointsMap]
    ] = None
)
