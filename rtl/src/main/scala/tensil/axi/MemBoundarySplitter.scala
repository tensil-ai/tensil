/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.axi

import chisel3._
import chisel3.util.Decoupled
import tensil.util.decoupled.MultiEnqueue
import tensil.util.decoupled.Counter

/**
  * Burst splitter splits a stream of packets into groups by raising the `last`
  * flag appropriately. The size of the each group is specified by sending
  * requests to io.control.
  *
  * @param gen must contain field `last: Bool`
  * @param maxLen a positive int (greater than 0)
  */
class BurstSplitter[T <: Bundle](gen: T, maxLen: Int) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(Decoupled(UInt(util.log2Ceil(maxLen).W)))
    val in      = Flipped(Decoupled(gen))
    val out     = Decoupled(gen)
  })

  if (!io.out.bits.elements.contains("last")) {
    throw new IllegalArgumentException(
      s"type ${io.out.bits} does not contain field matching `last: Bool`: found ${io.out.bits.elements}"
    )
  }

  for ((name, w) <- io.in.bits.elements) {
    if (io.out.bits.elements.contains(name)) {
      io.out.bits.elements(name) := w
    }
  }

  val last = io.out.bits.elements("last")

  io.out.valid := io.control.valid && io.in.valid
  io.in.ready := io.control.valid && io.out.ready

  val counter = Counter(maxLen)

  when(counter.io.value.bits === io.control.bits) {
    last := true.B
    counter.io.resetValue := io.out.fire
    io.control.ready := io.out.fire
  }.otherwise {
    last := false.B
    counter.io.value.ready := io.out.fire
    io.control.ready := false.B
  }
}

/**
  * Filter selects packets in a stream to be transmitted or dropped. The fate of
  * each packet is specified by sending requests to io.control.
  *
  * @param gen any Chisel data type
  */
class Filter[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(Decoupled(Bool())) // true = transmit, false = drop
    val in      = Flipped(Decoupled(gen))
    val out     = Decoupled(gen)
  })

  io.out.bits <> io.in.bits

  when(io.control.bits) {
    io.out.valid := io.control.valid && io.in.valid
    io.in.ready := io.control.valid && io.out.ready
    io.control.ready := io.in.valid && io.out.ready
  }.otherwise {
    io.out.valid := false.B
    io.in.ready := io.control.valid
    io.control.ready := io.in.valid
  }
}

/**
  * MemBoundarySplitter splits AXI requests up so that they do not cross memory
  * boundaries. In the AXI spec, it is required that burst requests do not cross
  * 4KB memory boundaries as this can cause the request to be sent to more than
  * one slave. This module guarantees that requirement.
  *
  * Note: We assume that the address is always aligned to the beat size. In
  *       other words, we don't support unaligned transfers.
  *
  * @param config the AXI configuration
  * @param boundary the memory boundary size, usually 4K = 4096
  * @param maxLen the maximum length of AXI burst transfers, usually 256
  */
class MemBoundarySplitter(config: Config, boundary: Int, maxLen: Int)
    extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new Master(config))
    val out = new Master(config)
  })

  val bytesPerWord = config.dataWidth / 8

  def illegal(address: UInt, length: UInt): Bool = {
    val lengthBytes = length * bytesPerWord.U
    (lengthBytes > boundary.U) || ((address % boundary.U) > ((boundary + 1).U - lengthBytes))
  }

  def min(a: UInt, b: UInt): UInt = Mux(a > b, b, a)

  val readDataQueue = Module(
    new util.Queue(UInt(util.log2Ceil(maxLen).W), 2, pipe = true, flow = true)
  )
  val writeDataQueue = Module(
    new util.Queue(UInt(util.log2Ceil(maxLen).W), 2, pipe = true, flow = true)
  )
  val writeResponseQueue = Module(
    new util.Queue(Bool(), 2, pipe = true, flow = true)
  )

  val readMerger = Module(new BurstSplitter(new ReadData(config), maxLen))
  readMerger.io.control <> readDataQueue.io.deq
  readMerger.io.in <> io.out.readData
  io.in.readData <> readMerger.io.out
  val writeSplitter = Module(new BurstSplitter(new WriteData(config), maxLen))
  writeSplitter.io.control <> writeDataQueue.io.deq
  writeSplitter.io.in <> io.in.writeData
  io.out.writeData <> writeSplitter.io.out
  val writeResponseFilter = Module(new Filter(new WriteResponse(config)))
  writeResponseFilter.io.control <> writeResponseQueue.io.deq
  writeResponseFilter.io.in <> io.out.writeResponse
  io.in.writeResponse <> writeResponseFilter.io.out
  // TODO instead of just filtering out one of the write responses, we should
  //      check the response codes and somehow merge them. E.g. if either
  //      response code is non-zero then return that code.

  val readAddressCounter = RegInit(
    0.U(util.log2Ceil(maxLen * bytesPerWord).W)
  )
  val readLenCounter = RegInit(0.U(util.log2Ceil(maxLen).W))
  val writeAddressCounter = RegInit(
    0.U(util.log2Ceil(maxLen * bytesPerWord).W)
  )
  val writeLenCounter = RegInit(0.U(util.log2Ceil(maxLen).W))

  val readEnqueuer  = MultiEnqueue(2)
  val writeEnqueuer = MultiEnqueue(3)

  // reads
  when(illegal(io.in.readAddress.bits.addr, io.in.readAddress.bits.len)) {
    val addr = io.in.readAddress.bits.addr + readAddressCounter
    val availableAddresses =
      (boundary.U - (addr % boundary.U))
    val availableBeats = availableAddresses / bytesPerWord.U
    val len = Mux(
      readLenCounter === 0.U,
      availableBeats,
      min(availableBeats, readLenCounter)
    ) - 1.U

    val address = Wire(new Address(config))
    for ((name, w) <- address.elements) {
      if (io.in.readAddress.bits.elements.contains(name)) {
        w := io.in.readAddress.bits.elements(name)
      }
    }
    address.addr := addr
    address.len := len

    when(readLenCounter === 0.U) {
      // start
      val ready = readEnqueuer.enqueue(
        io.in.readAddress.valid,
        io.out.readAddress,
        address,
        readDataQueue.io.enq,
        io.in.readAddress.bits.len,
      )
      io.in.readAddress.nodeq()
      when(io.in.readAddress.valid && ready) {
        readAddressCounter := availableAddresses
        readLenCounter := io.in.readAddress.bits.len - len
      }
    }.elsewhen(readLenCounter <= availableBeats) {
      // finish
      io.out.readAddress.valid := io.in.readAddress.valid
      io.in.readAddress.ready := io.out.readAddress.ready
      io.out.readAddress.bits <> address
      readDataQueue.io.enq.noenq()
      readEnqueuer.io.in.noenq()
      readEnqueuer.io.out(0).nodeq()
      readEnqueuer.io.out(1).nodeq()
      when(io.out.readAddress.fire) {
        readAddressCounter := 0.U
        readLenCounter := 0.U
      }
    }.otherwise {
      // continue
      io.out.readAddress.valid := io.in.readAddress.valid
      io.out.readAddress.bits <> address
      readDataQueue.io.enq.noenq()
      readEnqueuer.io.in.noenq()
      readEnqueuer.io.out(0).nodeq()
      readEnqueuer.io.out(1).nodeq()
      io.in.readAddress.nodeq()
      when(io.out.readAddress.fire) {
        // increment the read address counter by the number of addresses transferred
        readAddressCounter := readAddressCounter + availableAddresses
        // decrement the read len counter by the number of beats transferred
        readLenCounter := readLenCounter - (len + 1.U)
      }
    }
  }.otherwise {
    io.in.readAddress.ready := readEnqueuer.enqueue(
      io.in.readAddress.valid,
      io.out.readAddress,
      io.in.readAddress.bits,
      readDataQueue.io.enq,
      io.in.readAddress.bits.len
    )
  }

  // writes
  when(illegal(io.in.writeAddress.bits.addr, io.in.writeAddress.bits.len)) {
    val addr = io.in.writeAddress.bits.addr + writeAddressCounter
    val availableAddresses =
      (boundary.U - (addr % boundary.U))
    val availableBeats = availableAddresses / bytesPerWord.U
    val len = Mux(
      writeLenCounter === 0.U,
      availableBeats,
      min(availableBeats, writeLenCounter)
    ) - 1.U

    val address = Wire(new Address(config))
    for ((name, w) <- address.elements) {
      if (io.in.writeAddress.bits.elements.contains(name)) {
        w := io.in.writeAddress.bits.elements(name)
      }
    }
    address.addr := addr
    address.len := len

    when(writeLenCounter === 0.U) {
      // start
      val ready = writeEnqueuer.enqueue(
        io.in.writeAddress.valid,
        io.out.writeAddress,
        address,
        writeDataQueue.io.enq,
        address.len,
        writeResponseQueue.io.enq,
        false.B
      )
      io.in.writeAddress.nodeq()
      when(io.in.writeAddress.valid && ready) {
        writeAddressCounter := availableAddresses
        writeLenCounter := io.in.writeAddress.bits.len - len
      }
    }.elsewhen(writeLenCounter <= availableBeats) {
      // finish
      val ready = writeEnqueuer.enqueue(
        io.in.writeAddress.valid,
        io.out.writeAddress,
        address,
        writeDataQueue.io.enq,
        address.len,
        writeResponseQueue.io.enq,
        true.B
      )
      io.in.writeAddress.ready := ready
      when(io.in.writeAddress.valid && ready) {
        writeAddressCounter := 0.U
        writeLenCounter := 0.U
      }
    }.otherwise {
      // continue
      val ready = writeEnqueuer.enqueue(
        io.in.writeAddress.valid,
        io.out.writeAddress,
        address,
        writeDataQueue.io.enq,
        address.len,
        writeResponseQueue.io.enq,
        false.B
      )
      io.in.writeAddress.nodeq()
      when(io.in.writeAddress.valid && ready) {
        // increment the read address counter by the number of addresses transferred
        writeAddressCounter := writeAddressCounter + availableAddresses
        // decrement the read len counter by the number of beats transferred
        writeLenCounter := writeLenCounter - (len + 1.U)
      }
    }
  }.otherwise {
    io.in.writeAddress.ready := writeEnqueuer.enqueue(
      io.in.writeAddress.valid,
      io.out.writeAddress,
      io.in.writeAddress.bits,
      writeDataQueue.io.enq,
      io.in.writeAddress.bits.len,
      writeResponseQueue.io.enq,
      true.B
    )
  }
}
