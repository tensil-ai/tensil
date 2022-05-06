/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.mem

import chisel3._
import chisel3.util.{Decoupled, Queue, log2Ceil}
import tensil.PlatformConfig

class DualPortMemWithProtection[T <: Data](
    val gen: T,
    val depth: Long,
    val strideDepth: Int,
    debug: Boolean = false,
    name: String = "mem",
)(implicit val platformConfig: PlatformConfig)
    extends Module {
  val addressType = UInt(log2Ceil(depth).W)

  val io = IO(new Bundle {
    val portA          = new PortWithStride(gen, depth, strideDepth)
    val portB          = new PortWithStride(gen, depth, strideDepth)
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  val m = Module(new DualPortMem(gen, depth, debug = debug, name = name))

  // make a queue for each input port
  // whenever we enqueue new control inputs, we check
  // this module should include the size and stride handler stuff

  val controlBufferSize = 10

  val portA = Queue(io.portA.control, controlBufferSize)
  val portB = Queue(io.portB.control, controlBufferSize)

}

class BlockTable(depth: Long, strideDepth: Int) extends Module {
  val io = IO(new Bundle {
    val add =
      Flipped(Decoupled(new MemControlWithStride(depth, strideDepth: Int)))
    val remove =
      Flipped(Decoupled(new MemControlWithStride(depth, strideDepth: Int)))
    val test =
      Flipped(Decoupled(new MemControlWithStride(depth, strideDepth: Int)))
    val ok = Decoupled(Bool())
  })

  // when add, if it's a write increment its entry in the table
  // when remove, if it's a write decrement its entryin the table (ie write completed)
  // when test, if its entry in the table is 0 return true, otherwise false
}
