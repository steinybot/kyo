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
  *   - a `Never` bin-pack site breaks after the opening delimiter with one
  *     element per line, and the closing delimiter dangles on its own line
  *     when the policy says so;
  *   - a bin-packing `Always` site keeps both delimiters attached and packs
  *     as many elements per line as the width allows;
  *   - an `Oneline` site packs like `Always` but isolates multiline elements
  *     on their own lines, breaking after the opening delimiter when the
  *     first element is multiline (the closing delimiter then dangles
  *     according to the policy);
  *   - an aligning site keeps the opening delimiter attached, packs elements,
  *     and aligns continuation lines under it;
  *   - a template (`Extend`) site packs or lists parents per the
  *     `parentConstructors` mode, starting the parents inline when the
  *     template's parameter clause itself broke.
  *
  * Continuation indentation is applied relative to the clause's base, so
  * nested clauses stack their indents the way scalafmt stacks continuation
  * indents of nested argument lists.
  *
  * Source line breaks are handled the way scalafmt's default classic newline
  * source handles them: a break at only one clause delimiter is folded, and
  * a `Never` clause whose source broke at both delimiters keeps its broken
  * form (the preserved config-style shape).
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

        val sb       = new java.lang.StringBuilder()
        var col      = 0
        var pending  = -1
        var newlines = 0

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
            newlines += 1
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
            end match
        end flatWidth

        private def clauseFlatWidth(c: Doc.Clause): Option[Int] =
            for
                openW  <- flatWidth(c.open)
                closeW <- flatWidth(c.close)
                elemWs <- allElemWidths(c)
            yield
                if isSpaceJoined(c) then openW + elemWs.map(1 + _).sum + closeW
                else
                    openW + elemWs.sum + (c.elems.length - 1) * (flatWidth(c.sep).getOrElse(0) + 1) +
                        closeW
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

        /** Whether an element cannot render on a single line from a fresh
          * line at `indent`: either it contains a forced break, or its
          * interior breaks by fit when rendered there. An element with no
          * possible interior break (plain text) stays oneline even when it
          * overflows the width, matching scalafmt's single-line-block rule.
          */
        private def isMultiline(elem: Doc, indent: Int): Boolean =
            flatWidth(elem) match
                case None    => true
                case Some(0) => false
                case Some(w) => indent + w > width && breaksWhenRendered(elem, indent)
            end match
        end isMultiline

        /** Renders `elem` into a throwaway renderer to see whether it emits a
          * newline; used only by the `Oneline` packing rules.
          */
        private def breaksWhenRendered(elem: Doc, startCol: Int): Boolean =
            val probe = new Renderer(width)
            probe.col = startCol
            probe.render(elem, startCol, flat = false)
            probe.newlines > 0
        end breaksWhenRendered

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
            if c.elems.isEmpty then
                render(c.open, base, false)
                render(c.close, base, false)
            else
                val preserved = c.forcedOpen && c.forcedClose
                val fitsFlat  = !preserved && clauseFlatWidth(c).exists(col + _ <= width)
                if fitsFlat then renderFlatClause(c, base)
                else if c.policy.site == Doc.ClauseSite.Extend then renderTemplateClause(c, base)
                else if c.policy.binPack == Doc.BinPackMode.Never then renderOnePerLine(c, base)
                else renderPacked(c, base)
                end if
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

        private def renderOnePerLine(c: Doc.Clause, base: Int): Unit =
            val elemIndent = base + c.policy.indent
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

        /** Bin-packs call and definition sites (Always and Oneline). The open
          * delimiter stays attached; the Oneline mode isolates multiline
          * elements and breaks after the open when the first element is
          * multiline, in which case the close dangles per policy.
          */
        private def renderPacked(c: Doc.Clause, base: Int): Unit =
            val oneline = c.policy.binPack == Doc.BinPackMode.Oneline
            render(c.open, base, false)
            // An aligning packed clause keeps continuation lines under the
            // opening delimiter instead of at a fixed indent.
            val elemIndent =
                if c.policy.align then col
                else base + c.policy.indent
            var openBroke = false
            if oneline && isMultiline(c.elems(0), elemIndent) then
                emitNewline(elemIndent)
                openBroke = true
            end if
            var afterMultiline = false
            var i              = 0
            while i < c.elems.length do
                val elem        = c.elems(i)
                val atLineStart = pending >= 0
                val multiline   = isMultiline(elem, elemIndent)
                if oneline && multiline then
                    // An Oneline site isolates a multiline element on its
                    // own line(s), and the next element starts fresh.
                    if !atLineStart then emitNewline(elemIndent)
                    render(elem, elemIndent, false)
                else
                    // The first element flows directly after an attached
                    // open (or at the line start); later elements are
                    // separated by a space or a line break by fit. A
                    // multiline element under Always renders inline and
                    // breaks internally; only a forced break starts a new
                    // line unconditionally. Under Oneline, an element
                    // following a multiline one always starts fresh.
                    val needSpace = !atLineStart && i > 0
                    if afterMultiline && !atLineStart then emitNewline(elemIndent)
                    else
                        flatWidth(elem) match
                            case Some(w) if !needSpace || col + 1 + w <= width =>
                                if needSpace then emitSpace()
                            case _ => if !atLineStart then emitNewline(elemIndent)
                        end match
                    end if
                    render(elem, elemIndent, false)
                end if
                afterMultiline = oneline && multiline
                if i < c.elems.length - 1 || c.trailingSep then render(c.sep, elemIndent, false)
                i += 1
            end while
            if c.close ne Doc.Empty then
                if openBroke && c.policy.dangling then emitNewline(base)
                render(c.close, base, false)
            end if
        end renderPacked

        /** Formats a broken template parent clause (`extends` / `with`).
          *
          * Mirrors scalafmt's splits at the first parent: `Always` first
          * tries to keep the parents flowing from wherever the header ended,
          * tolerating newlines only inside parent constructors, and falls
          * back to breaking before the first parent and then packing by fit.
          * `Never` lists one parent per line. `Oneline` renders the whole
          * chain on one line when every parent is single-line and it fits,
          * and otherwise lists one parent per line;
          * `OnelineIfPrimaryOneline` picks between `Oneline` and `Never` by
          * whether the clause's opening (the template's parameter clause)
          * rendered on a single line. A listed clause whose opening broke
          * starts its first parent inline after the opening.
          */
        private def renderTemplateClause(c: Doc.Clause, base: Int): Unit =
            val elemIndent = base + c.policy.indent
            val nlBefore   = newlines
            render(c.open, base, false)
            val primaryFlat = newlines == nlBefore
            val mode        = c.policy.binPack match
                case Doc.BinPackMode.OnelineIfPrimaryOneline =>
                    if primaryFlat then Doc.BinPackMode.Oneline else Doc.BinPackMode.Never
                case other => other
            mode match
                case Doc.BinPackMode.Always =>
                    if !tryInlineChain(c, elemIndent) then
                        if primaryFlat then emitNewline(elemIndent)
                        var i = 0
                        while i < c.elems.length do
                            val atLineStart = pending >= 0
                            if !atLineStart then
                                val fitsHere =
                                    flatWidth(c.elems(i)).exists(w => col + 1 + w <= width)
                                if fitsHere then emitSpace()
                                else emitNewline(elemIndent)
                            end if
                            render(c.elems(i), elemIndent, false)
                            if i < c.elems.length - 1 then render(c.sep, elemIndent, false)
                            i += 1
                        end while
                    end if
                case Doc.BinPackMode.Oneline =>
                    // The whole chain on one line when every parent is
                    // single-line and it fits; the first parent follows a
                    // broken opening inline and a flat opening on a fresh
                    // line.
                    val startCol = if primaryFlat then elemIndent else col
                    val chainW   = c.elems.foldLeft(0)((acc, e) => acc + 1 + flatWidth(e).getOrElse(0))
                    val lead     = if primaryFlat then 0 else 1
                    val oneLine  = !c.elems.exists(isMultiline(_, elemIndent)) &&
                        startCol + chainW - 1 + lead <= width
                    if oneLine then
                        if !primaryFlat then emitSpace()
                        var i = 0
                        while i < c.elems.length do
                            if i > 0 then emitSpace()
                            render(c.elems(i), elemIndent, false)
                            i += 1
                        end while
                    else
                        emitListedChain(c, elemIndent, primaryFlat)
                    end if
                case _ =>
                    emitListedChain(c, elemIndent, primaryFlat)
            end match
            if c.close ne Doc.Empty then render(c.close, base, false)
        end renderTemplateClause

        /** Lists one parent per line; a flat opening breaks before the first
          * parent, a broken opening has the first parent follow it inline.
          */
        private def emitListedChain(c: Doc.Clause, elemIndent: Int, primaryFlat: Boolean): Unit =
            if primaryFlat then emitNewline(elemIndent)
            else if pending < 0 then emitSpace()
            var i = 0
            while i < c.elems.length do
                if i > 0 then emitNewline(elemIndent)
                render(c.elems(i), elemIndent, false)
                if i < c.elems.length - 1 || c.trailingSep then
                    render(c.sep, elemIndent, false)
                i += 1
            end while
        end emitListedChain

        /** Renders the parents flowing from the current position with no
          * newline between parents, returning false when that is impossible
          * because a parent overflows the width. Interior breaks (a parent
          * constructor's argument clause) are tolerated, matching the
          * exclusions of scalafmt's single-line split. The attempt runs on a
          * scratch renderer first; its output is discarded on success and
          * the real render replays it.
          */
        private def tryInlineChain(c: Doc.Clause, elemIndent: Int): Boolean =
            val probe = new Renderer(width)
            probe.col = col
            probe.pending = pending
            val ok = probe.emitInlineChain(c, elemIndent)
            if ok then emitInlineChain(c, elemIndent): Unit
            ok
        end tryInlineChain

        private def emitInlineChain(c: Doc.Clause, elemIndent: Int): Boolean =
            var i = 0
            while i < c.elems.length do
                if pending < 0 then emitSpace()
                if col > width then return false
                render(c.elems(i), elemIndent, false)
                if col > width then return false
                i += 1
            end while
            true
        end emitInlineChain

    end Renderer

end ScalafmtPrinter
