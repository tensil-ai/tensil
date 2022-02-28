package tensil.axi

import chisel3._

class Queue(n: Int, config: Config) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new Master(config))
    val out = new Master(config)
  })

  io.out.readAddress <> util.Queue(io.in.readAddress, n)
  io.out.writeAddress <> util.Queue(io.in.writeAddress, n)
  io.out.writeData <> util.Queue(io.in.writeData, n)
  io.in.readData <> util.Queue(io.out.readData, n)
  io.in.writeResponse <> util.Queue(io.out.writeResponse, n)
}

object Queue {
  def apply(m: Master, n: Int): Master = {
    val q = Module(new Queue(n, m.config))
    q.io.in <> m
    q.io.out
  }
}
