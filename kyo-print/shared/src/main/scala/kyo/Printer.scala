package kyo

/** A pretty-printer engine that renders document layout trees to text or streams.
  *
  * Interprets the abstract syntax tree of a [[Doc]] against a specified maximum column
  * width to choose line breaks, indentation, and alignment. Different layout engines
  * provide alternative trade-offs between rendering speed and layout optimality:
  *
  *   - Greedy: linear-time formatting with local lookahead (Wadler-Lindig)
  *   - Optimal: global search exploring branching choices to minimize page overflow
  *   - Compact: minimal single-line output ignoring indentation
  *
  * Document layout is pure and separated from the document representation itself,
  * enabling the same [[Doc]] tree to be rendered across different width budgets
  * and printing strategies.
  */
trait Printer:

    def print(doc: Doc, width: Int): String

    def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any]

end Printer

/** Companion object providing standard printer strategies.
  *
  * Factory and default instances for document layout algorithms.
  * Built-in implementations include greedy linear-time lookahead,
  * optimal Pareto DAG search, and compact minified output.
  *
  * Custom printers can also be constructed by implementing the [[Printer]]
  * trait to support specialized formatting requirements or domain-specific
  * constraints.
  */
object Printer:

    /** A linear-time pretty-printer using local lookahead (Wadler-Lindig algorithm).
      *
      * Evaluates documents in O(W * N) time where W is the column budget and N
      * is the document size. When encountering a layout choice or grouped sequence,
      * it inspects lookahead up to the current line budget to determine whether to
      * flatten or break.
      */
    val greedy: Printer = GreedyPrinter

    /** A compact printer that produces single-line minified output ignoring indentation.
      *
      * Evaluates documents in flat mode, collapsing soft line breaks to single spaces,
      * selecting flat alternatives for layout choices, and omitting all indentation
      * and column alignment. Hard line breaks remain preserved as newlines without
      * indentation margins.
      *
      * This strategy is suited for minified text, machine-readable output, or single-line
      * serialization where visual indentation is unnecessary.
      */
    val compact: Printer = CompactPrinter

    /** An optimal layout printer using dynamic programming and Pareto frontier search.
      *
      * Evaluates branching layout choices (`Doc.choice` and `Doc.group`) by searching
      * the document structure using Pareto-optimal frontier pruning. Maintains trade-offs
      * between page-width overflow, line breaks, and indentation to guarantee globally
      * optimal layouts across multi-alternative choice trees.
      *
      * Unlike greedy single-pass approaches, this layout engine explores alternatives
      * across sequential concatenations without committing to early local choices.
      * It falls back to secondary alternatives when preferred layouts exceed column constraints.
      */
    val optimal: Printer = OptimalPrinter

    /** A printer encoding the layout decision semantics of the scalafmt
      * formatter.
      *
      * Renders [[Doc.Clause]] nodes natively by resolving their site policy at
      * layout time: a clause stays flat when it fits, otherwise it breaks per
      * its dangling, align, and bin-pack policy, with continuation lines
      * indented relative to the clause's base and nested clauses stacking
      * their indents. Other documents are rendered greedily. Validation runs
      * against fixtures committed by scalafmt's own test suite; the generic
      * printers only approximate clauses through their Wadler lowering.
      */
    val scalafmt: Printer = ScalafmtPrinter

    private[kyo] def hasAlignments(doc: Doc): Boolean =
        doc match
            case _: Doc.Alignment    => true
            case Doc.Concat(a, b)    => hasAlignments(a) || hasAlignments(b)
            case Doc.Indent(_, d)    => hasAlignments(d)
            case Doc.Align(d)        => hasAlignments(d)
            case Doc.Group(d, _)     => hasAlignments(d)
            case Doc.Choice(a, b, _) =>
                hasAlignments(a) || hasAlignments(b)
            case Doc.Clause(_, o, c, es, s, _, _, _) =>
                hasAlignments(o) || hasAlignments(c) || hasAlignments(s) || es.exists(hasAlignments)
            case _ => false

    private[kyo] def computeAlignments(
        doc: Doc,
        entranceCol: Int,
        entranceIndent: Int
    ): Map[Int, Int] =
        if !hasAlignments(doc) then Map.empty
        else
            var targets       = Map.empty[Int, Int]
            var changed       = true
            var iterations    = 0
            val maxIterations = 16

            while changed && iterations < maxIterations do
                iterations += 1
                var measured = Map.empty[Int, Int]
                var col      = entranceCol
                var indent   = entranceIndent

                def measure(d: Doc): Unit =
                    d match
                        case Doc.Empty      => ()
                        case Doc.Text(text) =>
                            col += text.length
                        case Doc.Newline =>
                            col = indent
                        case Doc.HardNewline =>
                            col = indent
                        case Doc.Indent(spaces, child) =>
                            val oldIndent = indent
                            indent += spaces
                            measure(child)
                            indent = oldIndent
                        case Doc.Align(child) =>
                            val oldIndent = indent
                            indent = col
                            measure(child)
                            indent = oldIndent
                        case Doc.Concat(first, second) =>
                            measure(first)
                            measure(second)
                        case Doc.Group(child, _) =>
                            measure(child)
                        case Doc.Choice(flat, broken, _) =>
                            measure(broken)
                        case Doc.Alignment(id, offset) =>
                            val currentTarget = targets.getOrElse(id, 0)
                            val colBefore     = col
                            val prevMax       = measured.getOrElse(id, colBefore)
                            measured = measured.updated(id, math.max(prevMax, colBefore))
                            val aligned = math.max(colBefore, currentTarget + offset)
                            col = aligned
                        case c: Doc.Clause =>
                            measure(Doc.lowerClause(c))
                end measure

                measure(doc)

                if measured == targets then
                    changed = false
                else
                    targets = measured
                end if
            end while

            targets
    end computeAlignments

    private case class Work(
        indent: Int,
        isFlat: Boolean,
        doc: Doc,
        alignTargets: Map[Int, Int] = Map.empty,
        groupDecisions: Map[Int, Boolean] = Map.empty
    )

    private case class StreamState(work: List[Work], col: Int, pendingIndent: Int)

    private object GreedyPrinter extends Printer:

        @scala.annotation.tailrec
        private def fits(budget: Int, work: List[Work]): Boolean =
            if budget < 0 then false
            else
                work match
                    case Nil          => true
                    case head :: rest =>
                        head.doc match
                            case Doc.Empty =>
                                fits(budget, rest)
                            case Doc.Text(text) =>
                                val len = text.length
                                if len > budget then false
                                else fits(budget - len, rest)
                            case Doc.Newline =>
                                if head.isFlat then
                                    if budget < 1 then false
                                    else fits(budget - 1, rest)
                                else
                                    true
                            case Doc.HardNewline =>
                                true
                            case Doc.Indent(spaces, inner) =>
                                fits(budget, Work(head.indent + spaces, head.isFlat, inner, head.alignTargets, head.groupDecisions) :: rest)
                            case Doc.Align(inner) =>
                                val innerTargets = computeAlignments(inner, 0, 0)
                                val nextTargets  = if innerTargets.isEmpty then head.alignTargets else head.alignTargets ++ innerTargets
                                fits(budget, Work(head.indent, head.isFlat, inner, nextTargets, head.groupDecisions) :: rest)
                            case Doc.Concat(first, second) =>
                                fits(
                                    budget,
                                    Work(head.indent, head.isFlat, first, head.alignTargets, head.groupDecisions) ::
                                        Work(head.indent, head.isFlat, second, head.alignTargets, head.groupDecisions) :: rest
                                )
                            case Doc.Group(inner, gid) =>
                                val nextDecisions =
                                    if gid >= 0 then
                                        head.groupDecisions.get(gid) match
                                            case Some(_) => head.groupDecisions
                                            case None    => head.groupDecisions.updated(gid, head.isFlat)
                                    else
                                        head.groupDecisions
                                fits(budget, Work(head.indent, head.isFlat, inner, head.alignTargets, nextDecisions) :: rest)
                            case Doc.Choice(flat, broken, gid) =>
                                val chooseFlat =
                                    if gid >= 0 then
                                        head.groupDecisions.get(gid) match
                                            case Some(isFlat) => isFlat
                                            case None         => head.isFlat
                                    else
                                        head.isFlat
                                if chooseFlat then
                                    fits(budget, Work(head.indent, true, flat, head.alignTargets, head.groupDecisions) :: rest)
                                else
                                    fits(budget, Work(head.indent, false, broken, head.alignTargets, head.groupDecisions) :: rest)
                                end if
                            case Doc.Alignment(id, offset) =>
                                val spaces = head.alignTargets.get(id) match
                                    case Some(t) => math.max(0, t + offset)
                                    case None    => math.max(0, offset)
                                fits(budget - spaces, rest)
                            case c: Doc.Clause =>
                                fits(
                                    budget,
                                    Work(head.indent, head.isFlat, Doc.lowerClause(c), head.alignTargets, head.groupDecisions) :: rest
                                )

        def print(doc: Doc, width: Int): String =
            if doc eq Doc.Empty then ""
            else
                val sb            = new java.lang.StringBuilder()
                var col           = 0
                var pendingIndent = -1
                var work          = Work(0, false, doc) :: Nil

                while work.nonEmpty do
                    val head = work.head
                    work = work.tail

                    head.doc match
                        case Doc.Empty => ()

                        case Doc.Text(text) =>
                            if pendingIndent >= 0 then
                                val spaces = math.max(0, pendingIndent)
                                var i      = 0
                                while i < spaces do
                                    sb.append(' ')
                                    i += 1
                                end while
                                col += spaces
                                pendingIndent = -1
                            end if
                            sb.append(text)
                            col += text.length

                        case Doc.Newline =>
                            if head.isFlat then
                                if pendingIndent >= 0 then
                                    val spaces = math.max(0, pendingIndent)
                                    var i      = 0
                                    while i < spaces do
                                        sb.append(' ')
                                        i += 1
                                    end while
                                    col += spaces
                                    pendingIndent = -1
                                end if
                                sb.append(' ')
                                col += 1
                            else
                                sb.append('\n')
                                col = head.indent
                                pendingIndent = head.indent
                            end if

                        case Doc.HardNewline =>
                            sb.append('\n')
                            col = head.indent
                            pendingIndent = head.indent

                        case Doc.Indent(spaces, inner) =>
                            val newIndent = head.indent + spaces
                            if pendingIndent >= 0 then
                                pendingIndent = newIndent
                                col = newIndent
                            end if
                            work = Work(newIndent, head.isFlat, inner, head.alignTargets, head.groupDecisions) :: work

                        case Doc.Align(inner) =>
                            val innerTargets = computeAlignments(inner, col, col)
                            val nextTargets  = if innerTargets.isEmpty then head.alignTargets else head.alignTargets ++ innerTargets
                            work = Work(col, head.isFlat, inner, nextTargets, head.groupDecisions) :: work

                        case Doc.Concat(first, second) =>
                            work = Work(head.indent, head.isFlat, first, head.alignTargets, head.groupDecisions) ::
                                Work(head.indent, head.isFlat, second, head.alignTargets, head.groupDecisions) :: work

                        case Doc.Choice(flat, broken, gid) =>
                            val chooseFlat =
                                if gid >= 0 then
                                    head.groupDecisions.get(gid) match
                                        case Some(isFlat) => isFlat
                                        case None         => head.isFlat
                                else
                                    head.isFlat
                            if chooseFlat then
                                work = Work(head.indent, true, flat, head.alignTargets, head.groupDecisions) :: work
                            else
                                work = Work(head.indent, false, broken, head.alignTargets, head.groupDecisions) :: work
                            end if

                        case Doc.Alignment(id, offset) =>
                            val targetCol = head.alignTargets.get(id) match
                                case Some(t) => t + offset
                                case None    => col + math.max(0, offset)
                            val spaces = math.max(0, targetCol - col)
                            if spaces > 0 then
                                if pendingIndent >= 0 then
                                    val indentSpaces = math.max(0, pendingIndent)
                                    var i            = 0
                                    while i < indentSpaces do
                                        sb.append(' ')
                                        i += 1
                                    end while
                                    col += indentSpaces
                                    pendingIndent = -1
                                end if
                                var i = 0
                                while i < spaces do
                                    sb.append(' ')
                                    i += 1
                                end while
                                col += spaces
                            end if

                        case c: Doc.Clause =>
                            work = Work(head.indent, head.isFlat, Doc.lowerClause(c), head.alignTargets, head.groupDecisions) :: work

                        case Doc.Group(inner, gid) =>
                            if head.isFlat then
                                val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                                work = Work(head.indent, true, inner, head.alignTargets, nextDecisions) :: work
                            else
                                val testDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                                val flatFits = fits(width - col, Work(head.indent, true, inner, head.alignTargets, testDecisions) :: work)
                                if flatFits then
                                    val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                                    work = Work(head.indent, true, inner, head.alignTargets, nextDecisions) :: work
                                else
                                    val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, false) else head.groupDecisions
                                    work = Work(head.indent, false, inner, head.alignTargets, nextDecisions) :: work
                                end if
                            end if
                    end match
                end while

                sb.toString
            end if
        end print

        private def formatNextLine(state: StreamState, width: Int): (String, Option[StreamState]) =
            val sb                             = new java.lang.StringBuilder()
            var col                            = state.col
            var pendingIndent                  = state.pendingIndent
            var work                           = state.work
            var finishedLine                   = false
            var nextState: Option[StreamState] = None

            while work.nonEmpty && !finishedLine do
                val head = work.head
                work = work.tail

                head.doc match
                    case Doc.Empty => ()

                    case Doc.Text(text) =>
                        if pendingIndent >= 0 then
                            val spaces = math.max(0, pendingIndent)
                            var i      = 0
                            while i < spaces do
                                sb.append(' ')
                                i += 1
                            end while
                            col += spaces
                            pendingIndent = -1
                        end if
                        sb.append(text)
                        col += text.length

                    case Doc.Newline =>
                        if head.isFlat then
                            if pendingIndent >= 0 then
                                val spaces = math.max(0, pendingIndent)
                                var i      = 0
                                while i < spaces do
                                    sb.append(' ')
                                    i += 1
                                end while
                                col += spaces
                                pendingIndent = -1
                            end if
                            sb.append(' ')
                            col += 1
                        else
                            sb.append('\n')
                            col = head.indent
                            pendingIndent = head.indent
                            finishedLine = true
                            nextState = Some(StreamState(work, col, pendingIndent))
                        end if

                    case Doc.HardNewline =>
                        sb.append('\n')
                        col = head.indent
                        pendingIndent = head.indent
                        finishedLine = true
                        nextState = Some(StreamState(work, col, pendingIndent))

                    case Doc.Indent(spaces, inner) =>
                        val newIndent = head.indent + spaces
                        if pendingIndent >= 0 then
                            pendingIndent = newIndent
                            col = newIndent
                        end if
                        work = Work(newIndent, head.isFlat, inner, head.alignTargets, head.groupDecisions) :: work

                    case Doc.Align(inner) =>
                        val innerTargets = computeAlignments(inner, col, col)
                        val nextTargets  = if innerTargets.isEmpty then head.alignTargets else head.alignTargets ++ innerTargets
                        work = Work(col, head.isFlat, inner, nextTargets, head.groupDecisions) :: work

                    case Doc.Concat(first, second) =>
                        work = Work(head.indent, head.isFlat, first, head.alignTargets, head.groupDecisions) ::
                            Work(head.indent, head.isFlat, second, head.alignTargets, head.groupDecisions) :: work

                    case Doc.Choice(flat, broken, gid) =>
                        val chooseFlat =
                            if gid >= 0 then
                                head.groupDecisions.get(gid) match
                                    case Some(isFlat) => isFlat
                                    case None         => head.isFlat
                            else
                                head.isFlat
                        if chooseFlat then
                            work = Work(head.indent, true, flat, head.alignTargets, head.groupDecisions) :: work
                        else
                            work = Work(head.indent, false, broken, head.alignTargets, head.groupDecisions) :: work
                        end if

                    case Doc.Alignment(id, offset) =>
                        val targetCol = head.alignTargets.get(id) match
                            case Some(t) => t + offset
                            case None    => col + math.max(0, offset)
                        val spaces = math.max(0, targetCol - col)
                        if spaces > 0 then
                            if pendingIndent >= 0 then
                                val indentSpaces = math.max(0, pendingIndent)
                                var i            = 0
                                while i < indentSpaces do
                                    sb.append(' ')
                                    i += 1
                                end while
                                col += indentSpaces
                                pendingIndent = -1
                            end if
                            var i = 0
                            while i < spaces do
                                sb.append(' ')
                                i += 1
                            end while
                            col += spaces
                        end if

                    case c: Doc.Clause =>
                        work = Work(head.indent, head.isFlat, Doc.lowerClause(c), head.alignTargets, head.groupDecisions) :: work

                    case Doc.Group(inner, gid) =>
                        if head.isFlat then
                            val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                            work = Work(head.indent, true, inner, head.alignTargets, nextDecisions) :: work
                        else
                            val testDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                            val flatFits      = fits(width - col, Work(head.indent, true, inner, head.alignTargets, testDecisions) :: work)
                            if flatFits then
                                val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, true) else head.groupDecisions
                                work = Work(head.indent, true, inner, head.alignTargets, nextDecisions) :: work
                            else
                                val nextDecisions = if gid >= 0 then head.groupDecisions.updated(gid, false) else head.groupDecisions
                                work = Work(head.indent, false, inner, head.alignTargets, nextDecisions) :: work
                            end if
                        end if
                end match
            end while

            if finishedLine then
                (sb.toString, nextState)
            else
                (sb.toString, None)
            end if
        end formatNextLine

        def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
            if doc eq Doc.Empty then
                Stream.empty
            else
                Stream:
                    Loop(StreamState(Work(0, false, doc) :: Nil, 0, -1)) { state =>
                        if state.work.isEmpty then
                            Loop.done
                        else
                            val (line, nextStateOpt) = formatNextLine(state, width)
                            nextStateOpt match
                                case Some(nextState) =>
                                    Emit.valueWith(Chunk(line))(Loop.continue(nextState))
                                case None =>
                                    if line.nonEmpty then
                                        Emit.valueWith(Chunk(line))(Loop.done)
                                    else
                                        Loop.done
                            end match
                        end if
                    }
            end if
        end printStream

    end GreedyPrinter

    private object CompactPrinter extends Printer:

        def print(doc: Doc, width: Int): String =
            if doc eq Doc.Empty then ""
            else
                val sb   = new java.lang.StringBuilder()
                var work = doc :: Nil

                while work.nonEmpty do
                    val head = work.head
                    work = work.tail

                    head match
                        case Doc.Empty => ()

                        case Doc.Text(text) =>
                            sb.append(text)
                            ()

                        case Doc.Newline =>
                            sb.append(' ')
                            ()

                        case Doc.HardNewline =>
                            sb.append('\n')
                            ()

                        case Doc.Indent(_, inner) =>
                            work = inner :: work

                        case Doc.Align(inner) =>
                            work = inner :: work

                        case Doc.Concat(first, second) =>
                            work = first :: second :: work

                        case Doc.Group(inner, _) =>
                            work = inner :: work

                        case Doc.Choice(flat, _, _) =>
                            work = flat :: work

                        case Doc.Alignment(_, _) => ()

                        case c: Doc.Clause =>
                            work = Doc.lowerClause(c) :: work
                    end match
                end while

                sb.toString
            end if
        end print

        private def formatNextLine(initialWork: List[Doc]): (String, List[Doc]) =
            val sb           = new java.lang.StringBuilder()
            var work         = initialWork
            var finishedLine = false

            while work.nonEmpty && !finishedLine do
                val head = work.head
                work = work.tail

                head match
                    case Doc.Empty => ()

                    case Doc.Text(text) =>
                        sb.append(text)
                        ()

                    case Doc.Newline =>
                        sb.append(' ')
                        ()

                    case Doc.HardNewline =>
                        sb.append('\n')
                        finishedLine = true

                    case Doc.Indent(_, inner) =>
                        work = inner :: work

                    case Doc.Align(inner) =>
                        work = inner :: work

                    case Doc.Concat(first, second) =>
                        work = first :: second :: work

                    case Doc.Group(inner, _) =>
                        work = inner :: work

                    case Doc.Choice(flat, _, _) =>
                        work = flat :: work

                    case Doc.Alignment(_, _) => ()

                    case c: Doc.Clause =>
                        work = Doc.lowerClause(c) :: work
                end match
            end while

            (sb.toString, work)
        end formatNextLine

        def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
            if doc eq Doc.Empty then
                Stream.empty
            else
                Stream:
                    Loop(doc :: Nil) { work =>
                        if work.isEmpty then
                            Loop.done
                        else
                            val (line, nextWork) = formatNextLine(work)
                            if nextWork.nonEmpty then
                                Emit.valueWith(Chunk(line))(Loop.continue(nextWork))
                            else if line.nonEmpty then
                                Emit.valueWith(Chunk(line))(Loop.done)
                            else
                                Loop.done
                            end if
                        end if
                    }
            end if
        end printStream

    end CompactPrinter

    private object OptimalPrinter extends Printer:

        sealed private trait Tok derives CanEqual
        private case class TText(text: String)  extends Tok
        private case class TIndent(spaces: Int) extends Tok
        private case class TLine()              extends Tok

        private case class Cost(overflow: Long, lines: Int):
            def +(other: Cost): Cost =
                Cost(overflow + other.overflow, lines + other.lines)

            def <=(other: Cost): Boolean =
                if overflow != other.overflow then overflow < other.overflow
                else lines <= other.lines

            def <(other: Cost): Boolean =
                if overflow != other.overflow then overflow < other.overflow
                else lines < other.lines
        end Cost

        private case class Measure(last: Int, pendingIndent: Int, cost: Cost, tokens: List[Tok])

        private def dominates(m1: Measure, m2: Measure): Boolean =
            m1.last <= m2.last && (m1.cost <= m2.cost)

        private def merge(ms1: List[Measure], ms2: List[Measure]): List[Measure] =
            @scala.annotation.tailrec
            def loop(l1: List[Measure], l2: List[Measure], acc: List[Measure]): List[Measure] =
                (l1, l2) match
                    case (Nil, _)                   => acc.reverse ::: l2
                    case (_, Nil)                   => acc.reverse ::: l1
                    case (m1 :: rest1, m2 :: rest2) =>
                        if dominates(m1, m2) then
                            loop(l1, rest2, acc)
                        else if dominates(m2, m1) then
                            loop(rest1, l2, acc)
                        else if m1.last > m2.last then
                            loop(rest1, l2, m1 :: acc)
                        else
                            loop(l1, rest2, m2 :: acc)
            loop(ms1, ms2, Nil)
        end merge

        private def concatMeasure(m1: Measure, m2: Measure): Measure =
            Measure(m2.last, m2.pendingIndent, m1.cost + m2.cost, m1.tokens ::: m2.tokens)

        private def processConcat(
            ms1: List[Measure],
            processRight: Measure => List[Measure]
        ): List[Measure] =
            def doOne(m1: Measure): List[Measure] =
                val ms2 = processRight(m1)
                if ms2.isEmpty then Nil
                else
                    var currentBest = concatMeasure(m1, ms2.head)
                    var result      = List.empty[Measure]
                    var rest        = ms2.tail
                    while rest.nonEmpty do
                        val current = concatMeasure(m1, rest.head)
                        if current.cost <= currentBest.cost then
                            currentBest = current
                        else
                            result = currentBest :: result
                            currentBest = current
                        end if
                        rest = rest.tail
                    end while
                    (currentBest :: result).reverse
                end if
            end doOne

            def foldRight(ms: List[Measure]): List[Measure] =
                ms match
                    case Nil          => Nil
                    case head :: Nil  => doOne(head)
                    case head :: tail =>
                        merge(doOne(head), foldRight(tail))
            foldRight(ms1)
        end processConcat

        private def textCost(c: Int, len: Int, width: Int): Cost =
            val stop = c + len
            if stop > width then
                val maxwc = math.max(width, c)
                val a     = (maxwc - width).toLong
                val b     = (stop - maxwc).toLong
                Cost(b * (2 * a + b), 0)
            else
                Cost(0, 0)
            end if
        end textCost

        private def newlineCost: Cost =
            Cost(0, 1)

        sealed private trait Mode derives CanEqual
        private object Mode:
            case object General     extends Mode
            case object Flat        extends Mode
            case object BrokenGroup extends Mode
        end Mode

        private case class MemoKey(
            doc: Doc,
            col: Int,
            indent: Int,
            pendingIndent: Int,
            mode: Mode,
            alignTargets: Map[Int, Int],
            groupDecisions: Map[Int, Boolean]
        )

        private def resolve(
            doc: Doc,
            col: Int,
            indent: Int,
            pendingIndent: Int,
            mode: Mode,
            width: Int,
            memo: java.util.HashMap[MemoKey, List[Measure]],
            alignTargets: Map[Int, Int],
            groupDecisions: Map[Int, Boolean]
        ): List[Measure] =
            val key = MemoKey(doc, col, indent, pendingIndent, mode, alignTargets, groupDecisions)
            val hit = memo.get(key)
            if hit != null then hit
            else
                val res = doc match
                    case Doc.Empty =>
                        List(Measure(col, pendingIndent, Cost(0, 0), Nil))

                    case Doc.Text(text) =>
                        val len = text.length
                        if pendingIndent >= 0 then
                            val c    = pendingIndent
                            val cost = textCost(c, len, width)
                            val toks = if c > 0 then List(TIndent(c), TText(text)) else List(TText(text))
                            List(Measure(c + len, -1, cost, toks))
                        else
                            val cost = textCost(col, len, width)
                            List(Measure(col + len, -1, cost, List(TText(text))))
                        end if

                    case Doc.Newline =>
                        if mode == Mode.Flat then
                            if pendingIndent >= 0 then
                                val c    = pendingIndent
                                val cost = textCost(c, 1, width)
                                val toks = if c > 0 then List(TIndent(c), TText(" ")) else List(TText(" "))
                                List(Measure(c + 1, -1, cost, toks))
                            else
                                val cost = textCost(col, 1, width)
                                List(Measure(col + 1, -1, cost, List(TText(" "))))
                        else
                            List(Measure(indent, indent, newlineCost, List(TLine())))
                        end if

                    case Doc.HardNewline =>
                        List(Measure(indent, indent, newlineCost, List(TLine())))

                    case Doc.Indent(spaces, inner) =>
                        val newIndent  = indent + spaces
                        val newPending = if pendingIndent >= 0 then newIndent else -1
                        val newCol     = if pendingIndent >= 0 then newIndent else col
                        resolve(inner, newCol, newIndent, newPending, mode, width, memo, alignTargets, groupDecisions)

                    case Doc.Align(inner) =>
                        val alignCol     = if pendingIndent >= 0 then pendingIndent else col
                        val innerTargets = computeAlignments(inner, alignCol, alignCol)
                        val nextTargets  = if innerTargets.isEmpty then alignTargets else alignTargets ++ innerTargets
                        resolve(inner, col, alignCol, pendingIndent, mode, width, memo, nextTargets, groupDecisions)

                    case Doc.Concat(first, second) =>
                        val leftMs = resolve(first, col, indent, pendingIndent, mode, width, memo, alignTargets, groupDecisions)
                        processConcat(
                            leftMs,
                            m1 => resolve(second, m1.last, indent, m1.pendingIndent, mode, width, memo, alignTargets, groupDecisions)
                        )

                    case Doc.Group(inner, id) =>
                        mode match
                            case Mode.Flat =>
                                val flatDecisions = if id >= 0 then groupDecisions.updated(id, true) else groupDecisions
                                resolve(inner, col, indent, pendingIndent, Mode.Flat, width, memo, alignTargets, flatDecisions)
                            case _ =>
                                val flatDecisions   = if id >= 0 then groupDecisions.updated(id, true) else groupDecisions
                                val brokenDecisions = if id >= 0 then groupDecisions.updated(id, false) else groupDecisions
                                val flatMs          =
                                    resolve(inner, col, indent, pendingIndent, Mode.Flat, width, memo, alignTargets, flatDecisions)
                                val fittingFlat = flatMs.filter(_.cost.overflow == 0)
                                val brokenMs    =
                                    resolve(inner, col, indent, pendingIndent, Mode.BrokenGroup, width, memo, alignTargets, brokenDecisions)
                                if fittingFlat.nonEmpty then
                                    merge(brokenMs, fittingFlat)
                                else
                                    brokenMs
                                end if

                    case Doc.Choice(flat, broken, gid) =>
                        val fixed = if gid >= 0 then groupDecisions.get(gid) else None
                        fixed match
                            case Some(true) =>
                                resolve(flat, col, indent, pendingIndent, mode, width, memo, alignTargets, groupDecisions)
                            case Some(false) =>
                                resolve(broken, col, indent, pendingIndent, mode, width, memo, alignTargets, groupDecisions)
                            case None =>
                                mode match
                                    case Mode.Flat =>
                                        resolve(flat, col, indent, pendingIndent, Mode.Flat, width, memo, alignTargets, groupDecisions)
                                    case Mode.BrokenGroup =>
                                        resolve(
                                            broken,
                                            col,
                                            indent,
                                            pendingIndent,
                                            Mode.BrokenGroup,
                                            width,
                                            memo,
                                            alignTargets,
                                            groupDecisions
                                        )
                                    case Mode.General =>
                                        val flatMs = resolve(
                                            flat,
                                            col,
                                            indent,
                                            pendingIndent,
                                            Mode.General,
                                            width,
                                            memo,
                                            alignTargets,
                                            groupDecisions
                                        )
                                        val flatBest = flatMs.minBy(_.cost.overflow)
                                        if flatBest.cost.overflow == 0 then
                                            flatMs
                                        else
                                            val brokenMs =
                                                resolve(
                                                    broken,
                                                    col,
                                                    indent,
                                                    pendingIndent,
                                                    Mode.General,
                                                    width,
                                                    memo,
                                                    alignTargets,
                                                    groupDecisions
                                                )
                                            val brokenBest = brokenMs.minBy(_.cost.overflow)
                                            if brokenBest.cost.overflow < flatBest.cost.overflow then
                                                brokenMs
                                            else
                                                flatMs
                                            end if
                                        end if
                        end match

                    case a: Doc.Alignment =>
                        val targetCol = alignTargets.get(a.id) match
                            case Some(t) => t + a.offset
                            case None    => col + math.max(0, a.offset)
                        val spaces = math.max(0, targetCol - col)
                        val newCol = col + spaces
                        val cost   = textCost(col, spaces, width)
                        val tok    = if spaces > 0 then TText(" " * spaces) :: Nil else Nil
                        List(Measure(newCol, pendingIndent, cost, tok))

                    case c: Doc.Clause =>
                        resolve(Doc.lowerClause(c), col, indent, pendingIndent, mode, width, memo, alignTargets, groupDecisions)
                memo.put(key, res)
                res
            end if
        end resolve

        private def getTokens(doc: Doc, width: Int): List[Tok] =
            if doc eq Doc.Empty then Nil
            else
                val memo = new java.util.HashMap[MemoKey, List[Measure]]()
                val ms   = resolve(doc, 0, 0, -1, Mode.General, width, memo, Map.empty, Map.empty)
                if ms.isEmpty then Nil
                else
                    var best = ms.head
                    var rest = ms.tail
                    while rest.nonEmpty do
                        if rest.head.cost < best.cost then
                            best = rest.head
                        end if
                        rest = rest.tail
                    end while
                    best.tokens
                end if
        end getTokens

        def print(doc: Doc, width: Int): String =
            if doc eq Doc.Empty then ""
            else
                val tokens = getTokens(doc, width)
                val sb     = new java.lang.StringBuilder()
                var toks   = tokens
                while toks.nonEmpty do
                    toks.head match
                        case TText(s) =>
                            sb.append(s)
                            ()
                        case TIndent(spaces) =>
                            var i = 0
                            while i < spaces do
                                sb.append(' ')
                                i += 1
                            end while
                        case TLine() =>
                            sb.append('\n')
                            ()
                    end match
                    toks = toks.tail
                end while
                sb.toString
            end if
        end print

        private def formatNextLine(tokens: List[Tok]): (String, List[Tok]) =
            val sb           = new java.lang.StringBuilder()
            var toks         = tokens
            var finishedLine = false

            while toks.nonEmpty && !finishedLine do
                val head = toks.head
                toks = toks.tail
                head match
                    case TText(s) =>
                        sb.append(s)
                        ()
                    case TIndent(spaces) =>
                        var i = 0
                        while i < spaces do
                            sb.append(' ')
                            i += 1
                        end while
                    case TLine() =>
                        sb.append('\n')
                        finishedLine = true
                end match
            end while

            (sb.toString, toks)
        end formatNextLine

        def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
            if doc eq Doc.Empty then
                Stream.empty
            else
                Stream:
                    Loop(getTokens(doc, width)) { toks =>
                        if toks.isEmpty then
                            Loop.done
                        else
                            val (line, nextToks) = formatNextLine(toks)
                            if nextToks.nonEmpty then
                                Emit.valueWith(Chunk(line))(Loop.continue(nextToks))
                            else if line.nonEmpty then
                                Emit.valueWith(Chunk(line))(Loop.done)
                            else
                                Loop.done
                            end if
                        end if
                    }
            end if
        end printStream

    end OptimalPrinter

end Printer
