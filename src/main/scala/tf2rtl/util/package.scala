package tf2rtl

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.internal.firrtl.Width
import chisel3.util.{Cat, DecoupledIO}
import chisel3.Num.toBigInt
import com.google.protobuf.CodedInputStream
import org.tensorflow.framework.attr_value.AttrValue
import org.tensorflow.framework.graph.GraphDef
import org.tensorflow.framework.node_def.NodeDef
import org.tensorflow.framework.tensor.TensorProto
import org.tensorflow.framework.types.DataType
import tf2rtl.data.{Shape, TensorData}

import java.io.{ByteArrayInputStream, DataInputStream, InputStream}

import java.lang
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import java.nio.file.Paths
import chisel3.stage.ChiselStage
import java.io.PrintWriter
import firrtl.transforms.NoCircuitDedupAnnotation
import firrtl.AnnotationSeq
import chisel3.experimental.DataMirror

// scalastyle:off number.of.methods
package object util {
  object Environment extends Enumeration {
    type Environment = Value
    val Synthesis, Simulation = Value
  }

  def isOutput(x: Data): Boolean = {
    DataMirror.directionOf(x) == ActualDirection.Output
  }

  def emitToBuildDir[T <: RawModule](dut: => T, filename: String = ""): Unit = {
    val stage = new ChiselStage
    stage.emitSystemVerilog(
      dut,
      args = Array("--target-dir", "build"),
    )
  }

  def reportThroughput(
      requestBus: DecoupledIO[Data],
      reportingPeriod: Int,
      moduleName: String
  ): Unit = {
    val _reportingPeriod = 20
    val (cycleCounterValue, cycleCounterWrap) =
      chisel3.util.Counter(0 to _reportingPeriod, true.B, false.B)
    val (validCounterValue, validCounterWrap) =
      chisel3.util.Counter(
        0 to _reportingPeriod,
        requestBus.valid,
        cycleCounterWrap
      )
    val (requestCounterValue, _) = chisel3.util.Counter(
      0 to _reportingPeriod,
      requestBus.valid && requestBus.ready,
      cycleCounterWrap
    )
    when(cycleCounterWrap) {
      // output format is a CSV with columns
      // moduleName, requestsCompleted, cyclesValid, cyclesTotal
      printf(
        p"$moduleName, $requestCounterValue, $validCounterValue, ${_reportingPeriod}\n"
      )
    }
  }

  def zero[T <: Data](gen: T): T =
    gen match {
      case _: UInt       => 0.U.asTypeOf(gen)
      case _: SInt       => 0.S.asTypeOf(gen)
      case f: FixedPoint => 0.F(f.binaryPoint).asTypeOf(gen)
      case _             => 0.U(gen.getWidth.W).asTypeOf(gen)
    }

  def one[T <: Data](gen: T): T =
    gen match {
      case _: UInt       => 1.U.asTypeOf(gen)
      case _: SInt       => 1.S.asTypeOf(gen)
      case f: FixedPoint => 1.F(f.binaryPoint).asTypeOf(gen)
      case _             => 1.U(gen.getWidth.W).asTypeOf(gen)
    }

  def widthOf(x: Int): Width = {
    (x: BigInt).bitLength.W
  }

  def widthOf(x: Long): Width = {
    (x: BigInt).bitLength.W
  }

  private def saturateFixedPoint(width: Int, x: SInt): SInt = {
    val max = ((1L << (width - 1)) - 1).S
    val min = (-1L << (width - 1)).S

    Mux(x > max, max, Mux(x < min, min, x))
  }

  /**
    * timesFixedPoint returns the product of two fixed point values with a
    * "round-to-nearest-even" rounding policy.
    *
    * See: https://docs.google.com/spreadsheets/d/14U3z-yJsnC1whWWuBmbnL9ZggzcdP-VvrKsp4Y4ztPg/edit#gid=0
    *
    * @param x
    * @param y
    * @return
    */
  def timesFixedPoint(gen: FixedPoint, x: SInt, y: SInt): FixedPoint = {
    val width       = gen.getWidth
    val binaryPoint = gen.binaryPoint.get

    val mul = x * y

    val mask0 = 1.S << (binaryPoint - 1)
    val mask1 = (1.S << (binaryPoint - 1)) - 1.S
    val mask2 = 1.S << binaryPoint

    val adjustment =
      Mux(
        (((mul & mask0) =/= 0.S) && (((mul & mask1) =/= 0.S) || ((mul & mask2) =/= 0.S))),
        1.S,
        0.S
      )

    val adjusted  = (mul >> binaryPoint) + adjustment
    val saturated = saturateFixedPoint(width, adjusted)

    saturated.asFixedPoint(gen.binaryPoint)
  }

  def plusFixedPoint(gen: FixedPoint, x: SInt, y: SInt): FixedPoint = {
    val width       = gen.getWidth
    val binaryPoint = gen.binaryPoint.get

    // Add extra bit to detect over-/underflow
    val sum       = x +& y
    val saturated = saturateFixedPoint(width, sum)

    saturated.asFixedPoint(gen.binaryPoint)
  }

  def times[T <: Data with Num[T]](gen: T, x: T, y: T): T = {
    (gen, x, y) match {
      case (gen: FixedPoint, x: FixedPoint, y: FixedPoint) =>
        timesFixedPoint(gen, x.asSInt(), y.asSInt()).asInstanceOf[T]
      case _ => x * y
    }
  }

  def plus[T <: Data with Num[T]](gen: T, x: T, y: T): T = {
    (gen, x, y) match {
      case (gen: FixedPoint, x: FixedPoint, y: FixedPoint) =>
        plusFixedPoint(gen, x.asSInt(), y.asSInt()).asInstanceOf[T]
      case _ => x + y
    }
  }

  def minus[T <: Data with Num[T]](gen: T, x: T, y: T): T = {
    (gen, x, y) match {
      case (gen: FixedPoint, x: FixedPoint, y: FixedPoint) =>
        plusFixedPoint(gen, x.asSInt(), -y.asSInt()).asInstanceOf[T]
      case _ => x - y
    }
  }

  /** Returns a new array consisting of the original array repeated
    * n times.
    *
    * @param n the number of times to repeat the array
    * @param arr the array to be repeated
    * @tparam T
    * @return a new array
    */
  def repeat[T : ClassTag](n: Int)(arr: Array[T]): Array[T] = {
    Array.concat(Array.fill(n)(arr): _*)
  }

  def connectDecoupledProcessor[T <: Data, S <: Data](
      op: DecoupledIO[T],
      in: DecoupledIO[S],
      out: DecoupledIO[S]
  ): Unit = {
    op.ready := in.valid && out.ready
    in.ready := op.valid && out.ready
    out.valid := op.valid && in.valid
  }

  def getShape(node: NodeDef): Shape = {
    if (node.attr.contains("shape")) {
      val dims = node.attr("shape").value.shape.get.dim
      Shape(dims.map(d => d.size.toInt): _*)
    } else {
      throw new NotImplementedError
    }
  }

  def getDataType(node: NodeDef): DataType = {
    if (node.attr.contains("dtype")) {
      node.attr("dtype").value.`type`.get
    } else {
      throw new NotImplementedError
    }
  }

  def getTensorData(node: NodeDef): TensorData[Any] = {
    if (!node.attr.contains("value") || !node.attr.contains("dtype")) {
      throw new NotImplementedError
    }
    val dtype = node.attr("dtype").value.`type`.get
    val tdef  = node.attr("value").value.tensor.get
    val dims  = tdef.getTensorShape.dim
    val shape = Shape(dims.map(_.size.asInstanceOf[Int]).toArray)

    if (tdef.floatVal.nonEmpty) {
      new TensorData(shape, tdef.floatVal, dtype)
    } else if (tdef.intVal.nonEmpty) {
      new TensorData(shape, tdef.intVal, dtype)
    } else {
      getTensorContent(tdef)
    }
  }

  def printVec[T <: Data](v: Vec[T]): Unit = {
    var first = true
    for (i <- 0 until v.length) {
      if (first) {
        first = false
      } else {
        printf(", ")
      }
      printf(p"${v(i).asUInt()}")
    }
  }

  private def getTensorContent(tdef: TensorProto): TensorData[Any] = {
    val dims  = tdef.getTensorShape.dim
    val shape = Shape(dims.map(_.size.asInstanceOf[Int]).toArray)

    tdef.dtype match {
      case DataType.DT_FLOAT =>
        val buf = CodedInputStream.newInstance(
          tdef.tensorContent.asReadOnlyByteBuffer()
        )
        val floats = new ArrayBuffer[Float]
        while (!buf.isAtEnd) {
          floats += buf.readFloat()
        }
        new TensorData(shape, floats, tdef.dtype)
      case DataType.DT_INT32 =>
        val buf = CodedInputStream.newInstance(
          tdef.tensorContent.asReadOnlyByteBuffer()
        )
        val ints = new ArrayBuffer[Int]
        while (!buf.isAtEnd) {
          ints += buf.readRawLittleEndian32()
        }
        new TensorData(shape, ints, tdef.dtype)
      case _ =>
        println(tdef)
        throw new NotImplementedError()
    }
  }

  // scalastyle:off cyclomatic.complexity
  def getSeqFromAttrValue[T](attr: AttrValue, t: T): Seq[T] = {
    val al = attr.getList
    val l = t match {
      case _: Long =>
        if (
          al.s.nonEmpty || al.b.nonEmpty || al.f.nonEmpty ||
          al.func.nonEmpty || al.shape.nonEmpty || al.tensor.nonEmpty
        ) {
          throw new lang.IllegalArgumentException("Not a list of Long")
        }
        al.i
      case _: String =>
        if (
          al.i.nonEmpty || al.b.nonEmpty || al.f.nonEmpty ||
          al.func.nonEmpty || al.shape.nonEmpty || al.tensor.nonEmpty
        ) {
          throw new lang.IllegalArgumentException("Not a list of String")
        }
        attr.getList.s.map(s => s.toStringUtf8)
      case _: Boolean =>
        if (
          al.i.nonEmpty || al.s.nonEmpty || al.f.nonEmpty ||
          al.func.nonEmpty || al.shape.nonEmpty || al.tensor.nonEmpty
        ) {
          throw new lang.IllegalArgumentException("Not a list of Boolean")
        }
        attr.getList.b
      case _: Float =>
        if (
          al.i.nonEmpty || al.b.nonEmpty || al.s.nonEmpty ||
          al.func.nonEmpty || al.shape.nonEmpty || al.tensor.nonEmpty
        ) {
          throw new lang.IllegalArgumentException("Not a list of Float")
        }
        attr.getList.f
      case _ =>
        throw new IllegalArgumentException(
          "Unfetchable type " + t.getClass.getName
        )
    }
    l.map(t => t.asInstanceOf[T])
  }
  // scalastyle:on cyclomatic.complexity

  def protoFromStream[T <: scalapb.GeneratedMessage](
      t: scalapb.GeneratedMessageCompanion[T],
      stream: InputStream
  ): T = {
    val s  = new DataInputStream(stream)
    val cs = CodedInputStream.newInstance(s)
    t.parseFrom(cs)
  }

  def doubleToFixedPoint(x: Double, bp: Int): BigInt = {
    Math.round(x * (1 << bp))
  }

  def fixedPointToDouble(x: BigInt, bp: Int): Double = {
    x.toDouble / (1 << bp)
  }

  @scala.annotation.tailrec
  def bitMask(from: Int, to: Int): BigInt = {
    if (from > to) {
      bitMask(to, from)
    } else if (from == to) {
      BigInt(0)
    } else {
      ((BigInt(1) << to) - 1) - ((BigInt(1) << from) - 1)
    }
  }

  @scala.annotation.tailrec
  def extractBitField(b: BigInt, from: Int, to: Int): BigInt = {
    if (from > to) {
      extractBitField(b, to, from)
    } else if (from == to) {
      BigInt(0)
    } else {
      (b & bitMask(from, to)) >> from
    }
  }

  def vecToPrintable[T <: Bits](v: Vec[T]): Printable = {
    var p     = p""
    var first = true
    for (x <- v) {
      if (!first) {
        p += p" "
      } else {
        first = false
      }
      p += p"${x.asUInt()}"
    }
    p
  }

  def graphDefFromTextFormat(text: String): GraphDef = {
    val s  = new DataInputStream(new ByteArrayInputStream(text.getBytes()))
    var cs = CodedInputStream.newInstance(s)
    GraphDef.parseFrom(cs)
  }

  def divCeil(a: Int, b: Int): Int = {
    (a + (b - 1)) / b
  }

  def divCeil(a: BigInt, b: BigInt): BigInt = {
    (a + (b - 1)) / b
  }

  def cat(v: Seq[Bits]): Bits = {
    if (v.length == 1) {
      v.head
    } else {
      Cat(v.head, cat(v.tail))
    }
  }

  // scalastyle:off cyclomatic.complexity
  def streamTransmission(
      in: Iterable[BigInt],
      inputWidth: Int,
      outputWidth: Int
  ): Iterable[BigInt] = {
    if (inputWidth % outputWidth != 0 && outputWidth % inputWidth != 0) {
      throw new Exception(
        "Can't handle read elements overlapping write element " +
          "boundaries and vice verse. Make sure that either readWidth is a multiple " +
          "of writeWidth or writeWidth is a multiple of readWidth."
      )
    }
    if (inputWidth == outputWidth) {
      in
    } else if (inputWidth < outputWidth) {
      val cacheSize = divCeil(outputWidth, inputWidth)
      val cache     = Array.fill(cacheSize)(BigInt(0))
      var ctr       = 0

      val out = new mutable.Queue[BigInt]

      for (word <- in) {
        cache(ctr) = word
        if (ctr == (cacheSize - 1)) {
          out += combineWords(cache, inputWidth)
          ctr = 0
        } else {
          ctr += 1
        }
      }

      if (ctr != 0) {
        // Flush remaining values
        for (i <- ctr until cacheSize) {
          cache(ctr) = 0
        }
        out += combineWords(cache, inputWidth)
      }

      out
    } else if (inputWidth > outputWidth) {
      val range = divCeil(inputWidth, outputWidth)

      val out = new mutable.Queue[BigInt]

      for (word <- in) {
        out ++= splitWord(word, outputWidth).padTo(range, BigInt(0))
      }

      out
    } else {
      // should never get here, included only for scala type inference
      in
    }
  }
  // scalastyle:on cyclomatic.complexity

  /**
    * combineWords takes an array of words of the specified size and
    * concatenates them into a single word of size N*wordSize, where N is the
    * number of words in the array
    */
  def combineWords(words: Array[BigInt], inputWordSize: Int): BigInt = {
    var out = BigInt(0)
    for (i <- (words.length - 1) until -1 by -1) {
      out = (out << inputWordSize) | words(i)
    }
    out
  }

  /**
    * splitWord takes a word and splits into an array of words of the specified
    * size.
    */
  def splitWord(word: BigInt, outputWordSize: Int): Array[BigInt] = {
    val words =
      new ArrayBuffer[BigInt](divCeil(word.bitLength, outputWordSize))
    var tmp  = word
    val mask = (BigInt(1) << outputWordSize) - 1
    while (tmp.bitLength > 0) {
      words += tmp & mask
      tmp = tmp >> outputWordSize
    }
    words.toArray
  }

  def signedMod(x: SInt, mod: Int): SInt = {
    val result = WireInit((mod - 1).S)
    when(x < 0.S) {
      result := (mod - 1).S - ((0.S - x) % mod.S) + 1.S
    }.otherwise {
      result := x % mod.S
    }
    result
  }

  def isMaxValue(x: UInt): Bool = {
    val max = ((BigInt(1) << x.getWidth) - 1).U
    x === max
  }

  def bigIntToBytes(i: BigInt, width: Int): Array[Byte] = {
    // width is the desired width in bytes
    val b = i.toByteArray
    if (b.length == width) {
      b
    } else {
      if (b.length < width) {
        val d = width - b.length
        Array.fill[Byte](d)(0) ++ b
      } else {
        throw new Exception(s"$i is wider than specified width $width")
      }
    }
  }

  def floatToFixedPointBytes(
      f: Float,
      width: Int,
      binaryPoint: Int
  ): Array[Byte] = {
    if (width % 8 != 0)
      throw new Exception(
        s"width of $width bits will not cleanly fit 8-bit bytes"
      )
    bigIntToBytes(toBigInt(f, binaryPoint), width / 8)
  }

  def sufficientBytes(width: Int): Int = 8 * divCeil(width, 8)

  implicit class DecoupledHelper[T <: Data](d: DecoupledIO[T]) {
    def tieOff(): Unit = {
      d.bits := zero(d.bits)
      d.valid := false.B
    }

    def tieOffFlipped(): Unit = {
      d.ready := false.B
    }
  }

  def decoupledLit[T <: Data](lit: T): DecoupledIO[T] = {
    val w = Wire(DecoupledIO(chiselTypeOf(lit)))
    w.bits := lit
    w.valid := true.B
    w
  }

  def greatestCommonDivisor(a: Int, b: Int): Int = {
    if (a < b) {
      greatestCommonDivisor(b, a)
    } else {
      var a_ = a
      var b_ = b
      var r_ = b
      while (r_ > 0) {
        r_ = a_ % b_
        a_ = b_
        b_ = r_
      }
      a_
    }
  }

  def allReady(ports: DecoupledIO[Data]*): Bool = {
    ports.map(_.ready).reduce(_ && _)
  }

  def enqueue[S <: Data](port: DecoupledIO[S], value: S): Bool = {
    port.bits <> value
    port.valid := true.B
    port.ready
  }

  def interleave[T](roots: Seq[Seq[T]]) = {
    for (i <- 0 until roots.map(_.size).max)
      yield roots.filter(_.isDefinedAt(i)).map(_(i))
  }
}
// scalastyle:on number.of.methods
