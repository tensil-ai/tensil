package tf2rtl.decoupled

import chisel3.util.ReadyValidIO
import chisel3._
import chiseltest._

import scala.reflect.ClassTag

class Driver[T <: Data : ClassTag](
    x: ReadyValidIO[T]
) {
  // Source (enqueue) functions
  //
  def initSource(): this.type = {
    x.valid.poke(false.B)
    this
  }

  def setSourceClock(clock: Clock): this.type = {
    ClockResolutionUtils.setClock(Driver.decoupledSourceKey, x, clock)
    this
  }

  def getSourceClock: Clock = {
    ClockResolutionUtils.getClock(
      Driver.decoupledSourceKey,
      x,
      x.ready.getSourceClock
    ) // TODO: validate against bits/valid sink clocks
  }

  def enqueueNow(data: T): Unit =
    timescope {
      // TODO: check for init
      x.bits.poke(data)
      x.valid.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.ready.expect(true.B)
        }
        .joinAndStep(getSourceClock)
    }

  def enqueueAsync(data: T): Unit = {
//    val prevBits  = x.bits.peek()
//    val prevValid = x.valid.peek()
    x.bits.poke(data)
    x.valid.poke(true.B)
//
//    def cleanup(): Unit = {
//      x.bits.poke(prevBits)
//      x.valid.poke(prevValid)
//    }
//    cleanup
  }

  def enqueue(data: T): Unit =
    timescope {
      // TODO: check for init
      x.bits.poke(data)
      x.valid.poke(true.B)
      fork
        .withRegion(Monitor) {
          while (!x.ready.peek().litToBoolean) {
            getSourceClock.step(1)
          }
        }
        .joinAndStep(getSourceClock)
    }

  def enqueueSeq(data: Seq[T]): Unit =
    timescope {
      for (elt <- data) {
        enqueue(elt)
      }
    }

  // Sink (dequeue) functions
  //
  def initSink(): this.type = {
    x.ready.poke(false.B)
    this
  }

  def setSinkClock(clock: Clock): this.type = {
    ClockResolutionUtils.setClock(Driver.decoupledSinkKey, x, clock)
    this
  }

  def getSinkClock: Clock = {
    ClockResolutionUtils.getClock(
      Driver.decoupledSinkKey,
      x,
      x.valid.getSourceClock
    ) // TODO: validate against bits/valid sink clocks
  }

  // NOTE: this doesn't happen in the Monitor phase, unlike public functions
  def waitForValid(): Unit = {
    while (!x.valid.peek().litToBoolean) {
      getSinkClock.step(1)
    }
  }

  // the result value can only be accessed after fork.join
  def dequeue(): ForkResult[T] = {
    val result             = new ForkResult[T](getSinkClock)
    val originalReadyValue = x.ready.peek()
    x.ready.poke(true.B)
    result.setFork(
      fork
        .withRegion(Monitor) {
          waitForValid()
          result.setValue(x.bits.peek)
        }
    )
    result.setCleanup(() => {
      x.ready.poke(originalReadyValue)
    })
    result
  }

  // the result value can only be accessed after fork.join
  def dequeueSeq(len: Int): ForkResult[Array[T]] = {
    val result      = new ForkResult[Array[T]](getSinkClock)
    val resultValue = Array.fill(len)(x.bits.peek)
    result.setValue(resultValue)
    val originalReadyValue = x.ready.peek()
    x.ready.poke(true.B)
    result.setFork(fork.withRegion(Monitor) {
      for (i <- 0 until len) {
        waitForValid()
        resultValue(i) = x.bits.peek
        getSinkClock.step(1)
      }
    })
    result.setCleanup(() => {
      x.ready.poke(originalReadyValue)
    })
    result
  }

  def expectDequeue(data: T): Unit =
    timescope {
      // TODO: check for init
      x.ready.poke(true.B)
      fork
        .withRegion(Monitor) {
          waitForValid()
          x.valid.expect(true.B)
          x.bits.expect(data)
        }
        .joinAndStep(getSinkClock)
    }

  def expectDequeueNow(data: T): Unit =
    timescope {
      // TODO: check for init
      x.ready.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.valid.expect(true.B)
          x.bits.expect(data)
        }
        .joinAndStep(getSinkClock)
    }

  def expectDequeueSeq(data: Seq[T]): Unit =
    timescope {
      for (elt <- data) {
        expectDequeue(elt)
      }
    }

  def expectPeek(data: T): Unit = {
    fork.withRegion(Monitor) {
      x.valid.expect(true.B)
      x.bits.expect(data)
    }
  }

  def expectInvalid(): Unit = {
    fork.withRegion(Monitor) {
      x.valid.expect(false.B)
    }
  }
}

object Driver {
  protected val decoupledSourceKey = new Object()
  protected val decoupledSinkKey   = new Object()
}
