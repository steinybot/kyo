package kyo.grpc

import kyo.*
import kyo.internal.Platform
import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.annotation.targetName
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess: Assertion              = succeed
    def assertionFailure(msg: String): Assertion = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    def run(v: Future[Assertion] < (Abort[Any] & Async & Scope)): Future[Assertion] =
        import AllowUnsafe.embrace.danger
        val effect = Scope.run {
            Abort.run {
                v
            }
        }
        val fiber = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(effect))
        val fut   = fiber.unsafe.toFuture()
        fut.flatMap {
            case Result.Success(innerFut)      => innerFut
            case Result.Failure(ex: Throwable) => Future.failed(ex)
            case Result.Failure(other)         => Future.failed(new RuntimeException(s"Test aborted with $other"))
            case Result.Panic(ex)              => Future.failed(ex)
        }
    end run

    @targetName("runAssertion")
    def run(v: Assertion < (Abort[Any] & Async & Scope)): Future[Assertion] =
        run(v.map(Future.successful(_)))

    def runJVM(v: => Future[Assertion] < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isJVM then run(v) else Future.successful(succeed)

    @targetName("runJVMAssertion")
    def runJVM(v: => Assertion < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isJVM then run(v) else Future.successful(succeed)

    def runJS(v: => Future[Assertion] < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isJS then run(v) else Future.successful(succeed)

    @targetName("runJSAssertion")
    def runJS(v: => Assertion < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isJS then run(v) else Future.successful(succeed)

    def runNotJS(v: => Future[Assertion] < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if !Platform.isJS then run(v) else Future.successful(succeed)

    @targetName("runNotJSAssertion")
    def runNotJS(v: => Assertion < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if !Platform.isJS then run(v) else Future.successful(succeed)

    def runNative(v: => Future[Assertion] < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isNative then run(v) else Future.successful(succeed)

    @targetName("runNativeAssertion")
    def runNative(v: => Assertion < (Abort[Any] & Async & Scope)): Future[Assertion] =
        if Platform.isNative then run(v) else Future.successful(succeed)

end Test
