package kyo

class DocInterpolationTest extends kyo.test.Test[Any]:

    "single line literals" - {
        "plain literal" in {
            assert(Printer.greedy.print(doc"hello", 80) == "hello")
        }

        "empty literal" in {
            assert(Printer.greedy.print(doc"", 80) == "")
        }

        "string argument" in {
            val name = "world"
            assert(Printer.greedy.print(doc"Hello, $name!", 80) == "Hello, world!")
        }

        "doc argument inline" in {
            val b = Doc.text("b")
            assert(Printer.greedy.print(doc"a ${b} c", 80) == "a b c")
        }

        "space before argument is preserved" in {
            val x = "v"
            assert(Printer.greedy.print(doc"foo $x", 80) == "foo v")
        }

        "empty doc argument" in {
            assert(Printer.greedy.print(doc"a ${Doc.empty}b", 80) == "a b")
        }
    }

    "margin stripping" - {
        "baseline indentation is stripped" in {
            val d = doc"""
                def foo: Unit =
                    body
                """
            assert(Printer.greedy.print(d, 80) == "def foo: Unit =\n    body")
        }

        "minimum indentation across lines defines the baseline" in {
            val d = doc"""
                  deep
                shallow
                """
            assert(Printer.greedy.print(d, 80) == "  deep\nshallow")
        }

        "border blank lines are dropped" in {
            val d = doc"""
                a
                """
            assert(Printer.greedy.print(d, 80) == "a")
        }

        "only the newline closing the first blank line is dropped" in {
            val d = doc"""

                a
                """
            assert(Printer.greedy.print(d, 80) == "\na")
        }

        "interior blank lines are preserved" in {
            val d = doc"""
                a

                b
                """
            assert(Printer.greedy.print(d, 80) == "a\n\nb")
        }

        "blank lines do not affect the baseline" in {
            val d = doc"""
                a

                  b
                """
            assert(Printer.greedy.print(d, 80) == "a\n\n  b")
        }

        "trailing whitespace is stripped per line" in {
            val d = doc"foo   \nbar   "
            assert(Printer.greedy.print(d, 80) == "foo\nbar")
        }

        "compact printer keeps hard line breaks without indentation" in {
            val d = doc"""
                a
                b
                """
            assert(Printer.compact.print(d, 80) == "a\nb")
        }
    }

    "embedded multiline values" - {
        "doc continuation lines align to the insertion column" in {
            val body = Doc.stack(Doc.text("a"), Doc.text("b"))
            val d    = doc"""
                prefix $body
                """
            assert(Printer.greedy.print(d, 80) == "prefix a\n       b")
        }

        "string continuation lines align to the insertion column" in {
            val d = doc"""
                prefix ${"a\nb"}
                """
            assert(Printer.greedy.print(d, 80) == "prefix a\n       b")
        }

        "argument on its own line keeps the stripped indentation" in {
            val body = Doc.text("x")
            val d    = doc"""
                start
                    $body
                end
                """
            assert(Printer.greedy.print(d, 80) == "start\n    x\nend")
        }
    }

    "code generation template" - {
        val params = Chunk(Doc.text("a: Int"), Doc.text("b: String")).chop(Doc.text("("), Doc.text(", "), Doc.text(")"))

        "chop breaks relative to the insertion column" in {
            val d = doc"def foo${params}: Unit"
            assert(Printer.greedy.print(d, 80) == "def foo(a: Int, b: String): Unit")
            assert(Printer.greedy.print(d, 30) ==
                "def foo(\n           a: Int,\n           b: String\n       ): Unit")
        }

        "full method template" in {
            val name = "foo"
            val body = Doc.stack(Doc.text("stmt1"), Doc.text("stmt2"))
            val one  = Chunk(Doc.text("a: Int")).chop(Doc.text("("), Doc.text(", "), Doc.text(")"))
            val d    = doc"""
                def $name${one}: Unit =
                    $body
                """
            assert(Printer.greedy.print(d, 30) == "def foo(a: Int): Unit =\n    stmt1\n    stmt2")
        }
    }

end DocInterpolationTest
