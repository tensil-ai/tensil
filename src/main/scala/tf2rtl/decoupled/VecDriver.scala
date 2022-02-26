package tf2rtl.decoupled

import chisel3._
import chisel3.util.ReadyValidIO
import chiseltest._

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

class VecDriver[T <: Data : ClassTag](
    x: ReadyValidIO[Vec[T]]
) {
  // Source (enqueue) functions
  //
  def initSource(): this.type = {
    x.valid.poke(false.B)
    this
  }

  def setSourceClock(clock: Clock): this.type = {
    ClockResolutionUtils.setClock(
      VecDriver.decoupledSourceKey,
      x,
      clock
    )
    this
  }

  def getSourceClock: Clock = {
    ClockResolutionUtils.getClock(
      VecDriver.decoupledSourceKey,
      x,
      x.ready.getSourceClock
    ) // TODO: validate against bits/valid sink clocks
  }

  def enqueueNow(data: Seq[T]): Unit = {
    if (data.length != x.bits.length)
      throw new Exception("data length must match bits length")
    timescope {
      // TODO: check for init
      for (i <- x.bits.indices) {
        x.bits(i).poke(data(i))
      }
      x.valid.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.ready.expect(true.B)
        }
        .joinAndStep(getSourceClock)
    }
  }

  def enqueue(data: Seq[T]): Unit =
    timescope {
      // TODO: check for init
      if (data.length != x.bits.length)
        throw new Exception("data length must match bits length")
      for (i <- x.bits.indices) {
        x.bits(i).poke(data(i))
      }
      x.valid.poke(true.B)
      fork
        .withRegion(Monitor) {
          while (!x.ready.peek().litToBoolean) {
            getSourceClock.step(1)
          }
        }
        .joinAndStep(getSourceClock)
    }

  def enqueueSeq(data: Seq[Seq[T]]): Unit =
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
    ClockResolutionUtils.setClock(VecDriver.decoupledSinkKey, x, clock)
    this
  }

  def getSinkClock: Clock = {
    ClockResolutionUtils.getClock(
      VecDriver.decoupledSinkKey,
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
  def dequeue(): ForkResult[Array[T]] = {
    val result             = new ForkResult[Array[T]](getSinkClock)
    val originalReadyValue = x.ready.peek()
    x.ready.poke(true.B)
    result.setFork(
      fork
        .withRegion(Monitor) {
          waitForValid()
          result.setValue(x.bits.map(_.peek).toArray)
        }
    )
    result.setCleanup(() => {
      x.ready.poke(originalReadyValue)
    })
    result
  }

  def dequeueContinual(numCycles: Int): ForkResult[ListBuffer[Array[T]]] = {
    val result      = new ForkResult[ListBuffer[Array[T]]](getSinkClock)
    val resultValue = new ListBuffer[Array[T]]()
    result.setValue(resultValue)
    val originalReadyValue = x.ready.peek()
    x.ready.poke(true.B)
    result.setFork(fork.withRegion(Monitor) {
      var cycleCount = 0
      while (cycleCount < numCycles) {
        if (x.valid.peek().litToBoolean) {
          resultValue += x.bits.map(_.peek).toArray
        }
        getSinkClock.step(1)
        cycleCount += 1
      }
    })
    result.setCleanup(() => {
      x.ready.poke(originalReadyValue)
    })
    result
  }

  // the result value can only be accessed after fork.join
  def dequeueSeq(len: Int): ForkResult[Array[Array[T]]] = {
    val result      = new ForkResult[Array[Array[T]]](getSinkClock)
    val resultValue = Array.fill(len, x.bits.length)(x.bits.head.peek)
    result.setValue(resultValue)
    val originalReadyValue = x.ready.peek()
    x.ready.poke(true.B)
    result.setFork(fork.withRegion(Monitor) {
      for (i <- 0 until len) {
        waitForValid()
        resultValue(i) = x.bits.map(_.peek).toArray
        getSinkClock.step(1)
      }
    })
    result.setCleanup(() => {
      x.ready.poke(originalReadyValue)
    })
    result
  }

  def expectDequeue(data: Seq[T]): Unit = {
    if (data.length != x.bits.length)
      throw new Exception("data length must match bits length")
    timescope {
      // TODO: check for init
      x.ready.poke(true.B)
      fork
        .withRegion(Monitor) {
          waitForValid()
          x.valid.expect(true.B)
          for (i <- x.bits.indices) {
            x.bits(i).expect(data(i))
          }
        }
        .joinAndStep(getSinkClock)
    }
  }

  def expectDequeueNow(data: Seq[T]): Unit = {
    if (data.length != x.bits.length)
      throw new Exception("data length must match bits length")
    timescope {
      // TODO: check for init
      x.ready.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.valid.expect(true.B)
          for (i <- x.bits.indices) {
            x.bits(i).expect(data(i))
          }
        }
        .joinAndStep(getSinkClock)
    }
  }

  def expectDequeueSeq(data: Seq[Seq[T]]): Unit =
    timescope {
      for (elt <- data) {
        expectDequeue(elt)
      }
    }

  def expectPeek(data: Seq[T]): Unit = {
    if (data.length != x.bits.length)
      throw new Exception("data length must match bits length")
    fork.withRegion(Monitor) {
      x.valid.expect(true.B)
      for (i <- x.bits.indices) {
        x.bits(i).expect(data(i))
      }
    }
  }

  def expectInvalid(): Unit = {
    fork.withRegion(Monitor) {
      x.valid.expect(false.B)
    }
  }
}

object VecDriver {
  protected val decoupledSourceKey = new Object()
  protected val decoupledSinkKey   = new Object()
}
