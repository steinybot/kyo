package kyo.scheduler

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import scala.annotation.tailrec
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicReference

object Scheduler {

  private val coreWorkers = Runtime.getRuntime().availableProcessors()
  private val stride      = 16

  @volatile
  private var concurrencyLimit = coreWorkers
  private val concurrency      = AtomicInteger(coreWorkers)

  val workers      = CopyOnWriteArrayList[Worker]
  private val idle = AtomicReference[List[Worker]](Nil)
  private val pool = Executors.newCachedThreadPool(ThreadFactory("kyo-worker", new Worker(_)))

  for (_ <- 0 until coreWorkers) {
    pool.execute(() => Worker().runWorker(null))
  }

  def removeWorker(): Unit =
    concurrencyLimit = Math.max(1, concurrency.get() - 1)

  def addWorker(): Unit =
    concurrencyLimit = Math.max(concurrencyLimit, concurrency.get()) + 1
    var c       = concurrency.get()
    val toStart = Math.min(coreWorkers, concurrencyLimit)
    while (c < toStart && concurrency.compareAndSet(c, c + 1)) {
      pool.execute(() => Worker().runWorker(null))
      c = concurrency.get()
    }

  def apply(p: Preemptable): Unit =
    schedule(new Task(p))

  def schedule(t: Task): Unit =
    val w = Worker()
    if (w != null) {
      w.enqueueLocal(t)
    } else {
      submit(t)
    }

  @tailrec def submit(t: Task): Unit =
    val iw = idle.get()
    if ((iw ne Nil) && idle.compareAndSet(iw, iw.tail)) {
      val w  = iw.head
      val ok = w.enqueue(t)
      LockSupport.unpark(w)
      if (ok) {
        return
      }
    }
    val c = concurrency.get()
    if (c < concurrencyLimit && concurrency.compareAndSet(c, c + 1)) {
      pool.execute(() => Worker().runWorker(t))
      return
    }
    var w0: Worker = null
    var w1: Worker = null
    val it         = workers.iterator()
    while (it.hasNext()) {
      val e = it.next()
      if (w0 == null || e.load() < w0.load())
        w1 = w0
        w0 = e
    }
    if (w0 != null && w0.enqueue(t)) {
      return
    }
    if (w1 != null && w1.enqueue(t)) {
      return
    }
    submit(t)

  def loadAvg(): Double =
    var sum = 0L
    val it  = workers.iterator()
    var c   = 0
    while (it.hasNext()) {
      sum += it.next().load()
      c += 1
    }
    sum.doubleValue() / c

  def steal(w: Worker): Task = {
    var w0: Worker = null
    var w1: Worker = null
    val it         = workers.iterator()
    var max        = 1L
    while (it.hasNext()) {
      val e = it.next()
      val l = e.load()
      if (l > max && (e ne w)) {
        max = l
        w1 = w0
        w0 = e
      }
    }
    var r: Task = null
    if (w0 != null) {
      r = w0.steal(w)
    }
    if (r == null && w1 != null) {
      r = w1.steal(w)
    }
    r
  }

  def park(w: Worker, parkTime: Long): Unit =
    val tail = idle.get()
    if (idle.compareAndSet(tail, w :: tail)) {
      LockSupport.parkNanos(this, parkTime)
    }

  def stopWorker(): Boolean =
    val c = concurrency.get()
    c > concurrencyLimit && concurrency.compareAndSet(c, c - 1)

  override def toString =
    import scala.jdk.CollectionConverters._
    val w = workers.asScala.map(_.toString).mkString("\n")
    s"=======================\n$w\nScheduler(loadAvg=${loadAvg()},concurrency=$concurrency,limit=$concurrencyLimit)"

}