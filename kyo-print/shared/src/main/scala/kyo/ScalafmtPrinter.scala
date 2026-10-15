package kyo

/** The scalafmt parity layout engine.
  *
  * Renders documents by reproducing the decisions scalafmt makes during
  * layout rather than during document construction. A [[Doc.Clause]] carries
  * the site policy scalafmt would consult (resolved from a
  * `ScalafmtConfig` by the document builder), and this engine resolves the
  * policy against the current column and layout width:
  *
  *   - a clause whose flat form fits stays flat;
  *   - otherwise a `Never` bin-pack site breaks after the opening delimiter
  *     with one element per line, and the closing delimiter dangles on its own
  *     line when the policy says so;
  *   - a bin-packing site keeps the opening delimiter attached and packs as
  *     many elements per line as the width allows, unless the clause is
  *     forced open, in which case the elements still pack but the closing
  *     delimiter dangles;
  *   - an aligning site keeps the opening delimiter attached and aligns
  *     continuation lines under it, taking precedence over bin-packing.
  *
  * Continuation indentation is applied relative to the clause's base, so
  * nested clauses stack their indents the way scalafmt stacks continuation
  * indents of nested argument lists.
  *
  * Documents that are not clauses are rendered greedily, so this printer can
  * be used on any document. Output is validated byte-for-byte against
  * fixtures committed by scalafmt's own test suite (see `ScalafmtOracle`).
  */
private[kyo] object ScalafmtPrinter extends Printer:

    def print(doc: Doc, width: Int): String =
        if doc eq Doc.Empty then ""
        else
            val renderer = new Renderer(width)
            renderer.render(doc, 0, flat = false)
            renderer.result()
        end if
    end print

    def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
        val text = print(doc, width)
        if text.isEmpty then
            Stream.empty
        else
            val parts = text.split("\n", -1)
            val last  = parts.length - 1
            val lines = new Array[String](last + 1)
            var i     = 0
            while i <= last do
                lines(i) = if i == last then parts(i) else parts(i) + "\n"
                i += 1
            end while
            Stream.init(Chunk.from(lines))
        end if
    end printStream

    final private class Renderer(width: Int):

        val sb      = new java.lang.StringBuilder()
        var col     = 0
        var pending = -1

        def result(): String = sb.toString

        private def flush(): Unit =
            if pending >= 0 then
                var i = 0
                while i < pending do
                    sb.append(' ')
                    i += 1
                end while
                pending = -1
            end if
        end flush

        private def emitText(text: String): Unit =
            if text.nonEmpty then
                flush()
                sb.append(text)
                col += text.length
            end if
        end emitText

        private def emitSpace(): Unit =
            flush()
            sb.append(' ')
            col += 1
        end emitSpace

        private def emitNewline(indent: Int): Unit =
            sb.append('\n')
            col = indent
            pending = indent
        end emitNewline

        /** The flat width of a document, or `None` when it cannot render on a
          * single line because it contains a forced break.
          */
        def flatWidth(doc: Doc): Option[Int] =
            doc match
                case Doc.Empty                 => Some(0)
                case Doc.Text(text)            => Some(text.length)
                case Doc.Newline               => Some(1)
                case Doc.HardNewline           => None
                case Doc.Indent(_, inner)      => flatWidth(inner)
                case Doc.Align(inner)          => flatWidth(inner)
                case Doc.Group(inner)          => flatWidth(inner)
                case Doc.Choice(_, flat)       => flatWidth(flat)
                case Doc.Concat(first, second) =>
                    for
                        a <- flatWidth(first)
                        b <- flatWidth(second)
                    yield a + b
                case c: Doc.Clause => clauseFlatWidth(c)
        end flatWidth

        private def clauseFlatWidth(c: Doc.Clause): Option[Int] =
            if c.forcedOpen then None
            else
                for
                    openW  <- flatWidth(c.open)
                    closeW <- flatWidth(c.close)
                    elemWs <- allElemWidths(c)
                yield
                    if isSpaceJoined(c) then openW + elemWs.map(1 + _).sum + closeW
                    else
                        openW + elemWs.sum + (c.elems.length - 1) * (flatWidth(c.sep).getOrElse(0) + 1) +
                            closeW
            end if
        end clauseFlatWidth

        private def allElemWidths(c: Doc.Clause): Option[List[Int]] =
            c.elems.foldLeft[Option[List[Int]]](Some(Nil)) { (acc, e) =>
                for
                    ws <- acc
                    w  <- flatWidth(e)
                yield w :: ws
            }.map(_.reverse)
        end allElemWidths

        private def isSpaceJoined(c: Doc.Clause): Boolean =
            c.policy.site == Doc.ClauseSite.Extend

        def render(doc: Doc, base: Int, flat: Boolean): Unit =
            doc match
                case Doc.Empty => ()

                case Doc.Text(text) => emitText(text)

                case Doc.Newline =>
                    if flat then emitSpace() else emitNewline(base)

                case Doc.HardNewline => emitNewline(base)

                case Doc.Indent(spaces, inner) =>
                    val newBase = base + spaces
                    if pending >= 0 then
                        pending = newBase
                        col = newBase
                    render(inner, newBase, flat)

                case Doc.Align(inner) => render(inner, base, flat)

                case Doc.Concat(first, second) =>
                    render(first, base, flat)
                    render(second, base, flat)

                case Doc.Group(inner) =>
                    if flat then render(inner, base, true)
                    else
                        flatWidth(inner) match
                            case Some(w) if col + w <= width => render(inner, base, true)
                            case _                           => render(inner, base, false)
                    end if

                case Doc.Choice(broken, flatAlt) =>
                    render(if flat then flatAlt else broken, base, flat)

                case c: Doc.Clause =>
                    if flat then renderFlatClause(c, base)
                    else renderClause(c, base)
            end match
        end render

        private def renderClause(c: Doc.Clause, base: Int): Unit =
            val elemIndent = base + c.policy.indent
            val fitsFlat   = !c.forcedOpen && clauseFlatWidth(c).exists(col + _ <= width)
            if c.elems.isEmpty then
                render(c.open, base, false)
                render(c.close, base, false)
            else if fitsFlat then
                renderFlatClause(c, base)
            else if c.policy.align || c.policy.binPack == ScalafmtConfig.BinPack.Site.Never then
                renderOnePerLine(c, base, elemIndent)
            else
                renderPacked(c, base, elemIndent)
            end if
        end renderClause

        private def renderFlatClause(c: Doc.Clause, base: Int): Unit =
            val spaceJoined = isSpaceJoined(c)
            render(c.open, base, true)
            var i = 0
            while i < c.elems.length do
                if spaceJoined then emitSpace()
                else if i > 0 then
                    render(c.sep, base, true)
                    emitSpace()
                end if
                render(c.elems(i), base, true)
                if !spaceJoined && c.trailingSep && i == c.elems.length - 1 then
                    render(c.sep, base, true)
                i += 1
            end while
            render(c.close, base, true)
        end renderFlatClause

        private def renderOnePerLine(c: Doc.Clause, base: Int, elemIndent: Int): Unit =
            if c.policy.align then
                render(c.open, base, false)
                val alignCol = col
                var i        = 0
                while i < c.elems.length do
                    if i > 0 then emitNewline(alignCol)
                    render(c.elems(i), alignCol, false)
                    if i < c.elems.length - 1 || c.trailingSep then render(c.sep, alignCol, false)
                    i += 1
                end while
                if c.close ne Doc.Empty then
                    if c.policy.dangling then emitNewline(base)
                    render(c.close, base, false)
                end if
            else
                render(c.open, base, false)
                emitNewline(elemIndent)
                var i = 0
                while i < c.elems.length do
                    if i > 0 then emitNewline(elemIndent)
                    render(c.elems(i), elemIndent, false)
                    if i < c.elems.length - 1 || c.trailingSep then render(c.sep, elemIndent, false)
                    i += 1
                end while
                if c.close ne Doc.Empty then
                    if c.policy.dangling then emitNewline(base)
                    render(c.close, base, false)
                end if
            end if
        end renderOnePerLine

        private def renderPacked(c: Doc.Clause, base: Int, elemIndent: Int): Unit =
            val spaceJoined = isSpaceJoined(c)
            // An extend clause never flows its first parent on the header line:
            // like scalafmt's template handling, the break goes after the type
            // name and the parents pack below it.
            val forceOpenBreak = c.forcedOpen || spaceJoined
            render(c.open, base, false)
            if forceOpenBreak then emitNewline(elemIndent)
            var firstOnLine = true
            var isolateNext = false
            var i           = 0
            while i < c.elems.length do
                val elem        = c.elems(i)
                val w           = flatWidth(elem)
                val atLineStart = pending >= 0
                w match
                    case Some(elemW) =>
                        // The first element flows directly after an attached
                        // open (or at the line start); later elements are
                        // separated by a space or a line break by fit.
                        val needSpace = !atLineStart && (!firstOnLine || spaceJoined)
                        if needSpace && (isolateNext || col + 1 + elemW > width) then
                            emitNewline(elemIndent)
                        else if needSpace then emitSpace()
                        render(elem, elemIndent, false)
                    case None =>
                        // A multiline element is isolated on its own line.
                        if !atLineStart then emitNewline(elemIndent)
                        render(elem, elemIndent, false)
                end match
                firstOnLine = false
                isolateNext = c.policy.binPack == ScalafmtConfig.BinPack.Site.Oneline && w.isEmpty
                if i < c.elems.length - 1 || c.trailingSep then render(c.sep, elemIndent, false)
                i += 1
            end while
            if c.close ne Doc.Empty then
                if c.forcedOpen && c.policy.dangling then emitNewline(base)
                render(c.close, base, false)
            end if
        end renderPacked

    end Renderer

end ScalafmtPrinter
