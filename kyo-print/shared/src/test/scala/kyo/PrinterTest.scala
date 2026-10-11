package kyo

class PrinterTest extends kyo.test.Test[Any]:

    val dummyPrinter = new Printer:
        def print(doc: Doc, width: Int): String =
            s"width=$width"

        def printStream(doc: Doc, width: Int): Stream[String, Any] =
            Stream.init(Chunk(s"width=$width"))

    "Printer trait" - {
        "print returns formatted string" in {
            val result = dummyPrinter.print(Doc.text("test"), 80)
            assert(result == "width=80")
        }

        "printStream returns stream of strings" in {
            val stream = dummyPrinter.printStream(Doc.text("test"), 100)
            val chunk  = stream.run.eval
            assert(chunk == Chunk("width=100"))
        }
    }

end PrinterTest
