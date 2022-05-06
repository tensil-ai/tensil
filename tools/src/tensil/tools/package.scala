/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil

import tensil.tools.compiler.{MemoryAddress, MemoryObject}
import tensil.tools.data.Shape

package object tools {
  type TracepointsMap      = Map[MemoryAddress, List[MemoryObject]]
  type CompilerSourceType  = String
  type CompilerInputShape  = Seq[Option[Int]]
  type CompilerInputShapes = Map[Option[String], CompilerInputShape]

  implicit class CompilerInputShapeHelper(val inputShape: CompilerInputShape) {
    override def toString() =
      s"[${inputShape.map(v => if (v.isDefined) v.get.toString else "?").mkString(", ")}]"
  }

  implicit class CompilerInputShapesHelper(
      val inputShapes: CompilerInputShapes
  ) {
    def batchSize = inputShapes.head._2(0).get

    def deduceInputShape(
        name: String,
        modelInputShape: CompilerInputShape
    ): Shape = {
      Shape(
        modelInputShape.zipWithIndex
          .map({
            case (modelDim, i) =>
              val optionsInputShape = inputShapes
                .getOrElse(Some(name), inputShapes(None))

              if (
                optionsInputShape
                  .isDefinedAt(i) && optionsInputShape(i).isDefined
              ) {
                val optionsDim = optionsInputShape(i).get

                if (modelDim.isDefined && modelDim.get != optionsDim)
                  throw new CompilerException(
                    s"Specified input $name shape ${CompilerInputShapeHelper(optionsInputShape)} is incompatible with ${CompilerInputShapeHelper(modelInputShape)}"
                  )

                optionsDim
              } else {
                if (modelDim.isDefined)
                  modelDim.get
                else
                  throw new CompilerException(
                    s"Specified input $name shape ${CompilerInputShapeHelper(optionsInputShape)} is has unspecified dimensions for ${CompilerInputShapeHelper(modelInputShape)}"
                  )
              }
          })
          .toArray
      )
    }
  }
}
