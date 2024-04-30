package kyo

import scala.annotation.implicitNotFound

class Layer[-Pending, +Resolved, S]:

    def add[R, P, R2, P2, S2](l: Layer[P, R, S2])(
        using
        P => Pending | P2,
        Resolved <:< R2,
        Tag[R],
        Tag[R2]
    ): Layer[P2, R & R2, S & S2] =
        ???

    def add[R, P, R2, S2](f: R2 < (Envs[R] & S2))(
        using
        Resolved <:< P,
        Tag[R2]
    ): Layer[P, Resolved & R2, S & S2] =
        ???
end Layer

object Layers:
    val init: Layer[Nothing, Any, Any] =
        ???

    def run[P, R, T, S1, S2](l: Layer[P, R, S1])(v: T < (Envs[R] & S2))(
        using
        @implicitNotFound("Layer still has pending elements: ${P}")
        ev: Any => P,
        tag: Tag[R]
    ): T < (S1 & S2) =
        ???
    end run
end Layers
