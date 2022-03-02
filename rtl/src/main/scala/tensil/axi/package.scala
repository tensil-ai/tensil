/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}
import tensil.util.decoupled.Transmission
import tensil.util.WithLast

import scala.language.implicitConversions

package object axi {
  implicit def AXI4StreamToAXI4StreamDriver(
      x: AXI4Stream
  ): AXI4StreamDriver =
    new AXI4StreamDriver(x)

  def connectDownstreamInterface(
      in: AXI4Stream,
      out: DecoupledIO[Data],
      error: Bool
  ): Unit = {
    val inWidth  = in.tdata.getWidth
    val outWidth = out.bits.getWidth

    val transmission = Module(new Transmission(inWidth, outWidth))

    transmission.io.in <> in.toDecoupled
    out.valid := transmission.io.out.valid
    transmission.io.out.ready := out.ready
    out.bits := transmission.io.out.bits.asTypeOf(out.bits)
    transmission.io.error := error
  }

  def connectUpstreamInterface[T <: Data](
      in: DecoupledIO[WithLast[T]],
      out: AXI4Stream,
      error: Bool
  ): Unit = {
    val inWidth  = in.bits.getWidth
    val outWidth = out.tdata.getWidth

    if (inWidth <= outWidth) {
      val w = Wire(Decoupled(UInt(inWidth.W)))
      w.bits := in.bits.bits.asUInt()
      w.valid := in.valid
      in.ready := w.ready
      out.fromDecoupled(w, in.bits.last)
    } else {
      val transmission = Module(new Transmission(inWidth, outWidth))

      out.fromDecoupled(transmission.io.out, in.bits.last)
      transmission.io.in.valid := in.valid
      transmission.io.in.bits := in.bits.bits.asUInt()
      in.ready := transmission.io.in.ready
      transmission.io.error := error
    }
  }
}
