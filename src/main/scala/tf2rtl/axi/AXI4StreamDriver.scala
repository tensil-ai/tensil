package tf2rtl.axi

import chisel3._
import chiseltest._
import tf2rtl.decoupled.ForkResult

class AXI4StreamDriver(x: AXI4Stream) {
  // Source (enqueue) functions
  //
  def initSource(): this.type = {
    x.tvalid.poke(false.B)
    this
  }

  def setSourceClock(clock: Clock): this.type = {
    ClockResolutionUtils.setClock(AXI4StreamDriver.decoupledSourceKey, x, clock)
    this
  }

  def getSourceClock: Clock = {
    ClockResolutionUtils.getClock(
      AXI4StreamDriver.decoupledSourceKey,
      x,
      x.tready.getSourceClock
    ) // TODO: validate against bits/valid sink clocks
  }

  def enqueueNow(data: UInt): Unit =
    timescope {
      // TODO: check for init
      x.tdata.poke(data)
      x.tvalid.poke(true.B)
      x.tlast.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.tready.expect(true.B)
        }
        .joinAndStep(getSourceClock)
    }

  def enqueue(data: UInt): Unit =
    timescope {
      // TODO: check for init
      x.tdata.poke(data)
      x.tvalid.poke(true.B)
      x.tlast.poke(true.B)
      fork
        .withRegion(Monitor) {
          while (!x.tready.peek().litToBoolean) {
            getSourceClock.step(1)
          }
        }
        .joinAndStep(getSourceClock)
    }

  def enqueueSeq(data: Seq[UInt]): Unit =
    timescope {
      for (elt <- data) {
        enqueue(elt)
      }
    }

  // Sink (dequeue) functions
  //
  def initSink(): this.type = {
    x.tready.poke(false.B)
    this
  }

  def setSinkClock(clock: Clock): this.type = {
    ClockResolutionUtils.setClock(AXI4StreamDriver.decoupledSinkKey, x, clock)
    this
  }

  def getSinkClock: Clock = {
    ClockResolutionUtils.getClock(
      AXI4StreamDriver.decoupledSinkKey,
      x,
      x.tvalid.getSourceClock
    ) // TODO: validate against bits/valid sink clocks
  }

  // NOTE: this doesn't happen in the Monitor phase, unlike public functions
  def waitForValid(): Unit = {
    while (!x.tvalid.peek().litToBoolean) {
      getSinkClock.step(1)
    }
  }

  // the result value can only be accessed after fork.join
  def dequeue(): ForkResult[UInt] = {
    val result             = new ForkResult[UInt](getSinkClock)
    val originalReadyValue = x.tready.peek()
    x.tready.poke(true.B)
    result.setFork(
      fork
        .withRegion(Monitor) {
          waitForValid()
          result.setValue(x.tdata.peek)
        }
    )
    result.setCleanup(() => {
      x.tready.poke(originalReadyValue)
    })
    result
  }

  // the result value can only be accessed after fork.join
  def dequeueSeq(len: Int): ForkResult[Array[UInt]] = {
    val result      = new ForkResult[Array[UInt]](getSinkClock)
    val resultValue = Array.fill(len)(x.tdata.peek)
    result.setValue(resultValue)
    val originalReadyValue = x.tready.peek()
    x.tready.poke(true.B)
    result.setFork(fork.withRegion(Monitor) {
      for (i <- 0 until len) {
        waitForValid()
        resultValue(i) = x.tdata.peek
        getSinkClock.step(1)
      }
    })
    result.setCleanup(() => {
      x.tready.poke(originalReadyValue)
    })
    result
  }

  def expectDequeue(data: UInt): Unit =
    timescope {
      // TODO: check for init
      x.tready.poke(true.B)
      fork
        .withRegion(Monitor) {
          waitForValid()
          x.tvalid.expect(true.B)
          x.tdata.expect(data)
        }
        .joinAndStep(getSinkClock)
    }

  def expectDequeueNow(data: UInt): Unit =
    timescope {
      // TODO: check for init
      x.tready.poke(true.B)
      fork
        .withRegion(Monitor) {
          x.tvalid.expect(true.B)
          x.tdata.expect(data)
        }
        .joinAndStep(getSinkClock)
    }

  def expectDequeueSeq(data: Seq[UInt]): Unit =
    timescope {
      for (elt <- data) {
        expectDequeue(elt)
      }
    }

  def expectPeek(data: UInt): Unit = {
    fork.withRegion(Monitor) {
      x.tvalid.expect(true.B)
      x.tdata.expect(data)
    }
  }

  def expectInvalid(): Unit = {
    fork.withRegion(Monitor) {
      x.tvalid.expect(false.B)
    }
  }
}

object AXI4StreamDriver {
  protected val decoupledSourceKey = new Object()
  protected val decoupledSinkKey   = new Object()
}
