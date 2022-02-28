package tensil.axi

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{Decoupled, log2Ceil}
import tensil.mem.MemControl
import tensil.util.decoupled.{Deserializer, Extend, Serializer, Sink}
import tensil.util.decoupled.DataCounter
import tensil.mem.RequestSplitter
import tensil.util.decoupled.Transmission
import tensil.util.decoupled.VectorSerializer
import tensil.util.decoupled.VectorDeserializer
import tensil.util.decoupled.QueueWithReporting
import tensil.util.decoupled.MultiEnqueue

/**
  * @param config
  * @param gen
  * @param vectorSize
  * @param vectorMemDepth
  * @param numInflightRequests can have up to (numInflightRequests - 1) requests in flight
  * @tparam T
  */
class Converter[T <: Data](
    config: Config,
    gen: T,
    vectorSize: Int,
    vectorMemDepth: Long,
    numInflightRequests: Int = 256,
    numScalarsPerWord: Int = 1,
) extends Module {
  val io = IO(new Bundle {
    val mem = new Bundle {
      val control = Flipped(Decoupled(new MemControl(vectorMemDepth)))
      val dataIn  = Decoupled(Vec(vectorSize, gen))
      val dataOut =
        Flipped(Decoupled(Vec(vectorSize, gen)))
    }
    val axi            = new Master(config)
    val addressOffset  = Input(UInt(config.addrWidth.W))
    val cacheBehavior  = Input(UInt(4.W))
    val timeout        = Input(Bool())
    val tracepoint     = Input(Bool())
    val programCounter = Input(UInt(32.W))
  })

  dontTouch(io.timeout)
  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  if (vectorSize % numScalarsPerWord != 0) {
    throw new Exception(
      s"axi.Converter: Vector size (= $vectorSize) must be a multiple of " +
        s"numScalarsPerWord (= $numScalarsPerWord)"
    )
  }

  // 255 is the maximum allowable value of AxLEN, so 256 is the longest possible
  // AXI transfer
  val maxTransferLength = 256 * numScalarsPerWord / vectorSize
  val control =
    RequestSplitter(vectorMemDepth, maxTransferLength)(
      QueueWithReporting(io.mem.control, 2)
    )
  val dataOut       = QueueWithReporting(io.mem.dataOut, 2)
  val readData      = QueueWithReporting(io.axi.readData, 2)
  val writeResponse = QueueWithReporting(io.axi.writeResponse, 2)

  // translate address
  //// DRAM addr = vecmem addr * vectorSize * (dataWidth / 8)
  //// e.g. dataWidth = 32b = 4B, vectorSize = 8
  //// vec addr = 1 => dram addr = 1 * 8 * 4 = 32
  //// vec addr = 2 => dram addr = 2 * 8 * 4 = 64
  //// vec addr = 23 => dram addr = 23 * 8 * 4 = 736
  val address =
    control.bits.address * ((vectorSize / numScalarsPerWord) *
      (config.dataWidth / 8)).U
  val size =
    (control.bits.size + 1.U) * (vectorSize / numScalarsPerWord).U - 1.U

  val extendedGen = gen match {
    case f: FixedPoint => FixedPoint(config.dataWidth.W, f.binaryPoint)
    case _: SInt       => SInt(config.dataWidth.W)
    case _             => UInt(config.dataWidth.W)
  }

  val ser = Module(
    new VectorSerializer(
      gen,
      UInt(config.dataWidth.W),
      vectorSize,
      numScalarsPerWord
    )
  )
  ser.io.in <> dataOut

  val serCounter = Module(
    new DataCounter(chiselTypeOf(ser.io.out.bits), 256, numInflightRequests)
  )
  serCounter.io.in <> ser.io.out
  serCounter.io.len.bits := size
  serCounter.io.len.valid := false.B
  val last   = serCounter.io.last
  val serOut = serCounter.io.out

  val des = Module(
    new VectorDeserializer(
      UInt(config.dataWidth.W),
      gen,
      vectorSize,
      numScalarsPerWord
    )
  )
  io.mem.dataIn <> des.io.out

  // wait registers
  // TODO institute a response timeout so that if a response is not received
  //      eventually the count clears and other reads/writes can proceed
  //      also raise an error flag on timeout
  // TODO make sure these counts can't go below 0
  val writeResponseCount = RegInit(0.U(log2Ceil(numInflightRequests).W))
  val readResponseCount  = RegInit(0.U(log2Ceil(numInflightRequests).W))
  val canWrite =
    (readResponseCount === 0.U) && (writeResponseCount < (numInflightRequests - 1).U)
  val canRead =
    (writeResponseCount === 0.U) && (readResponseCount < (numInflightRequests - 1).U)
  val writeRequested = io.axi.writeAddress.ready && io.axi.writeAddress.valid
  val writeResponded = writeResponse.ready && writeResponse.valid
  val readRequested  = io.axi.readAddress.ready && io.axi.readAddress.valid
  val readResponded  = readData.ready && readData.valid && readData.bits.last
  when(writeRequested) {
    when(writeResponded) {
      // total number of outstanding requests is the same
    }.otherwise {
      when(writeResponseCount < (numInflightRequests - 1).U) {
        writeResponseCount := writeResponseCount + 1.U
      }
    }
  }.otherwise {
    when(writeResponded && writeResponseCount > 0.U) {
      writeResponseCount := writeResponseCount - 1.U
    }.otherwise {
      // do nothing
    }
  }
  when(readRequested) {
    when(readResponded) {
      // total number of outstanding requests is the same
    }.otherwise {
      when(readResponseCount < (numInflightRequests - 1).U) {
        readResponseCount := readResponseCount + 1.U
      }
    }
  }.otherwise {
    when(readResponded && readResponseCount > 0.U) {
      readResponseCount := readResponseCount - 1.U
    }.otherwise {
      // do nothing
    }
  }

  // write address
  io.axi.writeAddress.bits.setDefault()
  io.axi.writeAddress.bits
    .request(
      address + io.addressOffset,
      size,
      Some(io.cacheBehavior)
    )
  io.axi.writeAddress.valid := false.B

  // write data
  val writeBits = serOut.bits
  io.axi.writeData.bits.setDefault()
  io.axi.writeData.bits.request(writeBits, last)
  io.axi.writeData.valid := false.B
  io.axi.writeData.valid := serOut.valid
  serOut.ready := io.axi.writeData.ready

  // write response
  // TODO check writeResponse.bits.okay()?
  writeResponse.ready := true.B

  // read address
  io.axi.readAddress.bits.setDefault()
  io.axi.readAddress.bits
    .request(
      address + io.addressOffset,
      size,
      Some(io.cacheBehavior)
    )
  io.axi.readAddress.valid := false.B

  // read data
  // TODO check readData.bits.okay()?
  val readBits = readData.bits.data
  des.io.in.bits := readBits
  des.io.in.valid := readData.valid
  readData.ready := des.io.in.ready

  val writeEnqueue = MultiEnqueue(2)
  writeEnqueue.tieOff()

  // control
  when(control.bits.write) {
    writeEnqueue.io.in.valid := control.valid && canWrite
    control.ready := writeEnqueue.io.in.ready

    writeEnqueue.io.out(0).ready := io.axi.writeAddress.ready
    io.axi.writeAddress.valid := writeEnqueue.io.out(0).valid
    writeEnqueue.io.out(1).ready := serCounter.io.len.ready
    serCounter.io.len.valid := writeEnqueue.io.out(1).valid
  }.otherwise {
    io.axi.readAddress.valid := control.valid && canRead
    control.ready := io.axi.readAddress.ready && canRead
  }
}
