/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue}
import tensil.mem.OutQueue
import tensil.PlatformConfig
import tensil.tcu.simd.ALUArray
import tensil.util.{DecoupledHelper, reportThroughput}
import tensil.util.decoupled
import tensil.util.decoupled.MultiEnqueue
import tensil.util.decoupled.Sink
import tensil.util.decoupled.QueueWithReporting
import tensil.Architecture
import tensil.InstructionLayout

class AccumulatorWithALUArray[T <: Data with Num[T]](
    val gen: T,
    val arch: Architecture,
)(implicit val platformConfig: PlatformConfig)
    extends Module {
  val height = arch.arraySize
  val depth  = arch.accumulatorDepth
  val layout = InstructionLayout(arch)

  val io = IO(new Bundle {
    val input  = Flipped(Decoupled(Vec(height, gen)))
    val output = Decoupled(Vec(height, gen))
    val control = Flipped(
      Decoupled(new AccumulatorWithALUArrayControl(layout))
    )
    val wrote          = Decoupled(Bool())
    val computed       = Decoupled(Bool())
    val nooped         = Decoupled(Bool())
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  val acc = Module(new Accumulator(gen, height, depth))
  val alu = Module(new ALUArray(gen, arch))
  val aluOutputSink = Module(
    new Sink(chiselTypeOf(alu.io.output.bits))
  )
  val aluOutput            = alu.io.output
  val aluOutputForAccInput = Wire(Decoupled(chiselTypeOf(alu.io.output.bits)))
  val input                = io.input
  val control              = io.control
  // TODO if control.bits.read is not set, but at least one of the ALU sources
  //   is 0, then we need to feed the ALU some dummy data to avoid a stall.
  //   Just feed it some hard-coded 0s (we need a new aluInputMux).
  io.wrote.bits := true.B
  io.wrote.valid := Mux(
    control.bits.instruction.op === 0.U,
    acc.io.wrote.valid,
    false.B
  )
  acc.io.wrote.ready := io.wrote.ready
  io.computed.bits := true.B
  io.computed.valid := alu.io.output.valid
  io.nooped.bits := true.B
  io.nooped.valid := control.bits.instruction.op === 0.U && !control.bits.read && !control.bits.write && control.ready

  acc.io.tracepoint := io.tracepoint
  acc.io.programCounter := io.programCounter

  val aluOutputDemux = OutQueue(
    decoupled.Demux(
      aluOutput,
      aluOutputSink.io.in,
      aluOutputForAccInput,
      name = "accalu.aluOutput"
    ),
    1,
    pipe = true,
    flow = true
  )
  val accInputMux = OutQueue(
    decoupled.Mux(
      input,
      aluOutputForAccInput,
      acc.io.input,
      name = "accalu.accInput"
    ),
    1,
    pipe = true,
    flow = true
  )
  val accOutputDemux = OutQueue(
    decoupled.Demux(
      acc.io.output,
      io.output,
      alu.io.input,
      name = "accalu.accOutput"
    ),
    1,
    pipe = true,
    flow = true
  )
  aluOutputDemux.tieOff()
  accInputMux.tieOff()
  accOutputDemux.tieOff()
  acc.io.control.tieOff()
  alu.io.instruction.tieOff()

  val readEnqueued = RegInit(false.B)
  readEnqueued := false.B

  val accWriteEnqueuer    = MultiEnqueue(2)
  val accReadEnqueuer     = MultiEnqueue(2)
  val simdRWWriteEnqueuer = MultiEnqueue(3)
  val simdRWReadEnqueuer  = MultiEnqueue(3)
  val simdWriteEnqueuer   = MultiEnqueue(4)
  val simdReadEnqueuer    = MultiEnqueue(4)
  val simdEnqueuer        = MultiEnqueue(2)
  accWriteEnqueuer.tieOff()
  accReadEnqueuer.tieOff()
  simdRWWriteEnqueuer.tieOff()
  simdRWReadEnqueuer.tieOff()
  simdWriteEnqueuer.tieOff()
  simdReadEnqueuer.tieOff()
  simdEnqueuer.tieOff()

  // when instruction is NoOp, that means send read data to output / receive
  // write data from input
  val isNoOp = control.bits.instruction.op === simd.Op.NoOp.U

  // when(control.valid) {
  when(isNoOp) {
    val dataPathReady = WireInit(false.B)
    when(control.bits.read) {
      when(control.bits.write) {
        when(readEnqueued) {
          dataPathReady := accWriteEnqueuer.enqueue(
            control.valid,
            acc.io.control,
            writeControl(),
            accInputMux,
            0.U
          )
          when(dataPathReady) {
            readEnqueued := false.B
          }.otherwise {
            readEnqueued := readEnqueued
          }
        }.otherwise {
          dataPathReady := false.B
          readEnqueued := accReadEnqueuer.enqueue(
            control.valid,
            acc.io.control,
            readControl(),
            accOutputDemux,
            0.U
          )
        }
      }.otherwise {
        dataPathReady := accReadEnqueuer.enqueue(
          control.valid,
          acc.io.control,
          readControl(),
          accOutputDemux,
          0.U
        )
      }
    }.otherwise {
      when(control.bits.write) {
        dataPathReady := accWriteEnqueuer.enqueue(
          control.valid,
          acc.io.control,
          writeControl(),
          accInputMux,
          0.U
        )
      }.otherwise {
        dataPathReady := true.B
      }
    }
    control.ready := dataPathReady
  }.otherwise {
    val dataPathReady = WireInit(false.B)
    when(control.bits.read) {
      when(control.bits.write) {
        // first read, then write
        when(readEnqueued) {
          dataPathReady := simdRWWriteEnqueuer.enqueue(
            control.valid,
            acc.io.control,
            writeControl(),
            aluOutputDemux,
            1.U,
            accInputMux,
            1.U
          )
          when(dataPathReady) {
            readEnqueued := false.B
          }.otherwise {
            readEnqueued := readEnqueued
          }
        }.otherwise {
          dataPathReady := false.B
          when(
            control.bits.instruction.sourceLeft === 0.U || control.bits.instruction.sourceRight === 0.U
          ) {
            readEnqueued := simdRWReadEnqueuer.enqueue(
              control.valid,
              acc.io.control,
              readControl(),
              accOutputDemux,
              1.U,
              alu.io.instruction,
              control.bits.instruction
            )
          }.otherwise {
            readEnqueued := true.B
          }
        }
      }.otherwise {
        dataPathReady := simdReadEnqueuer.enqueue(
          control.valid,
          acc.io.control,
          readControl(),
          accOutputDemux,
          1.U,
          aluOutputDemux,
          0.U,
          alu.io.instruction,
          control.bits.instruction
        )
      }
    }.otherwise {
      when(control.bits.write) {
        dataPathReady := simdWriteEnqueuer.enqueue(
          control.valid,
          acc.io.control,
          writeControl(),
          aluOutputDemux,
          1.U,
          accInputMux,
          1.U,
          alu.io.instruction,
          control.bits.instruction
        )
      }.otherwise {
        dataPathReady := simdEnqueuer.enqueue(
          control.valid,
          aluOutputDemux,
          0.U,
          alu.io.instruction,
          control.bits.instruction
        )
      }
    }
    control.ready := dataPathReady
  }

  def writeControl(): AccumulatorControl = {
    val w = Wire(acc.io.control.bits.cloneType.asInstanceOf[AccumulatorControl])
    w.address := control.bits.writeAddress
    w.write := true.B
    w.accumulate := control.bits.accumulate
    w
  }

  def readControl(): AccumulatorControl = {
    val w = Wire(acc.io.control.bits.cloneType.asInstanceOf[AccumulatorControl])
    w.address := control.bits.readAddress
    w.write := false.B
    w.accumulate := false.B
    w
  }
}
