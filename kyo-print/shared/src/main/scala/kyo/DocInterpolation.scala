package kyo

import scala.collection.mutable.ArrayBuffer

/** Extension methods constructing [[Doc]] values through string interpolation.
  *
  * The `doc` interpolator turns a string literal with embedded values into a
  * Doc whose in-source structure mirrors the intended output, enabling readable
  * authoring of code generators and multi-line templates:
  * {{{
  * val methodDoc =
  *     doc"""
  *     def $name${params.chop("(", ",", ")")}: $returnType =
  *         $body
  *     """
  * }}}
  *
  * Literal text is normalized so indentation in the surrounding source does not
  * leak into the rendered document:
  *   - baseline indentation, the minimum leading whitespace across non-blank
  *     lines, is stripped from every line
  *   - a blank first line and the newline closing it are dropped
  *   - a blank last line and the newline preceding it are dropped
  *   - trailing whitespace at line ends is removed
  *
  * Interpolated values accept `Doc` or `String` arguments. Embedded multiline
  * values are aligned so their continuation lines indent to the column where
  * the value is inserted.
  */
extension (sc: StringContext)

    /** Builds a [[Doc]] from this literal interleaved with the given values. */
    def doc(args: (Doc | String)*): Doc =
        DocInterpolation.interpolate(sc.parts, args)

end extension

/** Internal machinery of the `doc` string interpolator.
  *
  * Performs two passes over the literal parts of a string context: an analysis
  * pass computing the baseline indentation and the blank border lines to drop,
  * and an assembly pass emitting text fragments, hard line breaks, and aligned
  * embedded values into a single [[Doc]].
  */
private[kyo] object DocInterpolation:

    def interpolate(parts: Seq[String], args: Seq[Doc | String]): Doc =
        require(parts.length == args.length + 1, "malformed doc interpolation")
        val escapedParts  = parts.map(StringContext.processEscapes)
        val analyzed      = analyze(escapedParts)
        val baseline      = analyzed.baseline
        val dropFirst     = analyzed.firstBlank
        val dropLast      = analyzed.lastBlank
        val totalNewlines = escapedParts.iterator.map(_.count(_ == '\n')).sum
        var acc           = Doc.empty
        var atLineStart   = true
        var newlineIndex  = 0
        val partsCount    = escapedParts.length
        var i             = 0
        while i < partsCount do
            val segs       = escapedParts(i).split("\n", -1)
            val segsCount  = segs.length
            val isLastPart = i == partsCount - 1
            var j          = 0
            while j < segsCount do
                if j > 0 then
                    val drop = (newlineIndex == 0 && dropFirst) ||
                        (newlineIndex == totalNewlines - 1 && dropLast)
                    newlineIndex += 1
                    if !drop then acc = acc.append(Doc.hardNewline)
                    atLineStart = true
                end if
                val frag    = segs(j)
                val trimmed = if atLineStart then stripLeading(frag, baseline) else frag
                val body    =
                    if j < segsCount - 1 || isLastPart then trimmed.stripTrailing()
                    else trimmed
                if body.nonEmpty then
                    acc = acc.append(Doc.text(body))
                    atLineStart = false
                end if
                j += 1
            end while
            if i < args.length then
                acc = acc.append(embed(args(i)))
                atLineStart = false
            end if
            i += 1
        end while
        acc
    end interpolate

    private def embed(arg: Doc | String): Doc =
        arg match
            case d: Doc =>
                d.align
            case s: String =>
                if s.indexOf('\n') < 0 then Doc.text(s)
                else textLines(s).align

    private def textLines(s: String): Doc =
        val segs = s.split("\n", -1)
        var acc  = Doc.empty
        var j    = 0
        while j < segs.length do
            if j > 0 then acc = acc.append(Doc.hardNewline)
            val line = segs(j).stripTrailing()
            if line.nonEmpty then acc = acc.append(Doc.text(line))
            j += 1
        end while
        acc
    end textLines

    private def stripLeading(s: String, spaces: Int): String =
        var i     = 0
        val limit = math.min(spaces, s.length)
        while i < limit && s.charAt(i).isWhitespace do i += 1
        if i == 0 then s else s.substring(i)
    end stripLeading

    final private class LineInfo:
        var leading    = 0
        var allWs      = true
        var hasContent = false
        var hasArg     = false

        def isBlank: Boolean = !hasContent && !hasArg

        def scan(ch: Char): Unit =
            if allWs then
                if ch.isWhitespace then leading += 1
                else
                    allWs = false
                    hasContent = true
            else if !ch.isWhitespace then hasContent = true
        end scan

        def markArg(): Unit =
            hasArg = true
            allWs = false
            hasContent = true
        end markArg
    end LineInfo

    final private class Analysis(val baseline: Int, val firstBlank: Boolean, val lastBlank: Boolean)

    private def analyze(parts: Seq[String]): Analysis =
        val lines   = new ArrayBuffer[LineInfo]
        var current = new LineInfo
        val count   = parts.length
        var i       = 0
        while i < count do
            val part = parts(i)
            val len  = part.length
            var k    = 0
            while k < len do
                val ch = part.charAt(k)
                if ch == '\n' then
                    lines += current
                    current = new LineInfo
                else current.scan(ch)
                end if
                k += 1
            end while
            if i < count - 1 then current.markArg()
            i += 1
        end while
        lines += current
        var baseline = Int.MaxValue
        var nonBlank = false
        var idx      = 0
        while idx < lines.length do
            val line = lines(idx)
            if !line.isBlank then
                nonBlank = true
                if line.leading < baseline then baseline = line.leading
            end if
            idx += 1
        end while
        Analysis(
            if nonBlank then baseline else 0,
            lines.head.isBlank,
            lines.length > 1 && lines.last.isBlank
        )
    end analyze

end DocInterpolation
