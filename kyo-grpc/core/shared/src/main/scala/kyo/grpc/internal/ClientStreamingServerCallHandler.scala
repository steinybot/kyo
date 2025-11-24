package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.Channel
import kyo.grpc.*
import kyo.grpc.Grpc

private[grpc] class ClientStreamingServerCallHandler[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Response])(using
    Frame,
    Tag[Emit[Chunk[Request]]]
) extends BaseStreamingServerCallHandler[Request, Response, GrpcHandler[Stream[Request, Grpc], Response]](f):

    override protected def send(
        call: ServerCall[Request, Response],
        handler: GrpcHandler[Stream[Request, Grpc], Response],
        channel: Channel[Request],
        ready: SignalRef[Boolean]
    ): Status < (Grpc & Emit[Metadata]) =
        def onChunk(chunk: Chunk[Request]) =
            Sync.defer(call.request(chunk.size))

        for
            _ <- Console.printLine("ClientStreamingServerCallHandler: getting response from channel")
            response <- handler(channel.streamUntilClosed().tapChunk(onChunk))
            _ <- Console.printLine("ClientStreamingServerCallHandler: sending response")
            _        <- Sync.defer(call.sendMessage(response))
            _ <- Console.printLine("ClientStreamingServerCallHandler: response sent")
        yield Status.OK
        end for
    end send

end ClientStreamingServerCallHandler
