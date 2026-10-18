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

            "single column alignment pads to column boundary" in {
                val doc = Doc.align { col =>
                    Doc.concat(
                        Doc.text("val a "),
                        col,
                        Doc.text("= 1"),
                        Doc.newline,
                        Doc.text("val longerName "),
                        col,
                        Doc.text("= 2")
                    )
                }
                assert(Printer.greedy.print(doc, 80) == "val a          = 1\nval longerName = 2")
            }

            "single column with offset adds padding" in {
                val doc = Doc.align { col =>
                    Doc.concat(
                        Doc.text("val a "),
                        col.indent(2),
                        Doc.text("= 1"),
                        Doc.newline,
                        Doc.text("val longerName "),
                        col.indent(2),
                        Doc.text("= 2")
                    )
                }
                assert(Printer.greedy.print(doc, 80) == "val a            = 1\nval longerName   = 2")
            }

            "multi-column alignment aligns table columns" in {
                val doc = Doc.align(2) { cols =>
                    Doc.concat(
                        Doc.text("a"),
                        cols(0),
                        Doc.text("b"),
                        cols(1),
                        Doc.text("c"),
                        Doc.newline,
                        Doc.text("long"),
                        cols(0),
                        Doc.text("longer"),
                        cols(1),
                        Doc.text("d")
                    )
                }
                assert(Printer.greedy.print(doc, 80) == "a   b     c\nlonglongerd")
            }

            "marker following newline aligns continuation line" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align { col =>
                        Doc.text("line1").append(Doc.newline).append(col).append(Doc.text("line2"))
                    }
                )
                assert(Printer.greedy.print(doc, 80) == "prefix: line1\n        line2")
            }

            "marker with offset following newline indents continuation line" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align { col =>
                        Doc.text("line1").append(Doc.newline).append(col.indent(4)).append(Doc.text("line2"))
                    }
                )
                assert(Printer.greedy.print(doc, 80) == "prefix: line1\n            line2")
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

            "scoped group choice all flat when group fits" in {
                val doc = Doc.group { choice =>
                    Doc.concat(
                        Doc.text("call("),
                        choice.spaceOrNewline,
                        Doc.text("arg1,"),
                        choice.spaceOrNewline,
                        Doc.text("arg2)")
                    )
                }
                assert(Printer.greedy.print(doc, 30) == "call( arg1, arg2)")
            }

            "scoped group choice all broken when group overflows" in {
                val doc = Doc.group { choice =>
                    Doc.concat(
                        Doc.text("call("),
                        choice(Doc.empty, Doc.newline.indent(4)),
                        Doc.text("arg1,"),
                        choice(Doc.space, Doc.newline.indent(4)),
                        Doc.text("arg2)")
                    )
                }
                assert(Printer.greedy.print(doc, 10) == "call(\n    arg1,\n    arg2)")
            }

            "nested scoped groups coordinate choices independently" in {
                val doc = Doc.group { outerChoice =>
                    Doc.concat(
                        Doc.text("outer("),
                        outerChoice(Doc.empty, Doc.newline.indent(2)),
                        Doc.group { innerChoice =>
                            Doc.concat(
                                Doc.text("inner("),
                                innerChoice(Doc.empty, Doc.newline.indent(4)),
                                Doc.text("x, y)")
                            )
                        },
                        outerChoice(Doc.empty, Doc.newline),
                        Doc.text(")")
                    )
                }
                // When width is 15, outer breaks, but inner("inner(x, y)") is 11 chars so it fits flat:
                assert(Printer.greedy.print(doc, 15) == "outer(\n  inner(x, y)\n)")
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

            "ignores column alignment markers" in {
                val doc = Doc.align(2) { cols =>
                    Doc.concat(
                        Doc.text("val a "),
                        cols(0),
                        Doc.text("= 1"),
                        Doc.newline,
                        Doc.text("val longerName "),
                        cols(0),
                        Doc.text("= 2")
                    )
                }
                assert(Printer.compact.print(doc, 80) == "val a = 1 val longerName = 2")
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

            "scoped group choice selects flat alternative" in {
                val doc = Doc.group { choice =>
                    Doc.concat(
                        Doc.text("call("),
                        choice(Doc.empty, Doc.newline.indent(4)),
                        Doc.text("arg1,"),
                        choice.spaceOrNewline,
                        Doc.text("arg2)")
                    )
                }
                assert(Printer.compact.print(doc, 1) == "call(arg1, arg2)")
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

            "single column alignment pads to column boundary" in {
                val doc = Doc.align { col =>
                    Doc.concat(
                        Doc.text("val a "),
                        col,
                        Doc.text("= 1"),
                        Doc.newline,
                        Doc.text("val longerName "),
                        col,
                        Doc.text("= 2")
                    )
                }
                assert(Printer.optimal.print(doc, 80) == "val a          = 1\nval longerName = 2")
            }

            "multi-column alignment aligns table columns" in {
                val doc = Doc.align(2) { cols =>
                    Doc.concat(
                        Doc.text("a"),
                        cols(0),
                        Doc.text("b"),
                        cols(1),
                        Doc.text("c"),
                        Doc.newline,
                        Doc.text("long"),
                        cols(0),
                        Doc.text("longer"),
                        cols(1),
                        Doc.text("d")
                    )
                }
                assert(Printer.optimal.print(doc, 80) == "a   b     c\nlonglongerd")
            }

            "marker following newline aligns continuation line" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align { col =>
                        Doc.text("line1").append(Doc.newline).append(col).append(Doc.text("line2"))
                    }
                )
                assert(Printer.optimal.print(doc, 80) == "prefix: line1\n        line2")
            }

            "marker with offset following newline indents continuation line" in {
                val doc = Doc.text("prefix: ").append(
                    Doc.align { col =>
                        Doc.text("line1").append(Doc.newline).append(col.indent(4)).append(Doc.text("line2"))
                    }
                )
                assert(Printer.optimal.print(doc, 80) == "prefix: line1\n            line2")
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

            "scoped group choice all flat when group fits" in {
                val doc = Doc.group { choice =>
                    Doc.concat(
                        Doc.text("call("),
                        choice.spaceOrNewline,
                        Doc.text("arg1,"),
                        choice.spaceOrNewline,
                        Doc.text("arg2)")
                    )
                }
                assert(Printer.optimal.print(doc, 30) == "call( arg1, arg2)")
            }

            "scoped group choice all broken when group overflows" in {
                val doc = Doc.group { choice =>
                    Doc.concat(
                        Doc.text("call("),
                        choice(Doc.empty, Doc.newline.indent(4)),
                        Doc.text("arg1,"),
                        choice(Doc.space, Doc.newline.indent(4)),
                        Doc.text("arg2)")
                    )
                }
                assert(Printer.optimal.print(doc, 10) == "call(\n    arg1,\n    arg2)")
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

    "Multi-column alignment and Markdown table fixture" - {
        import MarkdownTableFixture.*

        def verifyTableDelimiters(rendered: String)(using kyo.test.AssertScope): Unit =
            val lines = rendered.split('\n')
            assert(lines.nonEmpty)
            val pipeIndices = lines.map(line =>
                line.zipWithIndex.collect { case ('|', idx) => idx }.toSeq
            )
            val firstIndices = pipeIndices.head
            assert(firstIndices.length >= 2)
            for indices <- pipeIndices do
                assert(indices == firstIndices)
        end verifyTableDelimiters

        "basic markdown table generates vertically aligned columns" in {
            val headers = Chunk("Package", "Version", "Status")
            val rows    = Chunk(
                Chunk("kyo-core", "0.1.0", "stable"),
                Chunk("kyo-prelude", "0.1.0", "active"),
                Chunk("kyo-print", "0.1.0-SNAPSHOT", "experimental")
            )
            val doc      = MarkdownTableFixture.table(headers, rows)
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "| Package     | Version        | Status       |",
                "| :---        | :---           | :---         |",
                "| kyo-core    | 0.1.0          | stable       |",
                "| kyo-prelude | 0.1.0          | active       |",
                "| kyo-print   | 0.1.0-SNAPSHOT | experimental |"
            ).mkString("\n")

            assert(rendered == expected)
            verifyTableDelimiters(rendered)
        }

        "markdown table with column alignments (Left, Center, Right)" in {
            val columns = Chunk(
                Column("Left", ColumnAlign.Left),
                Column("Center", ColumnAlign.Center),
                Column("Right", ColumnAlign.Right)
            )
            val rows = Chunk(
                Chunk(Doc.text("a"), Doc.text("b"), Doc.text("c")),
                Chunk(Doc.text("longer text"), Doc.text("medium"), Doc.text("short"))
            )
            val doc      = MarkdownTableFixture.table(columns, rows)
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "| Left        | Center | Right |",
                "| :---        | :---:  | ---:  |",
                "| a           | b      | c     |",
                "| longer text | medium | short |"
            ).mkString("\n")

            assert(rendered == expected)
            verifyTableDelimiters(rendered)
        }

        "markdown table with ragged/uneven rows pads missing cells" in {
            val headers = Chunk("Col1", "Col2", "Col3")
            val rows    = Chunk(
                Chunk("only-col1"),
                Chunk("first", "second"),
                Chunk("a", "b", "c")
            )
            val doc      = MarkdownTableFixture.table(headers, rows)
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "| Col1      | Col2   | Col3 |",
                "| :---      | :---   | :--- |",
                "| only-col1 |        |      |",
                "| first     | second |      |",
                "| a         | b      | c    |"
            ).mkString("\n")

            assert(rendered == expected)
            verifyTableDelimiters(rendered)
        }

        "markdown table with rich Doc content" in {
            val headers = Chunk(
                Column(Doc.text("**Identifier**")),
                Column(Doc.text("`Type`")),
                Column(Doc.text("*Description*"))
            )
            val rows = Chunk(
                Chunk(Doc.text("id"), Doc.text("Int"), Doc.text("Unique identifier")),
                Chunk(Doc.text("payload"), Doc.text("Chunk[Byte]"), Doc.text("Raw payload"))
            )
            val doc      = MarkdownTableFixture.table(headers, rows)
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "| **Identifier** | `Type`      | *Description*     |",
                "| :---           | :---        | :---              |",
                "| id             | Int         | Unique identifier |",
                "| payload        | Chunk[Byte] | Raw payload       |"
            ).mkString("\n")

            assert(rendered == expected)
            verifyTableDelimiters(rendered)
        }

        "indented markdown table preserves outer indentation and inner column alignment" in {
            val headers = Chunk("A", "B")
            val rows    = Chunk(
                Chunk("val1", "val2"),
                Chunk("longerVal1", "v2")
            )
            val tableDoc = MarkdownTableFixture.table(headers, rows)
            val doc      = Doc.text("Summary:").append(Doc.newline).append(tableDoc.indent(4))
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "Summary:",
                "    | A          | B    |",
                "    | :---       | :--- |",
                "    | val1       | val2 |",
                "    | longerVal1 | v2   |"
            ).mkString("\n")

            assert(rendered == expected)
            val tablePart = rendered.linesIterator.drop(1).mkString("\n")
            verifyTableDelimiters(tablePart)
        }

        "multiple independent tables maintain isolated column measurements" in {
            val t1 = MarkdownTableFixture.table(
                Chunk("X", "Y"),
                Chunk(Chunk("short", "1"), Chunk("med", "2"))
            )
            val t2 = MarkdownTableFixture.table(
                Chunk("SuperLongColumnHeader", "Z"),
                Chunk(Chunk("a", "b"))
            )
            val doc = Doc.stack(
                Doc.text("Table 1:"),
                t1,
                Doc.empty,
                Doc.text("Table 2:"),
                t2
            )
            val rendered = Printer.greedy.print(doc, 100)

            val expected = Seq(
                "Table 1:",
                "| X     | Y    |",
                "| :---  | :--- |",
                "| short | 1    |",
                "| med   | 2    |",
                "",
                "Table 2:",
                "| SuperLongColumnHeader | Z    |",
                "| :---                  | :--- |",
                "| a                     | b    |"
            ).mkString("\n")

            assert(rendered == expected)
        }

        "empty table handling" in {
            val emptyCols = MarkdownTableFixture.table(Chunk.empty[String], Chunk.empty[Chunk[String]])
            assert(Printer.greedy.print(emptyCols, 80) == "")

            val headersOnly = MarkdownTableFixture.table(Chunk("A", "B"), Chunk.empty[Chunk[String]])
            val rendered    = Printer.greedy.print(headersOnly, 80)
            val expected    = Seq(
                "| A    | B    |",
                "| :--- | :--- |"
            ).mkString("\n")
            assert(rendered == expected)
        }

        "parity across greedy, optimal, and scalafmt printer engines" in {
            val headers = Chunk("Item", "Qty", "Cost")
            val rows    = Chunk(
                Chunk("Widget", "5", "$10.00"),
                Chunk("Super Gadget Deluxe", "100", "$1,250.00"),
                Chunk("Part", "1", "$0.50")
            )
            val doc     = MarkdownTableFixture.table(headers, rows)
            val greedy  = Printer.greedy.print(doc, 100)
            val optimal = Printer.optimal.print(doc, 100)
            val fmt     = Printer.scalafmt.print(doc, 100)

            assert(greedy == optimal)
            assert(greedy == fmt)
            verifyTableDelimiters(greedy)
        }

        "streaming printStream produces identical output to buffered print" in {
            val headers = Chunk("Field", "Type")
            val rows    = Chunk(
                Chunk("name", "String"),
                Chunk("age", "Int"),
                Chunk("attributes", "Map[String, Any]")
            )
            val doc      = MarkdownTableFixture.table(headers, rows)
            val expected = Printer.greedy.print(doc, 100)

            val greedyStream  = Printer.greedy.printStream(doc, 100).run.eval.mkString
            val optimalStream = Printer.optimal.printStream(doc, 100).run.eval.mkString

            assert(greedyStream == expected)
            assert(optimalStream == expected)
        }

        "compact printer handles markdown table gracefully" in {
            val headers  = Chunk("A", "B")
            val rows     = Chunk(Chunk("1", "2"))
            val doc      = MarkdownTableFixture.table(headers, rows)
            val rendered = Printer.compact.print(doc, 80)
            assert(rendered.contains("| A | B |"))
            assert(rendered.contains("| :--- | :--- |"))
            assert(rendered.contains("| 1 | 2 |"))
        }
    }

end PrinterTest

object MarkdownTableFixture:

    enum ColumnAlign derives CanEqual:
        case Left, Center, Right

    final case class Column(title: Doc, align: ColumnAlign = ColumnAlign.Left)

    object Column:
        def apply(title: String): Column                     = Column(Doc.text(title), ColumnAlign.Left)
        def apply(title: String, align: ColumnAlign): Column = Column(Doc.text(title), align)

    def table(
        columns: Chunk[Column],
        rows: Chunk[Chunk[Doc]]
    ): Doc =
        val numCols = math.max(columns.length, rows.foldLeft(0)((m, r) => math.max(m, r.length)))
        if numCols <= 0 then Doc.empty
        else
            Doc.align(numCols) { cols =>
                def formatRow(cells: Chunk[Doc]): Doc =
                    val cellDocs = Chunk.tabulate(numCols) { i =>
                        val content = if i < cells.length then cells(i) else Doc.empty
                        Doc.concat(Doc.space, content, Doc.space, cols(i))
                    }
                    Doc.concat(Doc.text("|"), Doc.join(Doc.text("|"))(cellDocs), Doc.text("|"))
                end formatRow

                val headerCells = Chunk.tabulate(numCols) { i =>
                    if i < columns.length then columns(i).title else Doc.empty
                }

                val sepCells = Chunk.tabulate(numCols) { i =>
                    val alignment = if i < columns.length then columns(i).align else ColumnAlign.Left
                    val marker    = alignment match
                        case ColumnAlign.Left   => Doc.text(":---")
                        case ColumnAlign.Center => Doc.text(":---:")
                        case ColumnAlign.Right  => Doc.text("---:")
                    Doc.concat(Doc.space, marker, Doc.space, cols(i))
                }
                val sepRow = Doc.concat(Doc.text("|"), Doc.join(Doc.text("|"))(sepCells), Doc.text("|"))

                val headerRow = formatRow(headerCells)
                val dataRows  = rows.map(formatRow)
                Doc.stack(headerRow +: sepRow +: dataRows)
            }
        end if
    end table

    @scala.annotation.targetName("tableStrings")
    def table(
        headers: Chunk[String],
        rows: Chunk[Chunk[String]]
    ): Doc =
        table(
            headers.map(h => Column(Doc.text(h), ColumnAlign.Left)),
            rows.map(r => r.map(Doc.text))
        )

end MarkdownTableFixture
