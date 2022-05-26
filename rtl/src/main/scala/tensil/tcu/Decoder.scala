/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tcu

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, Queue, log2Ceil}
import tensil.PlatformConfig
import tensil.mem.MemControl
import tensil.InstructionLayout
import tensil.tcu.instruction._
import tensil.util.decoupled.Counter
import tensil.util.decoupled.QueueWithReporting
import tensil.util.zero
import tensil.Architecture
import tensil.mem.StrideHandler
import tensil.mem.MemControlWithStride
import tensil.mem.SizeAndStrideHandler
import tensil.mem.SizeHandler
import tensil.util.WithLast
import tensil.util.decoupled.MultiEnqueue
import tensil.mem.OutQueue
import tensil.mutex.LockPool
import tensil.mutex.ConditionalReleaseLockControl

class Decoder(val arch: Architecture, options: TCUOptions = TCUOptions())(
    implicit val platformConfig: PlatformConfig
) extends Module {
  val arrayWidth           = arch.arraySize
  val accDepth             = arch.accumulatorDepth
  val memDepth             = arch.localDepth
  val dram0Depth           = arch.dram0Depth
  val dram1Depth           = arch.dram1Depth
  val validateInstructions = options.validateInstructions
  val defaultTimeout       = options.decoderTimeout
  val numLocks             = 2
  implicit val layout      = new InstructionLayout(arch)
  implicit val _arch       = arch

  val io = IO(new Bundle {
    val instruction =
      Flipped(Decoupled(new Instruction(layout.instructionSizeBytes * 8)))
    val memPortA = Decoupled(new MemControl(layout.arch.localDepth))
    val memPortB = Decoupled(new MemControl(layout.arch.localDepth))
    val dram0    = Decoupled(new MemControl(layout.arch.dram0Depth))
    val dram1    = Decoupled(new MemControl(layout.arch.dram1Depth))
    val dataflow =
      Decoupled(new LocalDataFlowControlWithSize(arch.localDepth))
    val hostDataflow =
      Decoupled(new HostDataFlowControl)
    val acc = Decoupled(
      new AccumulatorWithALUArrayControl(layout)
    )
    val array = Decoupled(new SystolicArrayControl)
    val config = new Bundle {
      val dram0AddressOffset  = Output(UInt(platformConfig.axi.addrWidth.W))
      val dram0CacheBehaviour = Output(UInt(4.W))
      val dram1AddressOffset  = Output(UInt(platformConfig.axi.addrWidth.W))
      val dram1CacheBehaviour = Output(UInt(4.W))
    }
    val status =
      Decoupled(new WithLast(new Instruction(layout.instructionSizeBytes * 8)))
    val timeout        = Output(Bool())
    val error          = Output(Bool())
    val tracepoint     = Output(Bool())
    val programCounter = Output(UInt(32.W))
    val sample         = Decoupled(new WithLast(new Sample))
    val skipped        = Decoupled(Bool())
    val nooped         = Decoupled(Bool())
  })

  dontTouch(io.timeout)
  dontTouch(io.error)
  dontTouch(io.tracepoint)
  dontTouch(io.programCounter)

  val instruction = Queue(io.instruction, 2)

  // only enqueues when instruction is done. we're just assuming
  // status.io.enq.ready will always be true, hence the 50 element buffer
  val status = Module {
    new Queue(
      new WithLast(new Instruction(layout.instructionSizeBytes * 8)),
      1,
      flow = true,
    )
  }
  status.io.enq.valid := instruction.valid && instruction.ready
  status.io.enq.bits.bits := instruction.bits
  status.io.enq.bits.last := true.B
  io.status <> status.io.deq

  // timeout signal for debug
  val timeout = RegInit(options.decoderTimeout.U(16.W))
  val timer   = RegInit(0.U(16.W))
  when(instruction.ready) {
    timer := 0.U
  }.otherwise {
    when(timer < timeout) {
      timer := timer + 1.U
    }
  }
  io.timeout := timer === timeout

  // tracepoint
  val tracepoint     = RegInit("hFFFFFFFF".U(32.W))
  val programCounter = RegInit(0.U(32.W))
  when(instruction.ready && instruction.valid) {
    programCounter := programCounter + 1.U
  }
  io.tracepoint := programCounter === tracepoint
  io.programCounter := programCounter

  // sampler
  val sampleInterval = RegInit(0.U(16.W))

  io.nooped.bits := true.B
  io.nooped.valid := false.B
  io.skipped.bits := true.B
  io.skipped.valid := false.B

  // configuration registers
  val registerWidth       = 4
  val dram0AddressOffset  = RegInit(0.U(platformConfig.axi.addrWidth.W))
  val dram0CacheBehaviour = RegInit(0.U(4.W))
  val dram1AddressOffset  = RegInit(0.U(platformConfig.axi.addrWidth.W))
  val dram1CacheBehaviour = RegInit(0.U(4.W))

  io.config.dram0AddressOffset := dram0AddressOffset
  io.config.dram0CacheBehaviour := dram0CacheBehaviour
  io.config.dram1AddressOffset := dram1AddressOffset
  io.config.dram1CacheBehaviour := dram1CacheBehaviour

  // size and stride handlers
  //// drams
  val dram0Gen = new MemControlWithStride(arch.dram0Depth, arch.stride1Depth)
  val dram1Gen = new MemControlWithStride(arch.dram1Depth, arch.stride1Depth)
  val dram0Handler = Module(
    new StrideHandler(
      dram0Gen,
      io.dram0.bits,
      arch.dram0Depth,
      arch.stride1Depth,
      name = "dram0"
    )
  )
  val dram1Handler = Module(
    new StrideHandler(
      dram1Gen,
      io.dram1.bits,
      arch.dram1Depth,
      arch.stride1Depth,
      name = "dram1"
    )
  )
  io.dram0 <> dram0Handler.io.out
  io.dram1 <> dram1Handler.io.out
  val dram0 = OutQueue(dram0Handler.io.in, 1, pipe = true, flow = true)
  val dram1 = OutQueue(dram1Handler.io.in, 1, pipe = true, flow = true)
  //// local
  val memPortGen = new MemControlWithStride(arch.localDepth, arch.stride0Depth)
  // val memPortBGen = new MemControlWithStride(arch.localDepth, arch.stride0Depth)
  val memPortAHandler = Module(
    new SizeAndStrideHandler(
      memPortGen,
      io.memPortA.bits,
      arch.localDepth,
      arch.stride0Depth,
      name = "memPortA"
    )
  )
  val memPortBHandler = Module(
    new SizeAndStrideHandler(
      memPortGen,
      io.memPortB.bits,
      arch.localDepth,
      arch.stride0Depth,
      name = "memPortB"
    )
  )
  io.memPortA <> memPortAHandler.io.out
  io.memPortB <> memPortBHandler.io.out
  ////// dual port lock pool
  def select(r: MemControlWithStride): UInt = {
    val blockSize = memDepth / numLocks
    r.address / blockSize.U
  }
  val lockPool = Module(new LockPool(memPortGen, 2, numLocks, select))
  val idA      = 0.U
  val idB      = 1.U
  memPortAHandler.io.in <> lockPool.io.actor(idA).out
  memPortBHandler.io.in <> lockPool.io.actor(idB).out
  val memPortA = lockPool.io.actor(0).in
  val memPortB = lockPool.io.actor(1).in
  val lock     = lockPool.io.lock
  lockPool.io.deadlocked.nodeq()
  lockPool.io.locked.nodeq()
  //// accumulator
  val accInGen  = new AccumulatorMemControlWithSizeWithStride(layout)
  val accOutGen = new AccumulatorMemControl(layout)
  val accHandler = Module(
    new SizeAndStrideHandler(
      accInGen,
      accOutGen,
      arch.accumulatorDepth,
      arch.stride1Depth,
      debug = false,
      name = "acc"
    )
  )
  io.acc.bits := accHandler.io.out.bits.toAccumulatorWithALUArrayControl()
  io.acc.valid := accHandler.io.out.valid
  accHandler.io.out.ready := io.acc.ready
  val acc =
    OutQueue(
      accHandler.io.in,
      layout.arch.arraySize * 2,
      pipe = true,
      flow = true
    )
  //// array
  val arrayInGen  = new SystolicArrayControlWithSize(arch.localDepth)
  val arrayOutGen = new SystolicArrayControl
  val arrayHandler = Module(
    new SizeHandler(
      arrayInGen,
      arrayOutGen,
      arch.localDepth,
      debug = false,
      name = "array"
    )
  )
  io.array <> arrayHandler.io.out
  val array = OutQueue(
    arrayHandler.io.in,
    layout.arch.arraySize * 2,
    pipe = true,
    flow = true
  )
  //// router
  val dataflow =
    OutQueue(io.dataflow, layout.arch.arraySize * 2, pipe = true, flow = true)
  //// host router
  val hostDataflowHandler = Module(
    new SizeHandler(
      new HostDataFlowControlWithSize(arch.localDepth),
      new HostDataFlowControl,
      arch.localDepth
    )
  )
  io.hostDataflow <> hostDataflowHandler.io.out
  val hostDataflow =
    OutQueue(hostDataflowHandler.io.in, 1, pipe = true, flow = true)

  setDefault(memPortA)
  setDefault(memPortB)
  setDefault(dataflow)
  setDefault(hostDataflow)
  setDefault(acc)
  setDefault(array)
  setDefault(dram0)
  setDefault(dram1)
  lock.noenq()

  val enqueuer1 = MultiEnqueue(1)
  val enqueuer2 = MultiEnqueue(2)
  val enqueuer3 = MultiEnqueue(3)
  val enqueuer4 = MultiEnqueue(4)
  val enqueuer5 = MultiEnqueue(5)
  enqueuer1.tieOff()
  enqueuer2.tieOff()
  enqueuer3.tieOff()
  enqueuer4.tieOff()
  enqueuer5.tieOff()

  when(instruction.bits.opcode === Opcode.MatMul) {
    val flags = Wire(new MatMulFlags)
    val args = Wire(
      new MatMulArgs(layout)
    )

    flags := instruction.bits.flags.asTypeOf(flags)
    args := instruction.bits.arguments.asTypeOf(args)

    when(flags.zeroes) {
      instruction.ready := enqueuer3.enqueue(
        instruction.valid,
        dataflow,
        dataflowBundle(LocalDataFlowControl._arrayToAcc, args.size),
        array,
        arrayBundle(false.B, flags.zeroes, args.size),
        acc,
        accWrite(
          args.accAddress,
          flags.accumulate,
          args.size,
          args.accStride
        ),
      )
    }.otherwise {
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        false.B,
      )
      instruction.ready := enqueuer5.enqueue(
        instruction.valid,
        dataflow,
        dataflowBundle(LocalDataFlowControl._memoryToArrayToAcc, args.size),
        memPortA,
        req,
        array,
        arrayBundle(false.B, flags.zeroes, args.size),
        acc,
        accWrite(
          args.accAddress,
          flags.accumulate,
          args.size,
          args.accStride
        ),
        lock,
        lockControl(idA, req),
      )
    }
  }.elsewhen(instruction.bits.opcode === Opcode.LoadWeights) {
    val flags = Wire(new LoadWeightFlags)
    val args =
      Wire(
        new LoadWeightArgs(layout)
      )

    flags := instruction.bits.flags.asTypeOf(flags)
    args := instruction.bits.arguments.asTypeOf(args)

    when(flags.zeroes) {
      instruction.ready := enqueuer1.enqueue(
        instruction.valid,
        array,
        arrayBundle(true.B, flags.zeroes, args.size),
      )
    }.otherwise {
      val stride = 1.U << args.stride
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.address + (args.size * stride),
        args.size,
        args.stride,
        true.B,
        false.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        dataflow,
        LocalDataFlowControlWithSize(memDepth)(
          LocalDataFlowControl.memoryToArrayWeight,
          args.size
        ),
        array,
        arrayBundle(true.B, flags.zeroes, args.size),
        memPortA,
        req,
        lock,
        lockControl(idA, req),
      )
    }
  }.elsewhen(instruction.bits.opcode === Opcode.DataMove) {
    val args =
      Wire(
        new DataMoveArgs(layout)
      )
    val flags = Wire(new DataMoveFlags)

    args := instruction.bits.arguments.asTypeOf(args)
    flags := instruction.bits.flags.asTypeOf(flags)

    when(flags.kind === DataMoveKind.dram0ToMemory) {
      // data in
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        true.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        hostDataflow,
        HostDataFlowControlWithSize(arch.localDepth)(
          HostDataFlowControl.In0,
          args.size
        ),
        memPortB,
        req,
        dram0,
        MemControlWithStride(arch.dram0Depth, arch.stride1Depth)(
          args.accAddress,
          args.size,
          args.accStride,
          false.B,
          false.B,
        ),
        lock,
        lockControl(idB, req),
      )
    }.elsewhen(flags.kind === DataMoveKind.memoryToDram0) {
      // data out
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        false.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        hostDataflow,
        HostDataFlowControlWithSize(arch.localDepth)(
          HostDataFlowControl.Out0,
          args.size
        ),
        memPortB,
        req,
        dram0,
        MemControlWithStride(arch.dram0Depth, arch.stride1Depth)(
          args.accAddress,
          args.size,
          args.accStride,
          false.B,
          true.B,
        ),
        lock,
        lockControl(idB, req),
      )
    }.elsewhen(flags.kind === DataMoveKind.dram1ToMemory) {
      // weights in
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        true.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        hostDataflow,
        HostDataFlowControlWithSize(arch.localDepth)(
          HostDataFlowControl.In1,
          args.size
        ),
        memPortB,
        req,
        dram1,
        MemControlWithStride(arch.dram1Depth, arch.stride1Depth)(
          args.accAddress,
          args.size,
          args.accStride,
          false.B,
          false.B,
        ),
        lock,
        lockControl(idB, req),
      )
    }.elsewhen(flags.kind === DataMoveKind.memoryToDram1) {
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        false.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        hostDataflow,
        HostDataFlowControlWithSize(arch.localDepth)(
          HostDataFlowControl.Out1,
          args.size
        ),
        memPortB,
        req,
        dram1,
        MemControlWithStride(arch.dram1Depth, arch.stride1Depth)(
          args.accAddress,
          args.size,
          args.accStride,
          false.B,
          true.B,
        ),
        lock,
        lockControl(idB, req),
      )
    }.elsewhen(
      flags.kind === DataMoveKind.accumulatorToMemory
    ) {
      // data move acc=>mem
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        true.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        dataflow,
        LocalDataFlowControlWithSize(memDepth)(
          LocalDataFlowControl.accumulatorToMemory,
          args.size
        ),
        memPortA,
        req,
        acc,
        accRead(args.accAddress, args.size, args.accStride),
        lock,
        lockControl(idA, req),
      )
    }.elsewhen(
      flags.kind === DataMoveKind.memoryToAccumulator
    ) {
      // data move mem=>acc
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        false.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        dataflow,
        LocalDataFlowControlWithSize(memDepth)(
          LocalDataFlowControl.memoryToAccumulator,
          args.size
        ),
        memPortA,
        req,
        acc,
        accWrite(args.accAddress, false.B, args.size, args.accStride),
        lock,
        lockControl(idA, req),
      )
    }.elsewhen(
      flags.kind === DataMoveKind.memoryToAccumulatorAccumulate
    ) {
      // data move mem=>acc(acc)
      val req = MemControlWithStride(memDepth, arch.stride0Depth)(
        args.memAddress,
        args.size,
        args.memStride,
        false.B,
        false.B,
      )
      instruction.ready := enqueuer4.enqueue(
        instruction.valid,
        dataflow,
        LocalDataFlowControlWithSize(memDepth)(
          LocalDataFlowControl.memoryToAccumulator,
          args.size
        ),
        memPortA,
        req,
        acc,
        accWrite(args.accAddress, true.B, args.size, args.accStride),
        lock,
        lockControl(idA, req)
      )
    }.otherwise {
      // all invalid dataflow control kinds
      instruction.ready := true.B
    }
  }.elsewhen(instruction.bits.opcode === Opcode.SIMD) {
    val flags = Wire(new SIMDFlags)
    val args =
      Wire(
        new SIMDArgs(layout)
      )
    flags := instruction.bits.flags.asTypeOf(flags)
    args := instruction.bits.arguments.asTypeOf(args)

    instruction.ready := enqueuer1.enqueue(
      instruction.valid,
      acc,
      accBundle(
        args.instruction,
        args.accReadAddress,
        args.accWriteAddress,
        flags.read,
        flags.write,
        flags.accumulate,
        0.U,
        0.U
      ),
    )
  }.elsewhen(instruction.bits.opcode === Opcode.Configure) {
    when(instruction.valid) {
      val args =
        Wire(new ConfigureArgs(registerWidth, platformConfig.axi.addrWidth))

      args := instruction.bits.arguments.asTypeOf(args)

      when(args.register === Configure.dram0AddressOffset) {
        dram0AddressOffset := (args.value << 16)
      }.elsewhen(args.register === Configure.dram0CacheBehaviour) {
        dram0CacheBehaviour := args.value
      }.elsewhen(args.register === Configure.dram1AddressOffset) {
        dram1AddressOffset := (args.value << 16)
      }.elsewhen(args.register === Configure.dram1CacheBehaviour) {
        dram1CacheBehaviour := args.value
      }.elsewhen(args.register === Configure.timeout) {
        timeout := args.value
      }.elsewhen(args.register === Configure.tracepoint) {
        tracepoint := args.value
      }.elsewhen(args.register === Configure.programCounter) {
        programCounter := args.value
      }.elsewhen(args.register === Configure.sampleInterval) {
        sampleInterval := args.value
      }
    }

    instruction.ready := true.B
  }.elsewhen(instruction.bits.opcode === Opcode.NoOp) {
    instruction.ready := true.B
    io.nooped.valid := true.B
  }.otherwise { // all invalid opcodes
    instruction.ready := true.B
    io.skipped.valid := true.B
  }

  if (options.validateInstructions) {
    val validator = Module(new Validator(layout))
    validator.io.instruction.bits := instruction.bits
    validator.io.instruction.valid := instruction.valid
    io.error := validator.io.error
  } else {
    io.error := false.B
  }

  if (options.enableSample) {
    val sampler = Module(new Sampler(options.sampleBlockSize))
    sampler.io.sampleInterval := sampleInterval
    sampler.io.programCounter := programCounter
    sampler.io.flags.instruction.connect(instruction)
    sampler.io.flags.memPortA.connect(io.memPortA)
    sampler.io.flags.memPortB.connect(io.memPortB)
    sampler.io.flags.dram0.connect(io.dram0)
    sampler.io.flags.dram1.connect(io.dram1)
    sampler.io.flags.dataflow.connect(io.dataflow)
    sampler.io.flags.acc.connect(io.acc)
    sampler.io.flags.array.connect(io.array)
    io.sample <> sampler.io.sample
  } else {
    io.sample.bits.bits := zero(new Sample)
    io.sample.bits.last := false.B
    io.sample.valid := false.B
  }

  def setDefault[T <: Data](port: DecoupledIO[T]): Unit = {
    port.bits := zero(port.bits)
    port.valid := false.B
  }

  def accWrite(
      address: UInt,
      accumulate: Bool,
      size: UInt,
      stride: UInt
  ): AccumulatorMemControlWithSizeWithStride =
    accBundle(
      simd.Instruction.noOp(),
      address,
      0.U,
      false.B,
      true.B,
      accumulate,
      size,
      stride
    )

  def accRead(
      address: UInt,
      size: UInt,
      stride: UInt
  ): AccumulatorMemControlWithSizeWithStride =
    accBundle(
      simd.Instruction.noOp(),
      address,
      0.U,
      true.B,
      false.B,
      false.B,
      size,
      stride
    )

  def accBundle(
      instruction: simd.Instruction,
      address: UInt,
      altAddress: UInt,
      read: Bool,
      write: Bool,
      accumulate: Bool,
      size: UInt,
      stride: UInt
  ): AccumulatorMemControlWithSizeWithStride = {
    val w = Wire(accInGen)
    w.instruction := instruction
    w.address := address
    w.altAddress := altAddress
    w.accumulate := accumulate
    w.write := write
    w.read := read
    w.size := size
    w.stride := stride
    w.reverse := false.B
    w
  }

  def dataflowBundle(
      kind: UInt,
      size: UInt
  ): LocalDataFlowControlWithSize = {
    val w = Wire(chiselTypeOf(dataflow.bits))
    w.kind := kind
    w.size := size
    w
  }

  def arrayBundle(
      load: Bool,
      zeroes: Bool,
      size: UInt
  ): SystolicArrayControlWithSize = {
    val w = Wire(arrayInGen)
    w.load := load
    w.zeroes := zeroes
    w.size := size
    w
  }

  def lockControl(
      by: UInt,
      req: MemControlWithStride,
  ): ConditionalReleaseLockControl[MemControlWithStride] = {
    val w = Wire(
      new ConditionalReleaseLockControl(memPortGen, 2, numLocks, 1 << 4)
    )
    w.lock := select(req)
    w.acquire := true.B
    w.by := by
    w.delayRelease := 0.U
    w.cond <> req
    w
  }
}
