package tf2rtl.data

import org.tensorflow.framework.types.DataType

class TensorData[T](
    val shape: Shape,
    val data: Seq[T],
    val dtype: DataType = DataType.DT_FLOAT
) {
  def as4D: Seq[Seq[Seq[Seq[T]]]] = {
    for (i <- 0 until shape(0))
      yield for (j <- 0 until shape(1))
        yield for (k <- 0 until shape(2))
          yield for (l <- 0 until shape(3))
            yield data(
              i * shape(1) * shape(2) * shape(3) +
                j * shape(2) * shape(3) + k * shape(3) + l
            )
  }

  def as4DCCHW: Seq[Seq[Seq[Seq[T]]]] = {
    for (l <- 0 until shape(3))
      yield for (k <- 0 until shape(2))
        yield for (i <- 0 until shape(0))
          yield for (j <- 0 until shape(1))
            yield data(
              i * shape(1) * shape(2) * shape(3) +
                j * shape(2) * shape(3) + k * shape(3) + l
            )
  }

  def as3D: Seq[Seq[Seq[T]]] = {
    for (i <- 0 until shape(0))
      yield for (j <- 0 until shape(1))
        yield for (k <- 0 until shape(2))
          yield data(i * shape(1) * shape(2) + j * shape(2) + k)
  }

  def as1D: Seq[T] = data
}

object TensorData {
  def fill[T](shape: Shape): T => TensorData[T] =
    (data: T) => new TensorData(shape, Seq.fill(shape.arraySize)(data))

  def from3D[T](data: Seq[Seq[Seq[T]]]): TensorData[T] = {
    val shape = Shape(data.length, data.head.length, data.head.head.length)
    new TensorData(shape, data.flatten.flatten)
  }

  def from4D[T](data: Seq[Seq[Seq[Seq[T]]]]): TensorData[T] = {
    val shape = Shape(
      data.length,
      data.head.length,
      data.head.head.length,
      data.head.head.head.length
    )
    new TensorData(shape, data.flatten.flatten.flatten)
  }
}
