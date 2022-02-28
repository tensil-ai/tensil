package tensil.decoupled

import chisel3.Clock
import chiseltest.internal.TesterThreadList

class ForkResult[S](
    clock: Clock,
) {
  private var threadList: TesterThreadList  = _
  private var value: Option[S]              = None
  private var cleanupFn: Option[() => Unit] = None

  def setFork(fork: TesterThreadList): Unit = this.threadList = fork
  def setValue(value: S): Unit              = this.value = Some(value)
  def setCleanup(fn: () => Unit): Unit      = this.cleanupFn = Some(fn)

  def join(): S = {
    threadList.joinAndStep(clock)
    cleanupFn match {
      case Some(fn) => fn()
      case None     =>
    }
    value.get
  }
}
