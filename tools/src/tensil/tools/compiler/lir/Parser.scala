/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler.lir

import scala.collection.mutable

import tensil.tools.TracepointsMap
import tensil.tools.compiler.{
  LIR,
  InstructionContext,
  MemoryAddress,
  MemoryAddressRaw,
  InstructionAddress
}

object Parser {
  def concat(parsers: Parser*): Parser =
    new Parser {

      override def hasNext: Boolean =
        !parsers.filter(_.hasNext).isEmpty

      override def parseNext(lir: LIR): Unit =
        parsers.filter(_.hasNext).head.parseNext(lir)
    }

  def injectInstructionAddress(parser: Parser): Parser = {
    new Parser {
      private var instructionOffset: InstructionAddress =
        InstructionAddress.Zero
      private def inject(
          context: Option[InstructionContext]
      ): Option[InstructionContext] = {
        val r = InstructionContext.injectInstructionAddress(
          context,
          instructionOffset
        )
        instructionOffset += InstructionAddress.One
        r
      }

      private var currentLir: Option[LIR] = None
      private val injectedLir = new LIR {
        def emitWait(
            tid: Int,
            tidToWait: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitWait(tid, tidToWait, inject(context))

        def emitMatMul(
            accumulate: Boolean,
            localStride: Int,
            localAddress: MemoryAddress,
            accumulatorStride: Int,
            accumulatorAddress: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitMatMul(
            accumulate: Boolean,
            localStride,
            localAddress,
            accumulatorStride,
            accumulatorAddress,
            size,
            tid,
            inject(context)
          )

        def emitSIMD(
            accumulate: Boolean,
            simdOp: Int,
            simdSourceLeft: Int,
            simdSourceRight: Int,
            simdDestination: Int,
            writeAccumulatorAddress: MemoryAddress,
            readAccumulatorAddress: MemoryAddress,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitSIMD(
            accumulate,
            simdOp,
            simdSourceLeft,
            simdSourceRight,
            simdDestination,
            writeAccumulatorAddress,
            readAccumulatorAddress,
            tid,
            inject(context)
          )

        def emitDataMove(
            toLocal: Boolean,
            accumulate: Boolean,
            localStride: Int,
            localAddress: MemoryAddress,
            stride: Int,
            address: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitDataMove(
            toLocal,
            accumulate,
            localStride,
            localAddress,
            stride,
            address,
            size,
            tid,
            inject(context)
          )

        def emitLoadWeights(
            localStride: Int,
            localAddress: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitLoadWeights(
            localStride,
            localAddress,
            size,
            tid,
            inject(context)
          )

        def endEmit(): Unit = {}
      }

      override def hasNext: Boolean = parser.hasNext

      override def parseNext(lir: LIR): Unit = {
        currentLir = Some(lir)
        parser.parseNext(injectedLir)
      }
    }
  }

  def injectInstructionTracepointsMaps(
      parser: Parser,
      instructionTracepointsMaps: Map[InstructionAddress, TracepointsMap]
  ): Parser = {
    new Parser {
      private var instructionOffset: InstructionAddress =
        InstructionAddress.Zero
      private def inject(
          context: Option[InstructionContext]
      ): Option[InstructionContext] =
        InstructionContext.injectInstructionTracepointsMaps(
          context,
          instructionTracepointsMaps
        )

      private var currentLir: Option[LIR] = None
      private val injectedLir = new LIR {
        def emitWait(
            tid: Int,
            tidToWait: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitWait(tid, tidToWait, inject(context))

        def emitMatMul(
            accumulate: Boolean,
            localStride: Int,
            localAddress: MemoryAddress,
            accumulatorStride: Int,
            accumulatorAddress: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitMatMul(
            accumulate: Boolean,
            localStride,
            localAddress,
            accumulatorStride,
            accumulatorAddress,
            size,
            tid,
            inject(context)
          )

        def emitSIMD(
            accumulate: Boolean,
            simdOp: Int,
            simdSourceLeft: Int,
            simdSourceRight: Int,
            simdDestination: Int,
            writeAccumulatorAddress: MemoryAddress,
            readAccumulatorAddress: MemoryAddress,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitSIMD(
            accumulate,
            simdOp,
            simdSourceLeft,
            simdSourceRight,
            simdDestination,
            writeAccumulatorAddress,
            readAccumulatorAddress,
            tid,
            inject(context)
          )

        def emitDataMove(
            toLocal: Boolean,
            accumulate: Boolean,
            localStride: Int,
            localAddress: MemoryAddress,
            stride: Int,
            address: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitDataMove(
            toLocal,
            accumulate,
            localStride,
            localAddress,
            stride,
            address,
            size,
            tid,
            inject(context)
          )

        def emitLoadWeights(
            localStride: Int,
            localAddress: MemoryAddress,
            size: MemoryAddressRaw,
            tid: Int,
            context: Option[InstructionContext]
        ): Unit =
          currentLir.get.emitLoadWeights(
            localStride,
            localAddress,
            size,
            tid,
            inject(context)
          )

        def endEmit(): Unit = {}
      }

      override def hasNext: Boolean = parser.hasNext

      override def parseNext(lir: LIR): Unit = {
        currentLir = Some(lir)
        parser.parseNext(injectedLir)
      }
    }
  }
}

abstract trait Parser {
  def parseAll(lir: LIR): Unit = {
    while (hasNext)
      parseNext(lir)

    lir.endEmit()
  }

  def hasNext: Boolean
  def parseNext(lir: LIR): Unit
}
