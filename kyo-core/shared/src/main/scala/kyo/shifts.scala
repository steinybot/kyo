package kyo

import kyo.core.*
import scala.annotation.implicitNotFound

class Shifts[V, S]() extends Effect[Shifts[V, S]]:
    type Command[T] = (T => V < (Shifts[V, S] & S)) => V < (Shifts[V, S] & S)

    private[Shifts] val handler =
        new Handler[[T] =>> (T => V < (Shifts[V, S] & S)) => V < (Shifts[V, S] & S), Shifts[V, S], Any]:
            def resume[T, U: Flat, S2](
                command: (T => V < (Shifts[V, S] & S)) => V < (Shifts[V, S] & S),
                k: T => U < (Shifts[V, S] & S2)
            )(using Tag[Shifts[V, S]]) =
                Resume(
                    (),
                    command { t =>
                        k(t).asInstanceOf[V < (Shifts[V, S] & S)]
                    }.asInstanceOf[U < (Shifts[V, S] & S2)]
                )
end Shifts

object Shifts:
    private case object shifts extends Shifts[Any, Any]
    private def shifts[V, S]: Shifts[V, S] = shifts.asInstanceOf[Shifts[V, S]]

    def apply[T, V, S](f: (T => V < (Shifts[V, S] & S)) => V < (Shifts[V, S] & S))(using Tag[Shifts[V, S]]): T < Shifts[V, S] =
        shifts[V, S].suspend(f)

    def run[T: Flat, V: Flat, S, S2](v: T < (Shifts[V, S] & S2))(
        using
        @implicitNotFound("Detected shifts of type '${V}' but the computation produces a '${T}'.")
        ev: T <:< V,
        tag: Tag[Shifts[V, S]]
    ): V < (S & S2) =
        val s = shifts[V, S]
        s.handle(s.handler)((), v.asInstanceOf[V < (Shifts[V, S] & S2)])
    end run
end Shifts
