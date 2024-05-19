package kyo

import kyo.scheduler.*
import zio.*
import zio.internal.ExecutionMetrics
import zio.internal.FiberRuntime

object KyoSchedulerRuntime {

    lazy val default: Runtime[Any] = {
        val exec =
            new Executor {
                val scheduler =
                    kyo.scheduler.Scheduler.get
                def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] = None
                def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = {
                    scheduler.schedule(kyo.scheduler.Task(runnable.run()))
                    true
                }
            }
        Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe.run {
                for {
                    scope   <- Scope.make
                    env     <- (Runtime.setExecutor(exec) ++ Runtime.setBlockingExecutor(exec)).build(scope)
                    runtime <- ZIO.runtime[Any].provideEnvironment(env)
                } yield runtime
            }.getOrThrowFiberFailure()
        }
    }
}
