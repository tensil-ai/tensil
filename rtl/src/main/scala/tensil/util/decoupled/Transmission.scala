/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.util.decoupled

import chisel3._
import chisel3.util.{Decoupled, Queue}
import tensil.util
import tensil.util.decoupled

class Transmission(inWidth: Int, outWidth: Int) extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(UInt(inWidth.W)))
    val out   = Decoupled(UInt(outWidth.W))
    val error = Input(Bool())
  })

  if (inWidth % outWidth != 0 && outWidth % inWidth != 0) {
    val widthConverter = Module(new WidthConverter(inWidth, outWidth))
    widthConverter.io.in <> io.in
    io.out <> widthConverter.io.out
  } else {
    val dt = Module(new StrictDecoupledTransmission(inWidth, outWidth))
    dt.io.in <> io.in
    io.out <> dt.io.out
    dt.io.error := io.error
  }
}

class StrictDecoupledTransmission(inWidth: Int, outWidth: Int) extends Module {
  if (inWidth % outWidth != 0 && outWidth % inWidth != 0) {
    throw new Exception(
      "Can't handle read elements overlapping write element " +
        "boundaries and vice verse. Make sure that either readWidth is a multiple " +
        "of writeWidth or writeWidth is a multiple of readWidth."
    )
  }

  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(UInt(inWidth.W)))
    val out   = Decoupled(UInt(outWidth.W))
    val error = Input(Bool())
  })

  if (inWidth == outWidth) {
    io.out <> io.in
  } else if (inWidth > outWidth) {
    val numOutValues = inWidth / outWidth
    val ser          = Module(new Serializer(UInt(outWidth.W), numOutValues))
    ser.io.in <> decoupled.asTypeOf(io.in, chiselTypeOf(ser.io.in.bits))
    io.out <> ser.io.out
    ser.io.error := io.error
  } else if (inWidth < outWidth) {
    val numInValues = outWidth / inWidth
    val des         = Module(new Deserializer(UInt(inWidth.W), numInValues))
    des.io.in <> io.in
    io.out <> decoupled.asTypeOf(des.io.out, chiselTypeOf(io.out.bits))
    des.io.error := io.error
  }
}
