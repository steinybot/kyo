package kyo.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import org.HdrHistogram.ConcurrentDoubleHistogram as HdrHistogram
import scala.annotation.targetName

object StatsRegistry {

    import internal.*

    def scope(path: List[String]): Scope = new Scope(path)

    class Scope private[kyo] (path: List[String]) {

        def counter(name: String): Counter =
            counters.get(name :: path, new Counter())

        def histogram(name: String): Histogram =
            histogram(name, 4)

        def histogram(name: String, numberOfSignificantValueDigits: Int): Histogram =
            histogram(name, numberOfSignificantValueDigits, 2)

        def histogram(name: String, numberOfSignificantValueDigits: Int, highestToLowestValueRatio: Long): Histogram =
            histograms.get(name :: path, new Histogram(numberOfSignificantValueDigits, highestToLowestValueRatio))

        def gauge(name: String)(run: => Double): Gauge =
            gauges.get(name :: path, new Gauge(() => run, false))

        def counterGauge(name: String)(run: => Double): Gauge =
            gauges.get(name :: path, new Gauge(() => run, true))

        @targetName("gaugeLong")
        def gauge(name: String)(run: => Long): Gauge =
            gauge(name)(run.toDouble)

        @targetName("counterGaugeLong")
        def counterGauge(name: String)(run: => Long): Gauge =
            counterGauge(name)(run.toDouble)
    }

    class Counter private[StatsRegistry] () {
        private val adder      = new LongAdder
        def get(): Long        = adder.sum()
        def inc(): Unit        = adder.increment()
        def add(v: Long): Unit = adder.add(v)
    }

    class Histogram private[StatsRegistry] (numberOfSignificantValueDigits: Int, highestToLowestValueRatio: Long) {
        private val hdr =
            new HdrHistogram(
                highestToLowestValueRatio,
                numberOfSignificantValueDigits
            )

        def observe(v: Long): Unit =
            hdr.recordValue(v.toDouble)

        def observe(v: Double): Unit =
            hdr.recordValue(v)

        def count(): Long =
            hdr.getTotalCount()

        def valueAtPercentile(v: Double): Double =
            hdr.getValueAtPercentile(v)
    }

    class Gauge private[StatsRegistry] (run: () => Double, counter: Boolean) {
        def collect(): Double    = run()
        def isCounter(): Boolean = counter
    }

    private object internal {

        val gcInterval = System.getProperty("kyo.stats.gcIntervalMs", "1000").toInt

        val counters   = new Store[Counter]
        val histograms = new Store[Histogram]
        val gauges     = new Store[Gauge]

        class Store[T] {
            private val map = new ConcurrentHashMap[List[String], WeakReference[T]]
            def get(path: List[String], init: => T): T = {
                val ref   = map.computeIfAbsent(path, _ => new WeakReference(init))
                val value = ref.get()
                if (value == null) {
                    map.remove(ref)
                    get(path, init)
                } else {
                    value
                }
            }
            def gc(): Unit =
                map.forEach { (path, ref) =>
                    if (ref.get() == null) {
                        map.remove(path)
                        ()
                    }
                }
        }

        val gc: Runnable = () => {
            counters.gc()
            histograms.gc()
            gauges.gc()
        }

        val threadFactory = new ThreadFactory {
            def newThread(r: Runnable) = {
                val thread = new Thread
                thread.setName("kyo-stats-registry-gc")
                thread.setDaemon(true)
                thread
            }
        }
        Executors.newSingleThreadScheduledExecutor(threadFactory)
            .scheduleAtFixedRate(gc, gcInterval, gcInterval, TimeUnit.MILLISECONDS)
    }
}
