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
            assert(ch.broken == a)
            assert(ch.flat == b)
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
    }

    "choice and fallback" - {
        "fallback delegates to choice" in {
            assert(a.fallback(b) == Doc.choice(a, b))
        }

        "identical alternatives collapse" in {
            assert(Doc.choice(a, a) == a)
        }

        "two distinct alternatives" in {
            assert(Doc.choice(a, b) == Doc.Choice(a, b))
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
    }

    "pattern matching" in {
        def describe(doc: Doc): String =
            doc match
                case Doc.Empty                => "empty"
                case Doc.Text(t)              => s"text($t)"
                case Doc.Newline              => "newline"
                case Doc.HardNewline          => "hardNewline"
                case Doc.Indent(n, d)         => s"indent($n, ${describe(d)})"
                case Doc.Align(d)             => s"align(${describe(d)})"
                case Doc.Concat(d1, d2)       => s"concat(${describe(d1)}, ${describe(d2)})"
                case Doc.Group(d)             => s"group(${describe(d)})"
                case Doc.Choice(broken, flat) => s"choice(${describe(broken)}, ${describe(flat)})"

        assert(describe(Doc.Empty) == "empty")
        assert(describe(Doc.Text("hi")) == "text(hi)")
        assert(describe(Doc.Newline) == "newline")
        assert(describe(Doc.HardNewline) == "hardNewline")
        assert(describe(Doc.Indent(2, a)) == "indent(2, text(a))")
        assert(describe(Doc.Align(a)) == "align(text(a))")
        assert(describe(Doc.Concat(a, b)) == "concat(text(a), text(b))")
        assert(describe(Doc.Group(a)) == "group(text(a))")
        assert(describe(Doc.Choice(a, b)) == "choice(text(a), text(b))")
    }

end DocTest
