package tensil.tools

import com.google.protobuf.CodedInputStream
import org.tensorflow.framework.attr_value.AttrValue
import org.tensorflow.framework.graph.GraphDef
import org.tensorflow.framework.node_def.NodeDef
import org.tensorflow.framework.tensor.TensorProto
import org.tensorflow.framework.types.DataType
import tensil.tools.data.{Shape, TensorData}

import java.io.{ByteArrayInputStream, DataInputStream, InputStream}

import java.lang
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import java.nio.file.Paths
import java.io.PrintWriter

// scalastyle:off number.of.methods
package object util {
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

  def interleave[T](roots: Seq[Seq[T]]) = {
    for (i <- 0 until roots.map(_.size).max)
      yield roots.filter(_.isDefinedAt(i)).map(_(i))
  }
}
// scalastyle:on number.of.methods
