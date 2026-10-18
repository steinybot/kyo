package kyo

class PrinterTest extends kyo.test.Test[Any]:

    val dummyPrinter = new Printer:
        def print(doc: Doc, width: Int): String =
            s"width=$width"

        def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
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

    "Printer.greedy" - {
        "primitives" - {
            "empty document" in {
                assert(Printer.greedy.print(Doc.empty, 80) == "")
            }

            "literal text" in {
                assert(Printer.greedy.print(Doc.text("hello"), 80) == "hello")
            }

            "concatenation" in {
                val doc = Doc.text("hello").append(Doc.space).append(Doc.text("world"))
                assert(Printer.greedy.print(doc, 80) == "hello world")
            }

            "newline in break mode emits line break" in {
                val doc = Doc.text("hello").append(Doc.newline).append(Doc.text("world"))
                assert(Printer.greedy.print(doc, 80) == "hello\nworld")
            }

            "hardNewline always breaks even in flat group" in {
                val doc = Doc.group(Doc.text("a").append(Doc.hardNewline).append(Doc.text("b")))
                assert(Printer.greedy.print(doc, 80) == "a\nb")
            }
        }

        "indentation" - {
            "nested indentation increases margin" in {
                val doc = Doc.text("parent:").append(
                    Doc.newline.append(Doc.text("child")).indent(4)
                )
                assert(Printer.greedy.print(doc, 80) == "parent:\n    child")
            }

            "additive indentation stacks across levels" in {
                val doc = Doc.text("level0:").append(
                    Doc.newline.append(Doc.text("level1:")).append(
                        Doc.newline.append(Doc.text("level2")).indent(2)
                    ).indent(2)
                )
                assert(Printer.greedy.print(doc, 80) == "level0:\n  level1:\n    level2")
            }

            "zero-trailing-whitespace on empty lines" in {
                val doc = Doc.text("a").append(Doc.newline).append(Doc.newline.indent(4)).append(
                    Doc.text("b").indent(4)
                )
                assert(Printer.greedy.print(doc, 80) == "a\n\n    b")
            }

            "trailing newline produces no trailing spaces" in {
                val doc = Doc.text("a").append(Doc.newline).indent(4)
                assert(Printer.greedy.print(doc, 80) == "a\n")
            }
        }

        "alignment" - {
            "aligns line breaks to current column" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align(Doc.text("line1").append(Doc.newline).append(Doc.text("line2")))
                )
                assert(Printer.greedy.print(doc, 80) == "prefix: line1\n        line2")
            }

            "nested alignment with indentation" in {
                val doc = Doc.text("fn(").append(
                    Doc.align(
                        Doc.text("a").append(Doc.newline).append(Doc.text("b").indent(2))
                    )
                )
                assert(Printer.greedy.print(doc, 80) == "fn(a\n     b")
            }
        }

        "grouping and lookahead" - {
            "flattens when within width limit" in {
                val doc = Doc.group(Doc.text("hello").append(Doc.newline).append(Doc.text("world")))
                assert(Printer.greedy.print(doc, 20) == "hello world")
            }

            "breaks when exceeding width limit" in {
                val doc = Doc.group(Doc.text("hello").append(Doc.newline).append(Doc.text("world")))
                assert(Printer.greedy.print(doc, 10) == "hello\nworld")
            }

            "width boundaries" - {
                val doc = Doc.group(Doc.text("12345").append(Doc.newline).append(Doc.text("67890")))
                // Flat length is 5 + 1 + 5 = 11

                "exact fit at 11 columns remains flat" in {
                    assert(Printer.greedy.print(doc, 11) == "12345 67890")
                }

                "1-character overflow at 10 columns breaks" in {
                    assert(Printer.greedy.print(doc, 10) == "12345\n67890")
                }

                "generous width at 12 columns remains flat" in {
                    assert(Printer.greedy.print(doc, 12) == "12345 67890")
                }
            }

            "nested groups selectively break" in {
                val inner = Doc.group(Doc.text("inner1").append(Doc.newline).append(Doc.text("inner2")))
                val doc   = Doc.group(Doc.text("outer1").append(Doc.newline).append(inner).append(Doc.newline).append(Doc.text("outer2")))

                // All flat: "outer1 inner1 inner2 outer2" length is 27
                assert(Printer.greedy.print(doc, 30) == "outer1 inner1 inner2 outer2")

                // Outer breaks (exceeds 20), inner remains flat ("inner1 inner2" is 13 <= 20)
                assert(Printer.greedy.print(doc, 20) == "outer1\ninner1 inner2\nouter2")

                // Both break (exceeds 10)
                assert(Printer.greedy.print(doc, 10) == "outer1\ninner1\ninner2\nouter2")
            }

            "lookahead considers trailing text on same line" in {
                // If group is flat, trailing "!" is on the same line: "hello world!" is 12 chars
                val doc = Doc.group(Doc.text("hello").append(Doc.newline).append(Doc.text("world"))).append(Doc.text("!"))

                assert(Printer.greedy.print(doc, 12) == "hello world!")
                assert(Printer.greedy.print(doc, 11) == "hello\nworld!")
            }
        }

        "choice" - {
            "selects flat alternative when group fits" in {
                val doc = Doc.group(Doc.choice(flat = Doc.text("flat"), broken = Doc.text("broken")))
                assert(Printer.greedy.print(doc, 10) == "flat")
            }

            "selects broken alternative when group overflows" in {
                val doc = Doc.group(Doc.choice(flat = Doc.text("flat"), broken = Doc.text("broken")))
                assert(Printer.greedy.print(doc, 3) == "broken")
            }

            "selects broken alternative outside group" in {
                val doc = Doc.choice(flat = Doc.text("flat"), broken = Doc.text("broken"))
                assert(Printer.greedy.print(doc, 80) == "broken")
            }
        }

        "combinators" - {
            val items = Chunk(Doc.text("a"), Doc.text("b"), Doc.text("c"))

            "chop formats flat when within width" in {
                val doc = Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(items)
                assert(Printer.greedy.print(doc, 20) == "(a, b, c)")
            }

            "chop formats multiline when exceeding width" in {
                val doc      = Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(items)
                val expected =
                    """(
                      |    a,
                      |    b,
                      |    c
                      |)""".stripMargin
                assert(Printer.greedy.print(doc, 5) == expected)
            }

            "chopClosed formats multiline with closed delimiter" in {
                val doc      = Doc.chopClosed(Doc.text("("), Doc.text(", "), Doc.text(")"))(items)
                val expected =
                    """(
                      |    a,
                      |    b,
                      |    c)""".stripMargin
                assert(Printer.greedy.print(doc, 5) == expected)
            }

            "hang indents continuation lines to delimiter width" in {
                val doc      = Doc.hang(Doc.text("call("), Doc.text(", "), Doc.text(")"))(items)
                val expected =
                    """call(a,
                      |     b,
                      |     c)""".stripMargin
                assert(Printer.greedy.print(doc, 5) == expected)
            }

            "wrap packs elements onto lines as budget allows" in {
                val doc = Doc.wrap(Doc.text("["), Doc.text(", "), Doc.text("]"))(
                    Doc.text("11"),
                    Doc.text("22"),
                    Doc.text("33"),
                    Doc.text("44")
                )
                // Width 10: "[11, 22,\n 33, 44]"
                val result = Printer.greedy.print(doc, 10)
                assert(result == "[11, 22,\n 33, 44]")
            }
        }

        "streaming" - {
            "empty document produces empty stream" in {
                val stream = Printer.greedy.printStream(Doc.empty, 80)
                assert(stream.run.eval == Chunk.empty)
            }

            "single line document produces single element stream" in {
                val stream = Printer.greedy.printStream(Doc.text("hello"), 80)
                assert(stream.run.eval == Chunk("hello"))
            }

            "multi-line document chunks by line" in {
                val doc    = Doc.text("a").append(Doc.newline).append(Doc.text("b"))
                val stream = Printer.greedy.printStream(doc, 80)
                assert(stream.run.eval == Chunk("a\n", "b"))
            }

            "stream output equals buffered print output across document shapes" in {
                val docs = Seq(
                    Doc.empty,
                    Doc.text("simple single line"),
                    Doc.text("a").append(Doc.newline).append(Doc.text("b")),
                    Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y")),
                    Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y")),
                    Doc.text("prefix: ").append(Doc.align(Doc.text("1").append(Doc.newline).append(Doc.text("2"))))
                )

                for
                    d <- docs
                    w <- Seq(5, 10, 80)
                do
                    val expected = Printer.greedy.print(d, w)
                    val streamed = Printer.greedy.printStream(d, w).run.eval.mkString
                    assert(streamed == expected)
                end for
            }
        }
    }

    "Printer.compact" - {
        "primitives" - {
            "empty document" in {
                assert(Printer.compact.print(Doc.empty, 80) == "")
            }

            "literal text" in {
                assert(Printer.compact.print(Doc.text("hello"), 80) == "hello")
            }

            "concatenation" in {
                val doc = Doc.text("hello").append(Doc.space).append(Doc.text("world"))
                assert(Printer.compact.print(doc, 80) == "hello world")
            }

            "newline collapses to single space" in {
                val doc = Doc.text("hello").append(Doc.newline).append(Doc.text("world"))
                assert(Printer.compact.print(doc, 80) == "hello world")
            }

            "hardNewline preserves line break without indentation" in {
                val doc = Doc.text("hello").append(Doc.hardNewline).append(Doc.text("world"))
                assert(Printer.compact.print(doc, 80) == "hello\nworld")
            }
        }

        "indentation and alignment ignored" - {
            "ignores indentation" in {
                val doc = Doc.text("parent:").append(
                    Doc.newline.append(Doc.text("child")).indent(4)
                )
                assert(Printer.compact.print(doc, 80) == "parent: child")
            }

            "ignores indentation with hardNewline" in {
                val doc = Doc.text("line1").append(
                    Doc.hardNewline.append(Doc.text("line2")).indent(4)
                )
                assert(Printer.compact.print(doc, 80) == "line1\nline2")
            }

            "ignores alignment" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align(Doc.text("line1").append(Doc.newline).append(Doc.text("line2")))
                )
                assert(Printer.compact.print(doc, 80) == "prefix: line1 line2")
            }
        }

        "grouping and choice" - {
            "group remains flat" in {
                val doc = Doc.group(Doc.text("hello").append(Doc.newline).append(Doc.text("world")))
                assert(Printer.compact.print(doc, 1) == "hello world")
            }

            "choice selects flat alternative" in {
                val doc = Doc.choice(flat = Doc.text("flat"), broken = Doc.text("broken"))
                assert(Printer.compact.print(doc, 80) == "flat")
            }

            "nested choice selects flat alternative" in {
                val doc = Doc.choice(
                    flat = Doc.choice(flat = Doc.text("innerFlat"), broken = Doc.text("innerBroken")),
                    broken = Doc.text("outerBroken")
                )
                assert(Printer.compact.print(doc, 80) == "innerFlat")
            }
        }

        "combinators" - {
            val items = Chunk(Doc.text("a"), Doc.text("b"), Doc.text("c"))

            "chop formats flat with separators" in {
                val doc = Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(items)
                assert(Printer.compact.print(doc, 5) == "(a, b, c)")
            }

            "chopClosed formats flat with delimiters" in {
                val doc = Doc.chopClosed(Doc.text("("), Doc.text(", "), Doc.text(")"))(items)
                assert(Printer.compact.print(doc, 5) == "(a, b, c)")
            }

            "hang formats flat" in {
                val doc = Doc.hang(Doc.text("call("), Doc.text(", "), Doc.text(")"))(items)
                assert(Printer.compact.print(doc, 5) == "call(a, b, c)")
            }

            "stack collapses lines to single spaces" in {
                val doc = Doc.stack(items)
                assert(Printer.compact.print(doc, 80) == "a b c")
            }

            "wrap formats flat" in {
                val doc = Doc.wrap(Doc.text("["), Doc.text(", "), Doc.text("]"))(
                    Doc.text("11"),
                    Doc.text("22"),
                    Doc.text("33")
                )
                assert(Printer.compact.print(doc, 5) == "[11, 22, 33]")
            }
        }

        "streaming" - {
            "empty document produces empty stream" in {
                val stream = Printer.compact.printStream(Doc.empty, 80)
                assert(stream.run.eval == Chunk.empty)
            }

            "single line document produces single element stream" in {
                val doc    = Doc.text("hello").append(Doc.newline).append(Doc.text("world"))
                val stream = Printer.compact.printStream(doc, 80)
                assert(stream.run.eval == Chunk("hello world"))
            }

            "document with hardNewline chunks by line" in {
                val doc    = Doc.text("a").append(Doc.hardNewline).append(Doc.text("b"))
                val stream = Printer.compact.printStream(doc, 80)
                assert(stream.run.eval == Chunk("a\n", "b"))
            }

            "stream output equals buffered print output across document shapes" in {
                val docs = Seq(
                    Doc.empty,
                    Doc.text("simple single line"),
                    Doc.text("a").append(Doc.newline).append(Doc.text("b")),
                    Doc.text("a").append(Doc.hardNewline).append(Doc.text("b")),
                    Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y")),
                    Doc.text("prefix: ").append(Doc.align(Doc.text("1").append(Doc.newline).append(Doc.text("2"))))
                )

                for
                    d <- docs
                    w <- Seq(5, 10, 80)
                do
                    val expected = Printer.compact.print(d, w)
                    val streamed = Printer.compact.printStream(d, w).run.eval.mkString
                    assert(streamed == expected)
                end for
            }
        }
    }

    "Printer.optimal" - {
        "primitives" - {
            "empty document" in {
                assert(Printer.optimal.print(Doc.empty, 80) == "")
            }

            "literal text" in {
                assert(Printer.optimal.print(Doc.text("hello"), 80) == "hello")
            }

            "concatenation" in {
                val doc = Doc.text("hello").append(Doc.space).append(Doc.text("world"))
                assert(Printer.optimal.print(doc, 80) == "hello world")
            }

            "newline in break mode emits line break" in {
                val doc = Doc.text("hello").append(Doc.newline).append(Doc.text("world"))
                assert(Printer.optimal.print(doc, 80) == "hello\nworld")
            }

            "hardNewline always breaks even in flat group" in {
                val doc = Doc.group(Doc.text("a").append(Doc.hardNewline).append(Doc.text("b")))
                assert(Printer.optimal.print(doc, 80) == "a\nb")
            }
        }

        "indentation" - {
            "nested indentation increases margin" in {
                val doc = Doc.text("parent:").append(
                    Doc.newline.append(Doc.text("child")).indent(4)
                )
                assert(Printer.optimal.print(doc, 80) == "parent:\n    child")
            }

            "additive indentation stacks across levels" in {
                val doc = Doc.text("level0:").append(
                    Doc.newline.append(Doc.text("level1:")).append(
                        Doc.newline.append(Doc.text("level2")).indent(2)
                    ).indent(2)
                )
                assert(Printer.optimal.print(doc, 80) == "level0:\n  level1:\n    level2")
            }

            "zero-trailing-whitespace on empty lines" in {
                val doc = Doc.text("a").append(Doc.newline).append(Doc.newline.indent(4)).append(
                    Doc.text("b").indent(4)
                )
                assert(Printer.optimal.print(doc, 80) == "a\n\n    b")
            }

            "trailing newline produces no trailing spaces" in {
                val doc = Doc.text("a").append(Doc.newline).indent(4)
                assert(Printer.optimal.print(doc, 80) == "a\n")
            }
        }

        "alignment" - {
            "aligns line breaks to current column" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align(Doc.text("line1").append(Doc.newline).append(Doc.text("line2")))
                )
                assert(Printer.optimal.print(doc, 80) == "prefix: line1\n        line2")
            }

            "nested alignment with indentation" in {
                val doc = Doc.text("fn(").append(
                    Doc.align(
                        Doc.text("a").append(Doc.newline).append(Doc.text("b").indent(2))
                    )
                )
                assert(Printer.optimal.print(doc, 80) == "fn(a\n     b")
            }
        }

        "choice and optimality" - {
            "evaluates multi-alternative choice selecting best fit" in {
                val doc = Doc.choice(
                    Doc.text("long alternative that exceeds width"),
                    Doc.text("medium alt"),
                    Doc.text("short")
                )
                assert(Printer.optimal.print(doc, 15) == "medium alt")
                assert(Printer.optimal.print(doc, 8) == "short")
                assert(Printer.optimal.print(doc, 50) == "long alternative that exceeds width")
            }

            "multi-choice tree selects globally optimal layout" in {
                // Testing multiple choices where optimal avoids greedy pitfall
                val doc = Doc.choice(
                    Doc.text("first-option-long").append(Doc.space).append(
                        Doc.choice(Doc.text("second-long"), Doc.text("s2"))
                    ),
                    Doc.text("first-short").append(Doc.space).append(
                        Doc.choice(Doc.text("second-long-choice"), Doc.text("s2"))
                    )
                )
                // Width 25: "first-short second-long-choice" has length 11 + 1 + 18 = 30 (overflow)
                // "first-short s2" length 11 + 1 + 2 = 14
                // "first-option-long s2" length 17 + 1 + 2 = 20 <= 25 (fits!)
                // "first-option-long second-long" length 17 + 1 + 11 = 29
                assert(Printer.optimal.print(doc, 22) == "first-option-long s2")
            }
        }

        "equivalence with greedy on standard single-choice shapes" - {
            val docs = Seq(
                ("empty", Doc.empty),
                ("single line", Doc.text("simple single line")),
                ("lines", Doc.text("a").append(Doc.newline).append(Doc.text("b"))),
                ("group", Doc.group(Doc.text("hello").append(Doc.newline).append(Doc.text("world")))),
                ("chop", Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y"))),
                ("chopClosed", Doc.chopClosed(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y"))),
                ("hang", Doc.hang(Doc.text("fn("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y"))),
                ("stack", Doc.stack(Doc.text("line 1"), Doc.text("line 2"), Doc.text("line 3"))),
                ("align", Doc.text("prefix: ").append(Doc.align(Doc.text("1").append(Doc.newline).append(Doc.text("2")))))
            )

            for
                (name, d) <- docs
                w         <- Seq(5, 10, 15, 80)
            do
                s"shape $name at width $w" in {
                    val greedyResult  = Printer.greedy.print(d, w)
                    val optimalResult = Printer.optimal.print(d, w)
                    assert(optimalResult == greedyResult)
                }
            end for
        }

        "streaming" - {
            "empty document produces empty stream" in {
                val stream = Printer.optimal.printStream(Doc.empty, 80)
                assert(stream.run.eval == Chunk.empty)
            }

            "single line document produces single element stream" in {
                val stream = Printer.optimal.printStream(Doc.text("hello"), 80)
                assert(stream.run.eval == Chunk("hello"))
            }

            "multi-line document chunks by line" in {
                val doc    = Doc.text("a").append(Doc.newline).append(Doc.text("b"))
                val stream = Printer.optimal.printStream(doc, 80)
                assert(stream.run.eval == Chunk("a\n", "b"))
            }

            "stream output equals buffered print output across document shapes" in {
                val docs = Seq(
                    Doc.empty,
                    Doc.text("simple single line"),
                    Doc.text("a").append(Doc.newline).append(Doc.text("b")),
                    Doc.chop(Doc.text("("), Doc.text(", "), Doc.text(")"))(Doc.text("x"), Doc.text("y")),
                    Doc.text("prefix: ").append(Doc.align(Doc.text("1").append(Doc.newline).append(Doc.text("2"))))
                )

                for
                    d <- docs
                    w <- Seq(5, 10, 80)
                do
                    val expected = Printer.optimal.print(d, w)
                    val streamed = Printer.optimal.printStream(d, w).run.eval.mkString
                    assert(streamed == expected)
                end for
            }
        }
    }

end PrinterTest
