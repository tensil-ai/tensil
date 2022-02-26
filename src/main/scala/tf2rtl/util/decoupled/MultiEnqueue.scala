package tf2rtl.util.decoupled

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.DecoupledIO

class MultiEnqueue(n: Int) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new ReadyValid)
    val out = Vec(n, new ReadyValid)
  })

  val enq = for (i <- 0 until n) yield RegInit(false.B)

  val allEnqueued = io.out
    .map(_.ready)
    .zip(enq)
    .map({
      case (ready, enq) => ready || enq
    }) // verify that we have either enqueued every thing or it is now ready
    .reduce(_ && _)

  for (i <- 0 until n) {
    io.out(i).valid := io.in.valid && !enq(i)
    when(allEnqueued) {
      enq(i) := false.B
    }.otherwise {
      when(!enq(i)) {
        enq(i) := io.out(i).valid && io.out(i).ready
      }
    }
  }

  io.in.ready := allEnqueued

  def tieOff(): Unit = {
    io.in.valid := false.B
    for (port <- io.out) {
      port.ready := false.B
    }
  }

  def enqueue[T <: Data](
      out0: DecoupledIO[T],
      out0Bits: T,
  ): Bool = {
    io.in.valid := true.B
    out0 <> io.out(0).toDecoupled(out0Bits)
    io.in.ready
  }

  def enqueue[T <: Data](
      valid: Bool,
      out0: DecoupledIO[T],
      out0Bits: T,
  ): Bool = {
    io.in.valid := valid
    out0 <> io.out(0).toDecoupled(out0Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data](
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S
  ): Bool = {
    io.in.valid := true.B
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data](
      valid: Bool,
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S
  ): Bool = {
    io.in.valid := valid
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data, R <: Data](
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S,
      out2: DecoupledIO[R],
      out2Bits: R
  ): Bool = {
    io.in.valid := true.B
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    out2 <> io.out(2).toDecoupled(out2Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data, R <: Data](
      valid: Bool,
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S,
      out2: DecoupledIO[R],
      out2Bits: R
  ): Bool = {
    io.in.valid := valid
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    out2 <> io.out(2).toDecoupled(out2Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data, R <: Data, Q <: Data](
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S,
      out2: DecoupledIO[R],
      out2Bits: R,
      out3: DecoupledIO[Q],
      out3Bits: Q
  ): Bool = {
    io.in.valid := true.B
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    out2 <> io.out(2).toDecoupled(out2Bits)
    out3 <> io.out(3).toDecoupled(out3Bits)
    io.in.ready
  }

  def enqueue[T <: Data, S <: Data, R <: Data, Q <: Data](
      valid: Bool,
      out0: DecoupledIO[T],
      out0Bits: T,
      out1: DecoupledIO[S],
      out1Bits: S,
      out2: DecoupledIO[R],
      out2Bits: R,
      out3: DecoupledIO[Q],
      out3Bits: Q
  ): Bool = {
    io.in.valid := valid
    out0 <> io.out(0).toDecoupled(out0Bits)
    out1 <> io.out(1).toDecoupled(out1Bits)
    out2 <> io.out(2).toDecoupled(out2Bits)
    out3 <> io.out(3).toDecoupled(out3Bits)
    io.in.ready
  }
}

object MultiEnqueue {
  def apply(n: Int): MultiEnqueue = Module(new MultiEnqueue(n))
}
