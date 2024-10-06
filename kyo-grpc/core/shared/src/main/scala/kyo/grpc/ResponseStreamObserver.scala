package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import org.checkerframework.checker.units.qual.s

// TODO: Should this extend another StreamObserver?
class ResponseStreamObserver[Response](
    responseChannel: Channel[Result[GrpcResponse.Errors, Response]],
    responsesCompleted: AtomicBoolean
)(using Frame) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        println(s"ResponseStreamObserver.onNext: $response")
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(responseChannel.put(Success(response)))).unit.eval
    end onNext

    override def onError(t: Throwable): Unit =
        println(s"ResponseStreamObserver.onError: $t")
        // TODO: Do a better job of backpressuring here.
        val putAndClose =
            for
                _       <- responseChannel.put(Fail(StreamNotifier.throwableToStatusException(t)))
                isEmpty <- responseChannel.empty
                // TODO: Make sure we close properly everywhere else
                _ <- if isEmpty then responseChannel.close else Kyo.unit
            yield ()
        IO.run(Async.run(putAndClose)).unit.eval
    end onError

    override def onCompleted(): Unit =
        print("ResponseStreamObserver.onCompleted")
        val close =
            for
                _       <- responsesCompleted.set(true)
                isEmpty <- responseChannel.empty
                _       <- if isEmpty then responseChannel.close else Kyo.unit
            yield ()
        IO.run(close).eval
    end onCompleted

end ResponseStreamObserver
