package tensil.data

class Index(indices: Array[Either[Int, Slice]])
    extends Iterable[Either[Int, Slice]] {
  val length = indices.length

  def apply(i: Int): Either[Int, Slice] = indices(i)

  // the size of the array needed to store all the results from accessing with this index
  def size(shp: Shape): Int = shape(shp).product

  def shape(shp: Shape): Shape =
    Shape(
      this
        .zip(shp)
        .map {
          case (idx, shp) =>
            idx match {
              case Left(i) => 1
              case Right(s) =>
                s.length match {
                  case Some(l) => l
                  case None    => shp
                }
            }
        }
        .toArray
    )

  override def iterator: Iterator[Either[Int, Slice]] = indices.iterator

  def asArrays(shape: Shape): Array[Array[Int]] = {
    for ((idx, shp) <- indices.zip(shape)) yield idx match {
      case Left(i)  => Array(i)
      case Right(s) => s.iterator(shp).toArray
    }
  }
}

object Index {
  def apply(dimensions: Array[Int]): Index =
    new Index(dimensions.map(Left.apply))

  def apply[T <: Either[Int, Slice]](indices: Array[T]): Index =
    new Index(indices.toArray)

  def apply[T <: Either[Int, Slice]](indices: T*): Index =
    new Index(indices.toArray)
}
