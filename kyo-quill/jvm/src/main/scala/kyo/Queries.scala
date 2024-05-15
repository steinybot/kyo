package kyo

import io.getquill.{EntityQuery as QuillEntityQuery, Query as QuillQuery, *}
import io.getquill.context.sql.SqlContext
import io.getquill.idiom.Idiom
import scala.util.NotGiven

case class Query[+T](build: QuillQuery[T]) extends AnyVal

object Query:

    inline def lift[T](v: T): T =
        io.getquill.lazyLift(v)

    trait Dynamic[Label <: String, +T]:
        def build(l: Label): QuillQuery[T]
        def dynamic[Label2 <: String, T, U >: T](label: Label2, q: QuillQuery[T]): Dynamic[Label | label.type, U]

    def dynamic[Label <: String, T](label: Label, q: QuillQuery[T]): Dynamic[label.type, T] = ???

    inline def apply[T]: Query[T] =
        Query(query[T])

    extension [T](inline q: Query[T])
        inline def map[U](inline f: T => U): Query[U] =
            Query(q.build.map(f))
        inline def flatMap[U](inline f: T => Query[U]): Query[U] =
            Query(q.build.flatMap(f(_).build))
    end extension
end Query

object test extends App:
    case class Person(name: String, age: Int)
    val ctx       = new SqlMirrorContext(PostgresDialect, SnakeCase)
    inline def q1 = Query[Person].map(_.age).build
    ctx.run {
        q1
    }

    inline def q2 =
        Query.dynamic("plus1", query[Person].map(_.age + 1))
            .dynamic("plus2", query[Person].map(_.age + 2))

    inline def q3 = q2.build("plus1").filter(_ > 18)

    ctx.run {
        q3
    }
end test
