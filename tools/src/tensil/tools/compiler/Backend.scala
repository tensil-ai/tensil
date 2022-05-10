/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.tools.{TraceContext, TracepointCondition, TracepointsMap}
import tensil.{ArchitectureDataType, InstructionLayout}

class BackendSegment(
    val key: BackendSegmentKey,
    layout: InstructionLayout,
    stats: Option[Stats],
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) extends LIR {
  val file = File.createTempFile("segment_", ".tprog")

  private val fileStream = new FileOutputStream(file)

  private val lirTracepointCollector = new LIRTracepointCollector(
    tracepointConditions,
    resolveRefToObject
  )

  private val lirBroadcast = new LIRBroadcast(
    Seq(
      new LIRGen(layout, fileStream),
      lirTracepointCollector
    ) ++ (if (stats.isDefined) Seq(new LIREstimator(layout, stats.get))
          else
            Nil)
  )

  def instructionsCount = lirTracepointCollector.instructionsCount
  def instructionTracepointsMaps =
    lirTracepointCollector.instructionTracepointsMaps

  def close(): Unit = fileStream.close()

  def emitNoOp(): Unit = lirBroadcast.emitNoOp()

  def emitWait(tidToWait: Int): Unit = lirBroadcast.emitWait(tidToWait)

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitMatMul(
      accumulate,
      localStride,
      localAddress,
      accumulatorStride,
      accumulatorAddress,
      size
    )

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit =
    lirBroadcast.emitSIMD(
      accumulate,
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination,
      writeAccumulatorAddress,
      readAccumulatorAddress
    )

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitDataMove(
      toLocal,
      accumulate,
      localStride,
      localAddress,
      stride,
      address,
      size
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = lirBroadcast.emitLoadWeights(localStride, localAddress, size)
}

object BackendSegmentKey {
  val Init    = 0
  val Load    = 1
  val Compute = 2
  val Save    = 3

  def apply(
      layer: Int,
      stage: Int,
      partition: Int,
      kind: Int
  ): BackendSegmentKey = (layer, stage, partition, kind)
}

class Backend(
    layout: InstructionLayout,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None,
    traceContext: TraceContext = TraceContext.empty,
) {
  private val segments =
    mutable.SortedMap.empty[BackendSegmentKey, BackendSegment]

  def mkSegment(
      key: BackendSegmentKey,
      stats: Option[Stats] = None
  ): BackendSegment =
    new BackendSegment(
      key = key,
      layout = layout,
      stats = stats,
      tracepointConditions = tracepointConditions,
      resolveRefToObject = resolveRefToObject
    )

  def finalizeSegment(segment: BackendSegment): Unit = {
    segment.close()
    segments(segment.key) = segment
  }

  def writeSegments(
      programStream: OutputStream,
      printProgramStream: Option[DataOutputStream] = None,
      stats: Option[Stats] = None
  ): Unit = {
    var instructionOffset: Long = 0
    val lir = new LIRBroadcast(
      Seq(new LIRGen(layout, programStream)) ++ (if (
                                                   printProgramStream.isDefined
                                                 )
                                                   Seq(
                                                     new LIRPrinter(
                                                       printProgramStream.get
                                                     )
                                                   )
                                                 else
                                                   Nil) ++ (if (stats.isDefined)
                                                              Seq(
                                                                new LIREstimator(
                                                                  layout,
                                                                  stats.get
                                                                )
                                                              )
                                                            else Nil)
    )

    def decodeSegment(segment: BackendSegment, lir: LIR): Unit = {
      val inputStream = new FileInputStream(segment.file)
      val lirDecoder  = new LIRDecoder(layout.arch)

      lirDecoder.decode(inputStream, lir)

      inputStream.close()
    }

    // TILED EXPERIMENT BEGINS

    val tiledProgramStream = new DataOutputStream(
      new FileOutputStream("tiled.tasm")
    )
    val tiledLir = new LIRPrinter(tiledProgramStream)
    var nextTid  = 0

    case class Tile(
        init: Option[BackendSegment] = None,
        load: Option[BackendSegment] = None,
        compute: Option[BackendSegment] = None,
        save: Option[BackendSegment] = None,
    ) {
      val tid = nextTid

      nextTid = (nextTid + 1) % 2
    }

    var prev0Tile = Tile()
    var prev1Tile = Tile()

    var curInit: Option[BackendSegment] = None

    val tiles =
      for (
        tileSegments <-
          segments
            .groupBy(p => (p._1.layer, p._1.stage, p._1.partition))
            .toSeq
            .sortBy(_._1)
            .map(_._2)
      ) yield {
        val kindSegments = tileSegments.map {
          case (key, segment) => (key.kind, segment)
        }

        val init = if (tileSegments.head._1.partition == 0) {
          curInit = kindSegments.get(BackendSegmentKey.Init)
          curInit
        } else if (tileSegments.head._1.partition == 1) {
          curInit
        } else None

        Tile(
          init = init,
          load = kindSegments.get(BackendSegmentKey.Load),
          compute = kindSegments.get(BackendSegmentKey.Compute),
          save = kindSegments.get(BackendSegmentKey.Save)
        )
      }

    def overlayTiles(curTile: Tile, prev0Tile: Tile, prev1Tile: Tile) = {
      val lirDecoder   = new LIRDecoder(layout.arch)
      val streamsByTid = mutable.ArrayBuffer.empty[(Int, InputStream)]

      if (prev1Tile.save.isDefined) {
        tiledProgramStream.writeBytes(
          s"; TID ${prev1Tile.tid}: ${BackendSegmentKeyHelper(prev1Tile.save.get.key)}\r\n"
        )
        streamsByTid += (
          (
            prev1Tile.tid,
            new FileInputStream(prev1Tile.save.get.file)
          )
        )
      }
      if (curTile.init.isDefined) {
        tiledProgramStream.writeBytes(
          s"; TID ${curTile.tid}: ${BackendSegmentKeyHelper(curTile.init.get.key)}\r\n"
        )
        streamsByTid += (
          (
            curTile.tid,
            new FileInputStream(curTile.init.get.file)
          )
        )
      }
      if (curTile.load.isDefined) {
        tiledProgramStream.writeBytes(
          s"; TID ${curTile.tid}: ${BackendSegmentKeyHelper(curTile.load.get.key)}\r\n"
        )
        streamsByTid += (
          (
            curTile.tid,
            new FileInputStream(curTile.load.get.file)
          )
        )
      }
      if (prev0Tile.compute.isDefined) {
        tiledProgramStream.writeBytes(
          s"; TID ${prev0Tile.tid}: ${BackendSegmentKeyHelper(prev0Tile.compute.get.key)}\r\n"
        )
        streamsByTid += (
          (
            prev0Tile.tid,
            new FileInputStream(prev0Tile.compute.get.file)
          )
        )
      }

      val decodersByTid = mutable.Map(
        streamsByTid
          .groupBy(_._1)
          .mapValues(
            _.map(v => lirDecoder.mkStepDecoder(v._2)).toList
          )
          .toSeq: _*
      )

      val mixers = decodersByTid.keys
        .map(decodersTid =>
          new LIR {
            // TODO: Mixer will set desired TID and adjust local address

            val tid       = decodersTid
            val estimator = new Estimator(layout)
            var curCycles = 0L

            override def emitNoOp(): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles
              tiledLir.emitNoOp()
            }

            override def emitWait(tidToWait: Int): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles
              tiledLir.emitWait(tidToWait)
            }

            override def emitMatMul(
                accumulate: Boolean,
                localStride: Int,
                localAddress: MemoryAddress,
                accumulatorStride: Int,
                accumulatorAddress: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(Opcode.MatMul, Some(size))
                .cycles
              tiledLir.emitMatMul(
                accumulate,
                localStride,
                localAddress,
                accumulatorStride,
                accumulatorAddress,
                size
              )
            }

            override def emitSIMD(
                accumulate: Boolean,
                simdOp: Int,
                simdSourceLeft: Int,
                simdSourceRight: Int,
                simdDestination: Int,
                writeAccumulatorAddress: MemoryAddress,
                readAccumulatorAddress: MemoryAddress
            ): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.SIMD).cycles
              tiledLir.emitSIMD(
                accumulate,
                simdOp,
                simdSourceLeft,
                simdSourceRight,
                simdDestination,
                writeAccumulatorAddress,
                readAccumulatorAddress
              )
            }

            override def emitDataMove(
                toLocal: Boolean,
                accumulate: Boolean,
                localStride: Int,
                localAddress: MemoryAddress,
                stride: Int,
                address: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(
                  Opcode.DataMove,
                  Some(size),
                  LIRGen.mkDataMoveFlags(toLocal, accumulate, address.tag)
                )
                .cycles
              tiledLir.emitDataMove(
                toLocal,
                accumulate,
                localStride,
                localAddress,
                stride,
                address,
                size
              )
            }

            override def emitLoadWeights(
                localStride: Int,
                localAddress: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size))
                .cycles
              tiledLir.emitLoadWeights(localStride, localAddress, size)
            }

          }
        )
        .toSeq

      /* Find mixer with remaning decoders and least cycles */
      def nextMixer =
        mixers
          .filter(m => decodersByTid.contains(m.tid))
          .sortBy(_.curCycles)
          .headOption
      var curMixer = nextMixer

      while (curMixer.isDefined) {
        val decoders                   = decodersByTid(curMixer.get.tid)
        val curDecoder :: restDecoders = decoders

        /* Decode and check if there is remaining LIR */
        if (!curDecoder(curMixer.get))
          if (restDecoders.isEmpty)
            decodersByTid.remove(curMixer.get.tid)
          else
            decodersByTid(curMixer.get.tid) = restDecoders

        curMixer = nextMixer
      }

      /* Pad mixers with NoOps until maximum cycles */
      val maxCycles = mixers.map(_.curCycles).max

      while (mixers.map(_.curCycles).min < maxCycles)
        for (mixer <- mixers)
          if (mixer.curCycles < maxCycles)
            mixer.emitNoOp()

      streamsByTid.foreach(_._2.close())
    }

    for (curTile <- tiles) {
      overlayTiles(curTile, prev0Tile, prev1Tile)

      prev1Tile = prev0Tile
      prev0Tile = curTile
    }

    overlayTiles(Tile(), prev0Tile, prev1Tile)
    overlayTiles(Tile(), Tile(), prev0Tile)

    tiledProgramStream.close()

    // TILED EXPERIMENT ENDS

    for ((key, segment) <- segments) {
      if (printProgramStream.isDefined)
        printProgramStream.get.writeBytes(
          s";\r\n; ${BackendSegmentKeyHelper(key)}\r\n;\r\n"
        )

      decodeSegment(segment, lir)
      segment.file.delete()

      for (
        (offset, instructionTracepointsMap) <-
          segment.instructionTracepointsMaps
      ) {
        traceContext.emitTracepoints(
          instructionOffset + offset,
          instructionTracepointsMap
        )
      }

      instructionOffset += segment.instructionsCount
    }
  }

  def instructionsCount = segments.values.map(_.instructionsCount).sum
}
