package kyo

/** A pretty printing document representing structured layout.
  *
  * Doc is an immutable algebraic data type representing text, indentation, line breaks,
  * grouping, and layout choices. Layout interpretation is separated from document
  * representation, allowing different printer engines to format documents according
  * to column constraints.
  *
  * Core layout primitives include:
  *   - empty: the identity document with zero width
  *   - text: literal string slices without newlines
  *   - newline: soft line breaks that collapse to spaces when flattened
  *   - hardNewline: line breaks that always emit a newline
  *   - indent: increases indentation nesting level
  *   - align: aligns line breaks to the current column
  *   - group: prefers single-line layout when width allows
  *   - choice: alternative layouts explored by printers
  *   - clause: a delimited element list whose break style is decided at layout
  *     time from a site policy
  */
sealed trait Doc derives CanEqual:

    def append(that: Doc): Doc =
        if this eq Doc.Empty then that
        else if that eq Doc.Empty then this
        else Doc.Concat(this, that)

    def indent(spaces: Int = 4): Doc =
        Doc.indent(spaces, this)

    def or(that: Doc): Doc =
        Doc.choice(this, that)

    def fallback(that: Doc): Doc =
        or(that)

    def group: Doc =
        Doc.group(this)

    def align: Doc =
        Doc.align(this)

end Doc

/** Companion object providing primitives and combinators for Doc.
  *
  * Contains the canonical ADT node definitions representing layout trees,
  * along with smart constructors and sequence composition combinators.
  * Primitives normalize trivial cases such as empty documents or identity
  * operations to minimize intermediate object allocations during layout tree construction.
  *
  * Layout engines inspect these nodes to produce formatted strings or streams
  * targeting specific column budgets.
  */
object Doc:

    case object Empty                          extends Doc
    case class Text(text: String)              extends Doc
    case object Newline                        extends Doc
    case object HardNewline                    extends Doc
    case class Indent(spaces: Int, doc: Doc)   extends Doc
    case class Align(doc: Doc)                 extends Doc
    case class Concat(first: Doc, second: Doc) extends Doc
    case class Group(doc: Doc)                 extends Doc
    case class Choice(flat: Doc, broken: Doc)  extends Doc
    case class Clause(
        policy: Doc.ClausePolicy,
        open: Doc,
        close: Doc,
        elems: Chunk[Doc],
        sep: Doc = Text(","),
        trailingSep: Boolean = false,
        forcedOpen: Boolean = false,
        forcedClose: Boolean = false
    ) extends Doc

    /** The kind of source construct a [[Clause]] represents.
      *
      * The site selects the layout rules the scalafmt printer applies:
      * `Call` formats method call argument lists, `Defn` formats definition
      * parameter lists, and `Extend` formats template parent clauses
      * (`extends` / `with`), which are space-joined keyword lists without
      * delimiters.
      */
    enum ClauseSite derives CanEqual:
        case Call, Defn, Extend, Lambda
    end ClauseSite

    /** How source newlines interact with layout decisions. */
    enum SourcePolicy derives CanEqual:
        case Classic, Keep, Fold, Unfold
    end SourcePolicy

    /** Break policy before the opening parenthesis of a parameter group. */
    enum BeforeOpenParen derives CanEqual:
        case Fold, Unfold, Keep
    end BeforeOpenParen

    /** Break policy between the opening curly brace and lambda parameters. */
    enum BeforeCurlyLambdaParams derives CanEqual:
        case Never, Always, Multiline, MultilineWithCaseOnly
    end BeforeCurlyLambdaParams

    /** Break and blank-line policy following the arrow of a curly lambda. */
    enum AfterCurlyLambdaParams derives CanEqual:
        case Never, Always, Keep, Squash
    end AfterCurlyLambdaParams

    /** How the elements of a broken clause are distributed across lines, as
      * resolved from a `ScalafmtConfig` by the document builder.
      *
      * `Never` places one element per line. `Always` packs greedily by column
      * budget, letting a multiline element share its last line with later
      * elements. `Oneline` packs like `Always` but keeps multiline elements
      * isolated on their own lines. `OnelineIfPrimaryOneline` is a template
      * rule only: it applies `Oneline` when the clause's opening (the
      * template's parameter clause) rendered on a single line and `Never`
      * otherwise.
      */
    enum BinPackMode derives CanEqual:
        case Never, Always, Oneline, OnelineIfPrimaryOneline
    end BinPackMode

    /** Layout policy for a [[Clause]], resolved from a ScalafmtConfig by the
      * document builder.
      *
      * The policy records everything the layout engine needs to decide how the
      * clause breaks, mirroring the per-site options scalafmt consults during
      * layout: the site selects the decision rules (one element per line,
      * bin-packing, or template breaking), `indent` is the continuation indent
      * in spaces applied relative to the clause's base, `dangling` places the
      * closing delimiter on its own line when the clause breaks, `align`
      * aligns continuation lines under the opening delimiter, `binPack`
      * selects the element packing strategy, and `configStyle` lets a source
      * layout with breaks around both delimiters render in the preserved
      * config-style form.
      */
    /** Layout policy for definition-site vertical multiline formatting. */
    final case class VerticalMultiline(
        atDefnSite: Boolean = false,
        arityThreshold: Int = 100,
        newlineBeforeOpenParen: Boolean = false,
        newlineAfterOpenParen: Boolean = false,
        newlinesAfterImplicitKW: Boolean = false,
        newlinesBeforeImplicitKW: Boolean = false,
        closeParenNewline: Boolean = false,
        includeImplicitKw: Boolean = false,
        includeApplyKw: Boolean = false,
        keepSymbols: Boolean = false
    ) derives CanEqual

    final case class ClausePolicy(
        site: Doc.ClauseSite,
        indent: Int,
        dangling: Boolean,
        align: Boolean,
        binPack: Doc.BinPackMode,
        configStyle: Boolean = false,
        source: Doc.SourcePolicy = Doc.SourcePolicy.Classic,
        beforeOpenParen: Option[Doc.BeforeOpenParen] = None,
        beforeCurlyLambdaParams: Doc.BeforeCurlyLambdaParams = Doc.BeforeCurlyLambdaParams.Never,
        afterCurlyLambdaParams: Doc.AfterCurlyLambdaParams = Doc.AfterCurlyLambdaParams.Never,
        verticalMultiline: Option[Doc.VerticalMultiline] = None
    ) derives CanEqual

    val empty: Doc = Empty

    private val singleSpace: Doc = Text(" ")

    def space: Doc = singleSpace

    val newline: Doc = Newline

    val hardNewline: Doc = HardNewline

    def text(str: String): Doc =
        if str.isEmpty then Empty
        else Text(str)

    def char(c: Char): Doc =
        if c == ' ' then singleSpace
        else Text(c.toString)

    def append(a: Doc, b: Doc): Doc =
        a.append(b)

    def indent(spaces: Int, doc: Doc): Doc =
        if spaces == 0 || (doc eq Empty) then doc
        else
            doc match
                case Indent(prev, inner) =>
                    val total = prev + spaces
                    if total == 0 then inner
                    else Indent(total, inner)
                case _ =>
                    Indent(spaces, doc)

    def group(doc: Doc): Doc =
        if (doc eq Empty) || doc.isInstanceOf[Group] then doc
        else Group(doc)

    def grouped(doc: Doc): Doc =
        group(doc)

    def align(doc: Doc): Doc =
        if (doc eq Empty) || doc.isInstanceOf[Align] then doc
        else Align(doc)

    def aligned(doc: Doc): Doc =
        align(doc)

    def concat(first: Doc, second: Doc): Doc =
        first.append(second)

    def concat(docs: Chunk[Doc]): Doc =
        if docs.isEmpty then empty
        else if docs.length == 1 then docs.head
        else
            var acc = docs.head
            var i   = 1
            while i < docs.length do
                acc = acc.append(docs(i))
                i += 1
            end while
            acc

    def concat(docs: Doc*): Doc =
        concat(Chunk.from(docs))

    def choice(flat: Doc, broken: Doc): Doc =
        if flat == broken then flat
        else Choice(flat, broken)

    def choice(alts: Chunk[Doc]): Doc =
        if alts.isEmpty then empty
        else if alts.length == 1 then alts.head
        else alts.reduceRight((flat, broken) => choice(flat, broken))

    def choice(alts: Doc*): Doc =
        choice(Chunk.from(alts))

    val spaceOrNewline: Doc = choice(singleSpace, newline)

    val emptyOrNewline: Doc = choice(empty, newline)

    /** Builds a clause: a delimited element list whose break style is decided
      * at layout time by the site policy.
      *
      * `open` and `close` are the delimiters (for a definition site the close
      * may carry an attached suffix such as `): Boolean`), `elems` are the
      * arguments or parameters, and `sep` is the bare separator emitted after
      * each element; a single space is added between elements when flat. An
      * empty separator models a space-joined keyword list such as a template
      * parent clause. `forcedOpen` and `forcedClose` record that the source
      * had a line break after the opening delimiter and before the closing
      * delimiter; under the classic newline source the engine folds a break
      * at only one delimiter and preserves the broken form when the source
      * broke at both. A clause without elements degenerates to `open`
      * followed by `close`.
      */
    def clause(
        policy: ClausePolicy,
        open: Doc,
        close: Doc,
        sep: Doc,
        trailingSep: Boolean,
        forcedOpen: Boolean,
        forcedClose: Boolean
    )(elems: Chunk[Doc]): Doc =
        if elems.isEmpty then open.append(close)
        else Clause(policy, open, close, elems, sep, trailingSep, forcedOpen, forcedClose)
    end clause

    def clause(
        policy: ClausePolicy,
        open: Doc,
        close: Doc,
        sep: Doc,
        trailingSep: Boolean,
        forcedOpen: Boolean,
        forcedClose: Boolean
    )(first: Doc, rest: Doc*): Doc =
        clause(policy, open, close, sep, trailingSep, forcedOpen, forcedClose)(
            Chunk.from(first +: rest)
        )

    def clause(policy: ClausePolicy, open: Doc, close: Doc, sep: Doc)(elems: Chunk[Doc]): Doc =
        clause(
            policy,
            open,
            close,
            sep,
            trailingSep = false,
            forcedOpen = false,
            forcedClose =
                false
        )(elems)

    def clause(policy: ClausePolicy, open: Doc, close: Doc, sep: Doc)(first: Doc, rest: Doc*): Doc =
        clause(
            policy,
            open,
            close,
            sep,
            trailingSep = false,
            forcedOpen = false,
            forcedClose =
                false
        )(Chunk.from(first +: rest))

    def clause(policy: ClausePolicy, open: Doc, close: Doc)(elems: Chunk[Doc]): Doc =
        clause(
            policy,
            open,
            close,
            Text(","),
            trailingSep = false,
            forcedOpen = false,
            forcedClose =
                false
        )(elems)

    def clause(policy: ClausePolicy, open: Doc, close: Doc)(first: Doc, rest: Doc*): Doc =
        clause(
            policy,
            open,
            close,
            Text(","),
            trailingSep = false,
            forcedOpen = false,
            forcedClose =
                false
        )(Chunk.from(first +: rest))

    /** Builds a curly lambda clause: `{ params => body }`.
      *
      * `open` and `close` are the enclosing braces (defaulting to `{` and `}`),
      * `params` is the parameter declaration or case header (e.g. `x =>`),
      * and `body` is the lambda body. The site policy decides breaking before
      * and after the parameters at layout time.
      */
    def lambda(
        policy: ClausePolicy,
        params: Doc,
        body: Doc,
        open: Doc = Text("{"),
        close: Doc = Text("}"),
        forcedOpen: Boolean = false,
        forcedClose: Boolean = false
    ): Doc =
        Clause(
            policy,
            open,
            close,
            Chunk(params, body),
            sep = empty,
            trailingSep = false,
            forcedOpen = forcedOpen,
            forcedClose = forcedClose
        )

    /** Approximates a clause with the Wadler-style combinators so the generic
      * printers can render it; the scalafmt printer interprets the policy
      * natively. Bin-packed and aligned clauses are approximated with their
      * closest combinator (`wrap` / `hang`), which does not reproduce scalafmt
      * output exactly.
      */
    private[kyo] def lowerClause(c: Clause): Doc =
        if c.elems.isEmpty then c.open.append(c.close)
        else
            val extend = c.policy.site == ClauseSite.Extend
            val elems  =
                if c.trailingSep && !extend && c.elems.nonEmpty then
                    c.elems.take(c.elems.length - 1) :+ c.elems.last.append(c.sep)
                else c.elems
            end elems
            val preservedBroken = c.policy.configStyle && c.forcedClose &&
                (c.forcedOpen || (!c.policy.dangling && !c.policy.align))
            if extend then
                lowerExtend(c, elems)
            else if preservedBroken then
                lowerBroken(c, elems)
            else if c.policy.align then
                hang(c.open, c.sep, c.close)(elems)
            else if c.policy.binPack != BinPackMode.Never then
                wrap(c.open, c.sep, c.close)(elems)
            else
                val broken = lowerBroken(c, elems)
                val flat   = c.open.append(separate(c.sep.append(singleSpace))(elems)).append(c.close)
                group(choice(flat, broken))
            end if
        end if
    end lowerClause

    private def lowerExtend(c: Clause, elems: Chunk[Doc]): Doc =
        val broken = c.open.append(newline).append(separate(newline)(elems).indent(c.policy.indent))
        val flat   = c.open.append(separate(singleSpace)(elems))
        group(choice(flat, broken))
    end lowerExtend

    private def lowerBroken(c: Clause, elems: Chunk[Doc]): Doc =
        val brokenSep = c.sep.append(newline)
        val inner     = separate(brokenSep)(elems).indent(c.policy.indent)
        val body      = c.open.append(newline).append(inner)
        if c.policy.dangling && (c.close ne Empty) then body.append(newline).append(c.close)
        else body.append(c.close)
    end lowerBroken

    def join(sep: Doc)(elems: Chunk[Doc]): Doc =
        if elems.isEmpty then empty
        else if elems.length == 1 then elems.head
        else
            var acc = elems.head
            var i   = 1
            while i < elems.length do
                acc = acc.append(sep).append(elems(i))
                i += 1
            acc

    def join(sep: Doc)(elems: Doc*): Doc =
        join(sep)(Chunk.from(elems))

    def separate(sep: Doc)(docs: Chunk[Doc]): Doc =
        join(sep)(docs)

    def separate(sep: Doc)(docs: Doc*): Doc =
        join(sep)(Chunk.from(docs))

    def join(start: Doc, sep: Doc, end: Doc)(docs: Chunk[Doc]): Doc =
        enclose(start, sep, end)(docs)

    def join(start: Doc, sep: Doc, end: Doc)(docs: Doc*): Doc =
        enclose(start, sep, end)(Chunk.from(docs))

    def between(open: Doc, body: Doc, close: Doc): Doc =
        open.append(body).append(close)

    def enclose(open: Doc, close: Doc)(body: Doc): Doc =
        between(open, body, close)

    def enclose(open: Doc, sep: Doc, close: Doc)(elems: Chunk[Doc]): Doc =
        if elems.isEmpty then open.append(close)
        else open.append(join(sep)(elems)).append(close)

    def enclose(open: Doc, sep: Doc, close: Doc)(elems: Doc*): Doc =
        enclose(open, sep, close)(Chunk.from(elems))

    def frame(delim: Doc)(elems: Chunk[Doc]): Doc =
        if elems.isEmpty then empty
        else
            var acc = delim
            var i   = 0
            while i < elems.length do
                acc = acc.append(elems(i)).append(delim)
                i += 1
            end while
            acc

    def frame(delim: Doc)(elems: Doc*): Doc =
        frame(delim)(Chunk.from(elems))

    def stack(docs: Chunk[Doc]): Doc =
        join(newline)(docs)

    def stack(docs: Doc*): Doc =
        stack(Chunk.from(docs))

    def lines(docs: Chunk[Doc]): Doc =
        stack(docs)

    def lines(docs: Doc*): Doc =
        stack(Chunk.from(docs))

    def space(docs: Chunk[Doc]): Doc =
        join(singleSpace)(docs)

    def space(first: Doc, rest: Doc*): Doc =
        space(Chunk.from(first +: rest))

    def spaced(docs: Chunk[Doc]): Doc =
        space(docs)

    def spaced(docs: Doc*): Doc =
        space(Chunk.from(docs))

    private def stripTrailingSpace(doc: Doc): Doc =
        doc match
            case Text(s) if s.endsWith(" ") =>
                val trimmed = s.stripTrailing()
                if trimmed.isEmpty then Empty else Text(trimmed)
            case Concat(first, second) =>
                val s2 = stripTrailingSpace(second)
                if s2 eq Empty then stripTrailingSpace(first)
                else Concat(first, s2)
            case _ => doc

    private def resolveSeparators(sep: Doc): (Doc, Doc) =
        sep match
            case Choice(flat, broken) => (broken, flat)
            case Newline              => (Newline, singleSpace)
            case HardNewline          => (HardNewline, singleSpace)
            case Empty                => (newline, Empty)
            case _                    =>
                val stripped = stripTrailingSpace(sep)
                if stripped ne sep then
                    (stripped.append(newline), sep)
                else
                    (sep.append(newline), sep.append(singleSpace))
                end if

    def chop(open: Doc, sep: Doc, close: Doc, indent: Int)(docs: Chunk[Doc]): Doc =
        if docs.isEmpty then open.append(close)
        else
            val (brokenSep, flatSep) = resolveSeparators(sep)
            val brokenInner          =
                if docs.length == 1 then docs.head.indent(indent)
                else separate(brokenSep)(docs).indent(indent)
            val broken = open.append(newline).append(brokenInner).append(newline).append(close)
            val flat   = open.append(separate(flatSep)(docs)).append(close)
            group(choice(flat, broken))

    def chop(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc =
        chop(open, sep, close, 4)(docs)

    def chop(open: Doc, sep: Doc, close: Doc, indent: Int)(first: Doc, rest: Doc*): Doc =
        chop(open, sep, close, indent)(Chunk.from(first +: rest))

    def chop(open: Doc, sep: Doc, close: Doc)(first: Doc, rest: Doc*): Doc =
        chop(open, sep, close, 4)(Chunk.from(first +: rest))

    def chop(open: Doc, sep: Doc, close: Doc)(): Doc =
        open.append(close)

    def chopClosed(open: Doc, sep: Doc, close: Doc, indent: Int)(docs: Chunk[Doc]): Doc =
        if docs.isEmpty then open.append(close)
        else
            val (brokenSep, flatSep) = resolveSeparators(sep)
            val brokenInner          =
                if docs.length == 1 then docs.head.indent(indent)
                else separate(brokenSep)(docs).indent(indent)
            val broken = open.append(newline).append(brokenInner).append(close)
            val flat   = open.append(separate(flatSep)(docs)).append(close)
            group(choice(flat, broken))

    def chopClosed(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc =
        chopClosed(open, sep, close, 4)(docs)

    def chopClosed(open: Doc, sep: Doc, close: Doc, indent: Int)(first: Doc, rest: Doc*): Doc =
        chopClosed(open, sep, close, indent)(Chunk.from(first +: rest))

    def chopClosed(open: Doc, sep: Doc, close: Doc)(first: Doc, rest: Doc*): Doc =
        chopClosed(open, sep, close, 4)(Chunk.from(first +: rest))

    def chopClosed(open: Doc, sep: Doc, close: Doc)(): Doc =
        open.append(close)

    def hang(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc =
        if docs.isEmpty then open.append(close)
        else if docs.length == 1 then open.append(docs.head).append(close)
        else
            val (brokenSep, flatSep) = resolveSeparators(sep)
            val broken               = open.append(align(separate(brokenSep)(docs).append(close)))
            val flat                 = open.append(separate(flatSep)(docs)).append(close)
            group(choice(flat, broken))

    def hang(open: Doc, sep: Doc, close: Doc)(first: Doc, rest: Doc*): Doc =
        hang(open, sep, close)(Chunk.from(first +: rest))

    def hang(open: Doc, sep: Doc, close: Doc)(): Doc =
        open.append(close)

    def wrap(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc =
        if docs.isEmpty then open.append(close)
        else if docs.length == 1 then open.append(docs.head).append(close)
        else
            val (brokenSep, flatSep) = resolveSeparators(sep)
            val softSep              = group(choice(flatSep, brokenSep))
            open.append(align(separate(softSep)(docs))).append(close)

    def wrap(open: Doc, sep: Doc, close: Doc)(first: Doc, rest: Doc*): Doc =
        wrap(open, sep, close)(Chunk.from(first +: rest))

    def wrap(open: Doc, sep: Doc, close: Doc)(): Doc =
        open.append(close)

end Doc

extension (docs: Chunk[Doc])
    def separate(sep: Doc): Doc                                           = Doc.separate(sep)(docs)
    def join(start: Doc, sep: Doc, end: Doc): Doc                         = Doc.join(start, sep, end)(docs)
    def chop(open: Doc, sep: Doc, close: Doc, indent: Int = 4): Doc       = Doc.chop(open, sep, close, indent)(docs)
    def chopClosed(open: Doc, sep: Doc, close: Doc, indent: Int = 4): Doc = Doc.chopClosed(open, sep, close, indent)(docs)
    def hang(open: Doc, sep: Doc, close: Doc): Doc                        = Doc.hang(open, sep, close)(docs)
    def wrap(open: Doc, sep: Doc, close: Doc): Doc                        = Doc.wrap(open, sep, close)(docs)
end extension
