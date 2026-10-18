package kyo

class DocTest extends kyo.test.Test[Any]:

    val a = Doc.text("a")
    val b = Doc.text("b")
    val c = Doc.text("c")

    "ADT nodes" - {
        "Empty" in {
            assert(Doc.Empty == Doc.empty)
        }

        "Text" in {
            val t = Doc.Text("hello")
            assert(t.text == "hello")
        }

        "Newline" in {
            assert(Doc.Newline == Doc.newline)
        }

        "HardNewline" in {
            assert(Doc.HardNewline == Doc.hardNewline)
        }

        "Indent" in {
            val ind = Doc.Indent(2, a)
            assert(ind.spaces == 2)
            assert(ind.doc == a)
        }

        "Align" in {
            val al = Doc.Align(a)
            assert(al.doc == a)
        }

        "Concat" in {
            val cat = Doc.Concat(a, b)
            assert(cat.first == a)
            assert(cat.second == b)
        }

        "Group" in {
            val grp = Doc.Group(a)
            assert(grp.doc == a)
        }

        "Choice" in {
            val ch = Doc.Choice(a, b)
            assert(ch.flat == a)
            assert(ch.broken == b)
        }

        "Clause" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Never)
            val cl     = Doc.Clause(policy, a, b, Chunk(a, b), Doc.text(","), trailingSep = true, forcedOpen = true)
            assert(cl.policy == policy)
            assert(cl.open == a && cl.close == b)
            assert(cl.elems == Chunk(a, b))
            assert(cl.trailingSep && cl.forcedOpen)
        }
    }

    "clause" - {
        val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Never)

        "empty elements degenerate to open and close" in {
            assert(Doc.clause(policy, a, b)(Chunk.empty) == Doc.Concat(a, b))
        }

        "non-empty elements build a Clause node" in {
            val cl = Doc.clause(policy, a, b)(Chunk(a, b))
            assert(cl == Doc.Clause(policy, a, b, Chunk(a, b), Doc.text(",")))
            assert(Doc.clause(policy, a, b)(a, b) == cl)
        }

        "generic printers render the lowering like the matching combinator" in {
            val elems     = Chunk(a, b)
            val viaClause = Printer.greedy.print(Doc.clause(policy, Doc.text("send("), Doc.text(")"))(elems), 9)
            val viaChop   = Printer.greedy.print(elems.chop(Doc.text("send("), Doc.text(","), Doc.text(")"), 2), 9)
            assert(viaClause == viaChop)
            assert(viaClause ==
                """send(
                  |  a,
                  |  b
                  |)""".stripMargin)
        }
    }

    "core primitives" - {
        "empty" in {
            assert(Doc.empty == Doc.Empty)
        }

        "space" in {
            assert(Doc.space == Doc.Text(" "))
        }

        "newline" in {
            assert(Doc.newline == Doc.Newline)
        }

        "hardNewline" in {
            assert(Doc.hardNewline == Doc.HardNewline)
        }

        "text" - {
            "non-empty" in {
                assert(Doc.text("hello") == Doc.Text("hello"))
            }

            "empty string returns Empty" in {
                assert(Doc.text("") == Doc.Empty)
            }
        }

        "char" - {
            "regular character" in {
                assert(Doc.char('x') == Doc.Text("x"))
            }

            "space character returns Doc.space" in {
                assert(Doc.char(' ') == Doc.space)
            }
        }
    }

    "append" - {
        "identity element" in {
            assert(Doc.empty.append(a) == a)
            assert(a.append(Doc.empty) == a)
            assert(Doc.append(Doc.empty, a) == a)
            assert(Doc.append(a, Doc.empty) == a)
        }

        "concatenation of non-empty docs" in {
            assert(a.append(b) == Doc.Concat(a, b))
            assert(Doc.append(a, b) == Doc.Concat(a, b))
        }

        "associativity" in {
            val leftAssoc  = a.append(b).append(c)
            val rightAssoc = a.append(b.append(c))
            assert(leftAssoc == Doc.Concat(Doc.Concat(a, b), c))
            assert(rightAssoc == Doc.Concat(a, Doc.Concat(b, c)))
        }
    }

    "indent" - {
        "zero spaces is a no-op" in {
            assert(a.indent(0) == a)
            assert(Doc.indent(0, a) == a)
        }

        "empty document remains empty" in {
            assert(Doc.empty.indent(4) == Doc.Empty)
            assert(Doc.indent(4, Doc.empty) == Doc.Empty)
        }

        "applies indentation" in {
            assert(a.indent(2) == Doc.Indent(2, a))
            assert(Doc.indent(2, a) == Doc.Indent(2, a))
        }

        "default indentation is 4" in {
            assert(a.indent() == Doc.Indent(4, a))
        }

        "additive nesting" in {
            assert(a.indent(2).indent(3) == Doc.Indent(5, a))
            assert(a.indent(2).indent(-2) == a)
        }
    }

    "group" - {
        "empty document remains empty" in {
            assert(Doc.empty.group == Doc.Empty)
            assert(Doc.group(Doc.empty) == Doc.Empty)
            assert(Doc.grouped(Doc.empty) == Doc.Empty)
        }

        "wraps document in Group" in {
            assert(a.group == Doc.Group(a))
            assert(Doc.group(a) == Doc.Group(a))
            assert(Doc.grouped(a) == Doc.Group(a))
        }

        "idempotent" in {
            assert(a.group.group == a.group)
        }
    }

    "align" - {
        "empty document remains empty" in {
            assert(Doc.empty.align == Doc.Empty)
            assert(Doc.align(Doc.empty) == Doc.Empty)
            assert(Doc.aligned(Doc.empty) == Doc.Empty)
        }

        "wraps document in Align" in {
            assert(a.align == Doc.Align(a))
            assert(Doc.align(a) == Doc.Align(a))
            assert(Doc.aligned(a) == Doc.Align(a))
        }

        "idempotent" in {
            assert(a.align.align == a.align)
        }

        "scoped alignment marker" - {
            "passes Alignment marker with offset 0" in {
                var captured: Doc.Alignment = null
                val res                     = Doc.align { col =>
                    captured = col
                    col.append(a)
                }
                assert(captured != null)
                assert(captured.offset == 0)
                assert(captured.id >= 0)
                assert(res == Doc.Align(captured.append(a)))
            }

            "col.indent adds offset" in {
                val _ = Doc.align { col =>
                    val ind2 = col.indent(2)
                    assert(ind2.id == col.id)
                    assert(ind2.offset == 2)

                    val ind5 = ind2.indent(3)
                    assert(ind5.id == col.id)
                    assert(ind5.offset == 5)

                    assert(col.indent(0) eq col)
                    a
                }
            }

            "successive align blocks receive distinct IDs" in {
                var id1 = -1
                var id2 = -1
                val _   = Doc.align { col =>
                    id1 = col.id
                    a
                }
                val _ = Doc.align { col =>
                    id2 = col.id
                    b
                }
                assert(id1 != id2)
            }

            "multi-column align produces multiple distinct markers" in {
                val res = Doc.align(3) { cols =>
                    assert(cols.length == 3)
                    assert(cols(0).id != cols(1).id)
                    assert(cols(1).id != cols(2).id)
                    assert(cols.forall(_.offset == 0))
                    a
                }
                assert(res == Doc.Align(a))
            }
        }
    }

    "choice, or, and fallback" - {
        "or delegates to choice" in {
            assert(a.or(b) == Doc.choice(a, b))
            assert(a.fallback(b) == Doc.choice(a, b))
        }

        "identical alternatives collapse" in {
            assert(Doc.choice(a, a) == a)
            assert(a.or(a) == a)
        }

        "two distinct alternatives" in {
            assert(Doc.choice(a, b) == Doc.Choice(a, b))
            assert(a.or(b) == Doc.Choice(a, b))
        }

        "multi-alternative choice" - {
            "empty sequence returns Empty" in {
                assert(Doc.choice() == Doc.Empty)
                assert(Doc.choice(Chunk.empty[Doc]) == Doc.Empty)
            }

            "single alternative returns that alternative" in {
                assert(Doc.choice(a) == a)
                assert(Doc.choice(Chunk(a)) == a)
            }

            "multiple alternatives form right-associative chain" in {
                assert(Doc.choice(a, b, c) == Doc.Choice(a, Doc.Choice(b, c)))
                assert(Doc.choice(Chunk(a, b, c)) == Doc.Choice(a, Doc.Choice(b, c)))
            }
        }

        "spaceOrNewline and emptyOrNewline" in {
            assert(Doc.spaceOrNewline == Doc.choice(Doc.space, Doc.newline))
            assert(Doc.emptyOrNewline == Doc.choice(Doc.empty, Doc.newline))
        }
    }

    "concat" - {
        "two docs" in {
            assert(Doc.concat(a, b) == a.append(b))
        }

        "empty chunk returns empty" in {
            assert(Doc.concat(Chunk.empty[Doc]) == Doc.empty)
            assert(Doc.concat() == Doc.empty)
        }

        "single element" in {
            assert(Doc.concat(Chunk(a)) == a)
            assert(Doc.concat(a) == a)
        }

        "multiple elements" in {
            assert(Doc.concat(Chunk(a, b, c)) == a.append(b).append(c))
            assert(Doc.concat(a, b, c) == a.append(b).append(c))
        }
    }

    "join, enclose, and frame" - {
        val comma = Doc.text(",")
        val pipe  = Doc.text("|")
        val left  = Doc.text("(")
        val right = Doc.text(")")

        "join" - {
            "empty collection" in {
                assert(Doc.join(comma)() == Doc.empty)
                assert(Doc.join(comma)(Chunk.empty[Doc]) == Doc.empty)
            }

            "single element" in {
                assert(Doc.join(comma)(a) == a)
                assert(Doc.join(comma)(Chunk(a)) == a)
            }

            "multiple elements" in {
                val expected = a.append(comma).append(b).append(comma).append(c)
                assert(Doc.join(comma)(a, b, c) == expected)
                assert(Doc.join(comma)(Chunk(a, b, c)) == expected)
            }
        }

        "enclose" - {
            "single body" in {
                assert(Doc.enclose(left, right)(a) == left.append(a).append(right))
            }

            "collection with open, sep, close" - {
                "empty" in {
                    assert(Doc.enclose(left, comma, right)() == left.append(right))
                    assert(Doc.enclose(left, comma, right)(Chunk.empty[Doc]) == left.append(right))
                }

                "single element" in {
                    assert(Doc.enclose(left, comma, right)(a) == left.append(a).append(right))
                    assert(Doc.enclose(left, comma, right)(Chunk(a)) == left.append(a).append(right))
                }

                "multiple elements" in {
                    val inner    = a.append(comma).append(b).append(comma).append(c)
                    val expected = left.append(inner).append(right)
                    assert(Doc.enclose(left, comma, right)(a, b, c) == expected)
                    assert(Doc.enclose(left, comma, right)(Chunk(a, b, c)) == expected)
                }
            }
        }

        "frame" - {
            "empty" in {
                assert(Doc.frame(pipe)() == Doc.empty)
                assert(Doc.frame(pipe)(Chunk.empty[Doc]) == Doc.empty)
            }

            "single element" in {
                val expected = pipe.append(a).append(pipe)
                assert(Doc.frame(pipe)(a) == expected)
                assert(Doc.frame(pipe)(Chunk(a)) == expected)
            }

            "multiple elements" in {
                val expected = pipe.append(a).append(pipe).append(b).append(pipe).append(c).append(pipe)
                assert(Doc.frame(pipe)(a, b, c) == expected)
                assert(Doc.frame(pipe)(Chunk(a, b, c)) == expected)
            }
        }
    }

    "pattern matching" in {
        def describe(doc: Doc): String =
            doc match
                case Doc.Empty                                       => "empty"
                case Doc.Text(t)                                     => s"text($t)"
                case Doc.Newline                                     => "newline"
                case Doc.HardNewline                                 => "hardNewline"
                case Doc.Indent(n, d)                                => s"indent($n, ${describe(d)})"
                case Doc.Align(d)                                    => s"align(${describe(d)})"
                case Doc.Concat(d1, d2)                              => s"concat(${describe(d1)}, ${describe(d2)})"
                case Doc.Group(d)                                    => s"group(${describe(d)})"
                case Doc.Choice(flat, broken)                        => s"choice(${describe(flat)}, ${describe(broken)})"
                case Doc.Alignment(id, offset)                       => s"alignment($id, $offset)"
                case Doc.Clause(p, open, close, elems, sep, _, _, _) =>
                    s"clause(${p.site}, ${describe(open)}, ${describe(close)}, ${elems.length}, ${describe(sep)})"

        assert(describe(Doc.Empty) == "empty")
        assert(describe(Doc.Text("hi")) == "text(hi)")
        assert(describe(Doc.Newline) == "newline")
        assert(describe(Doc.HardNewline) == "hardNewline")
        assert(describe(Doc.Indent(2, a)) == "indent(2, text(a))")
        assert(describe(Doc.Align(a)) == "align(text(a))")
        assert(describe(Doc.Concat(a, b)) == "concat(text(a), text(b))")
        assert(describe(Doc.Group(a)) == "group(text(a))")
        assert(describe(Doc.Choice(a, b)) == "choice(text(a), text(b))")
        var testMarker: Doc.Alignment = null
        val _                         = Doc.align { col =>
            testMarker = col
            a
        }
        assert(describe(testMarker) == s"alignment(${testMarker.id}, 0)")
        assert(describe(testMarker.indent(4)) == s"alignment(${testMarker.id}, 4)")
        assert(
            describe(Doc.Clause(
                Doc.ClausePolicy(
                    Doc.ClauseSite.Call,
                    2,
                    true,
                    false,
                    Doc.BinPackMode.Never
                ),
                a,
                b,
                Chunk(a, b)
            )) ==
                "clause(Call, text(a), text(b), 2, text(,))"
        )
    }

    "separate" - {
        "empty collection" in {
            assert(Doc.separate(Doc.text(","))() == Doc.empty)
            assert(Doc.separate(Doc.text(","))(Chunk.empty[Doc]) == Doc.empty)
        }

        "single element" in {
            assert(Doc.separate(Doc.text(","))(a) == a)
            assert(Doc.separate(Doc.text(","))(Chunk(a)) == a)
        }

        "multiple elements" in {
            val comma    = Doc.text(",")
            val expected = a.append(comma).append(b).append(comma).append(c)
            assert(Doc.separate(comma)(a, b, c) == expected)
            assert(Doc.separate(comma)(Chunk(a, b, c)) == expected)
        }
    }

    "join" - {
        "empty collection" in {
            val left  = Doc.text("[")
            val right = Doc.text("]")
            assert(Doc.join(left, Doc.text(","), right)() == left.append(right))
            assert(Doc.join(left, Doc.text(","), right)(Chunk.empty[Doc]) == left.append(right))
        }

        "single element" in {
            val left  = Doc.text("[")
            val right = Doc.text("]")
            assert(Doc.join(left, Doc.text(","), right)(a) == left.append(a).append(right))
            assert(Doc.join(left, Doc.text(","), right)(Chunk(a)) == left.append(a).append(right))
        }

        "multiple elements" in {
            val left     = Doc.text("[")
            val comma    = Doc.text(",")
            val right    = Doc.text("]")
            val expected = left.append(a.append(comma).append(b).append(comma).append(c)).append(right)
            assert(Doc.join(left, comma, right)(a, b, c) == expected)
            assert(Doc.join(left, comma, right)(Chunk(a, b, c)) == expected)
        }
    }

    "stack and lines" - {
        "empty collection" in {
            assert(Doc.stack() == Doc.empty)
            assert(Doc.stack(Chunk.empty[Doc]) == Doc.empty)
            assert(Doc.lines() == Doc.empty)
            assert(Doc.lines(Chunk.empty[Doc]) == Doc.empty)
        }

        "single element" in {
            assert(Doc.stack(a) == a)
            assert(Doc.stack(Chunk(a)) == a)
            assert(Doc.lines(a) == a)
            assert(Doc.lines(Chunk(a)) == a)
        }

        "multiple elements" in {
            val expected = a.append(Doc.newline).append(b).append(Doc.newline).append(c)
            assert(Doc.stack(a, b, c) == expected)
            assert(Doc.stack(Chunk(a, b, c)) == expected)
            assert(Doc.lines(a, b, c) == expected)
            assert(Doc.lines(Chunk(a, b, c)) == expected)
        }
    }

    "space and spaced" - {
        "empty collection" in {
            assert(Doc.space(Chunk.empty[Doc]) == Doc.empty)
            assert(Doc.spaced() == Doc.empty)
            assert(Doc.spaced(Chunk.empty[Doc]) == Doc.empty)
        }

        "single element" in {
            assert(Doc.space(a) == a)
            assert(Doc.space(Chunk(a)) == a)
            assert(Doc.spaced(a) == a)
            assert(Doc.spaced(Chunk(a)) == a)
        }

        "multiple elements" in {
            val expected = a.append(Doc.space).append(b).append(Doc.space).append(c)
            assert(Doc.space(a, b, c) == expected)
            assert(Doc.space(Chunk(a, b, c)) == expected)
            assert(Doc.spaced(a, b, c) == expected)
            assert(Doc.spaced(Chunk(a, b, c)) == expected)
        }
    }

    "between and enclose" - {
        val left  = Doc.text("<")
        val right = Doc.text(">")

        "between" in {
            assert(Doc.between(left, a, right) == left.append(a).append(right))
        }

        "enclose" in {
            assert(Doc.enclose(left, right)(a) == left.append(a).append(right))
        }
    }

    "chop" - {
        val open  = Doc.text("(")
        val close = Doc.text(")")
        val sep   = Doc.text(",")

        "empty collection" in {
            assert(Doc.chop(open, sep, close)() == open.append(close))
            assert(Doc.chop(open, sep, close)(Chunk.empty[Doc]) == open.append(close))
        }

        "single element" in {
            val broken   = open.append(Doc.newline).append(a.indent(4)).append(Doc.newline).append(close)
            val flat     = open.append(a).append(close)
            val expected = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chop(open, sep, close)(a) == expected)
            assert(Doc.chop(open, sep, close)(Chunk(a)) == expected)
        }

        "multiple elements" in {
            val brokenSep   = sep.append(Doc.newline)
            val flatSep     = sep.append(Doc.space)
            val brokenInner = a.append(brokenSep).append(b).indent(4)
            val broken      = open.append(Doc.newline).append(brokenInner).append(Doc.newline).append(close)
            val flatInner   = a.append(flatSep).append(b)
            val flat        = open.append(flatInner).append(close)
            val expected    = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chop(open, sep, close)(a, b) == expected)
            assert(Doc.chop(open, sep, close)(Chunk(a, b)) == expected)
        }

        "custom indent" in {
            val brokenSep   = sep.append(Doc.newline)
            val brokenInner = a.append(brokenSep).append(b).indent(2)
            val broken      = open.append(Doc.newline).append(brokenInner).append(Doc.newline).append(close)
            val flat        = open.append(a.append(sep.append(Doc.space)).append(b)).append(close)
            val expected    = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chop(open, sep, close, indent = 2)(a, b) == expected)
        }

        "choice separator preserves zero-trailing-whitespace invariant" in {
            val commaSep    = Doc.choice(flat = Doc.text(", "), broken = Doc.text(",").append(Doc.newline))
            val brokenInner = a.append(Doc.text(",").append(Doc.newline)).append(b).indent(4)
            val broken      = open.append(Doc.newline).append(brokenInner).append(Doc.newline).append(close)
            val flatInner   = a.append(Doc.text(", ")).append(b)
            val flat        = open.append(flatInner).append(close)
            val expected    = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chop(open, commaSep, close)(a, b) == expected)
        }
    }

    "chopClosed" - {
        val open  = Doc.text("(")
        val close = Doc.text(")")
        val sep   = Doc.text(",")

        "empty collection" in {
            assert(Doc.chopClosed(open, sep, close)() == open.append(close))
            assert(Doc.chopClosed(open, sep, close)(Chunk.empty[Doc]) == open.append(close))
        }

        "single element" in {
            val broken   = open.append(Doc.newline).append(a.indent(4)).append(close)
            val flat     = open.append(a).append(close)
            val expected = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chopClosed(open, sep, close)(a) == expected)
            assert(Doc.chopClosed(open, sep, close)(Chunk(a)) == expected)
        }

        "multiple elements" in {
            val brokenSep   = sep.append(Doc.newline)
            val flatSep     = sep.append(Doc.space)
            val brokenInner = a.append(brokenSep).append(b).indent(4)
            val broken      = open.append(Doc.newline).append(brokenInner).append(close)
            val flatInner   = a.append(flatSep).append(b)
            val flat        = open.append(flatInner).append(close)
            val expected    = Doc.group(Doc.choice(flat, broken))
            assert(Doc.chopClosed(open, sep, close)(a, b) == expected)
            assert(Doc.chopClosed(open, sep, close)(Chunk(a, b)) == expected)
        }
    }

    "hang" - {
        val open  = Doc.text("(")
        val close = Doc.text(")")
        val sep   = Doc.text(",")

        "empty collection" in {
            assert(Doc.hang(open, sep, close)() == open.append(close))
            assert(Doc.hang(open, sep, close)(Chunk.empty[Doc]) == open.append(close))
        }

        "single element" in {
            assert(Doc.hang(open, sep, close)(a) == open.append(a).append(close))
            assert(Doc.hang(open, sep, close)(Chunk(a)) == open.append(a).append(close))
        }

        "multiple elements" in {
            val brokenSep = sep.append(Doc.newline)
            val flatSep   = sep.append(Doc.space)
            val broken    = open.append(Doc.align(a.append(brokenSep).append(b).append(close)))
            val flat      = open.append(a.append(flatSep).append(b)).append(close)
            val expected  = Doc.group(Doc.choice(flat, broken))
            assert(Doc.hang(open, sep, close)(a, b) == expected)
            assert(Doc.hang(open, sep, close)(Chunk(a, b)) == expected)
        }
    }

    "wrap" - {
        val open  = Doc.text("(")
        val close = Doc.text(")")
        val sep   = Doc.text(",")

        "empty collection" in {
            assert(Doc.wrap(open, sep, close)() == open.append(close))
            assert(Doc.wrap(open, sep, close)(Chunk.empty[Doc]) == open.append(close))
        }

        "single element" in {
            assert(Doc.wrap(open, sep, close)(a) == open.append(a).append(close))
            assert(Doc.wrap(open, sep, close)(Chunk(a)) == open.append(a).append(close))
        }

        "multiple elements" in {
            val softSep  = Doc.group(Doc.choice(flat = sep.append(Doc.space), broken = sep.append(Doc.newline)))
            val body     = Doc.align(a.append(softSep).append(b))
            val expected = open.append(body).append(close)
            assert(Doc.wrap(open, sep, close)(a, b) == expected)
            assert(Doc.wrap(open, sep, close)(Chunk(a, b)) == expected)
        }
    }

    "Chunk[Doc] extensions" - {
        val chunk = Chunk(a, b)
        val sep   = Doc.text(",")
        val open  = Doc.text("(")
        val close = Doc.text(")")

        "separate" in {
            assert(chunk.separate(sep) == Doc.separate(sep)(chunk))
        }

        "join" in {
            assert(chunk.join(open, sep, close) == Doc.join(open, sep, close)(chunk))
        }

        "chop" in {
            assert(chunk.chop(open, sep, close) == Doc.chop(open, sep, close)(chunk))
        }

        "chopClosed" in {
            assert(chunk.chopClosed(open, sep, close) == Doc.chopClosed(open, sep, close)(chunk))
        }

        "hang" in {
            assert(chunk.hang(open, sep, close) == Doc.hang(open, sep, close)(chunk))
        }

        "wrap" in {
            assert(chunk.wrap(open, sep, close) == Doc.wrap(open, sep, close)(chunk))
        }
    }

end DocTest
