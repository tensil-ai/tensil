package tf2rtl.util.decoupled

import chisel3._
import chisel3.util.Queue
import chisel3.util.DecoupledIO
import chisel3.internal.sourceinfo.SourceInfo
import chisel3.util.TransitName

object QueueWithReporting {
  def apply[T <: Data](w: DecoupledIO[T], n: Int, name: String = "")(implicit
      sourceInfo: SourceInfo
  ): DecoupledIO[T] = {
    val source = sourceInfo.makeMessage(s => s)
    val _name  = if (name.isBlank) "" else name + " "
    if (n > (1 << 6)) {
      println(s"Very large queue at $source")
    }

    val blockedReporting   = false
    val timeoutReporting   = false
    val bandwidthReporting = false

    val q = Module(new Queue(chiselTypeOf(w.bits), n))

    q.io.enq <> w

    if (blockedReporting) {
      when(q.io.enq.valid && !q.io.enq.ready) {
        printf(
          p"Waiting for queue at $source  with ${q.io.count}/$n elements\n"
        )
      }
    }

    if (timeoutReporting) {
      val readyTimeout = 50
      val (value, wrap) = chisel3.util.Counter(
        0 to readyTimeout,
        q.io.deq.valid && !q.io.deq.ready,
        q.io.deq.valid && q.io.deq.ready
      )

      when(q.io.deq.valid && value === readyTimeout.U && q.io.count === n.U) {
        printf(
          p"Queue timeout at $source with ${q.io.count}/$n elements of type" + s"${q.io.deq.bits}\n"
        )
      }
    }

    if (bandwidthReporting) {
      val sampleInterval     = 100
      val (cycleCount, wrap) = chisel3.util.Counter(true.B, sampleInterval)
      val (validCount, _) =
        chisel3.util.Counter(0 to sampleInterval, q.io.deq.valid, wrap)
      val (transferredCount, _) = chisel3.util.Counter(
        0 to sampleInterval,
        q.io.deq.valid && q.io.deq.ready,
        wrap
      )

      when(wrap && validCount > 0.U) {
        val p = transferredCount * 100.U / validCount
        printf(
          p"Queue ${_name}at $source stats: $p% ($transferredCount / $validCount in $sampleInterval cycles)\n"
        )
      }
    }

    TransitName(q.io.deq, q)
  }
}
