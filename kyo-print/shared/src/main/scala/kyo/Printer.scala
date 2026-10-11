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

    private case class Work(indent: Int, isFlat: Boolean, doc: Doc)

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
                                fits(budget, Work(head.indent + spaces, head.isFlat, inner) :: rest)
                            case Doc.Align(inner) =>
                                fits(budget, Work(head.indent, head.isFlat, inner) :: rest)
                            case Doc.Concat(first, second) =>
                                fits(budget, Work(head.indent, head.isFlat, first) :: Work(head.indent, head.isFlat, second) :: rest)
                            case Doc.Group(inner) =>
                                fits(budget, Work(head.indent, head.isFlat, inner) :: rest)
                            case Doc.Choice(broken, flat) =>
                                if head.isFlat then
                                    fits(budget, Work(head.indent, true, flat) :: rest)
                                else
                                    fits(budget, Work(head.indent, false, broken) :: rest)

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
                            work = Work(newIndent, head.isFlat, inner) :: work

                        case Doc.Align(inner) =>
                            work = Work(col, head.isFlat, inner) :: work

                        case Doc.Concat(first, second) =>
                            work = Work(head.indent, head.isFlat, first) :: Work(head.indent, head.isFlat, second) :: work

                        case Doc.Choice(broken, flat) =>
                            if head.isFlat then
                                work = Work(head.indent, true, flat) :: work
                            else
                                work = Work(head.indent, false, broken) :: work
                            end if

                        case Doc.Group(inner) =>
                            if head.isFlat then
                                work = Work(head.indent, true, inner) :: work
                            else
                                val flatFits = fits(width - col, Work(head.indent, true, inner) :: work)
                                if flatFits then
                                    work = Work(head.indent, true, inner) :: work
                                else
                                    work = Work(head.indent, false, inner) :: work
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
                        work = Work(newIndent, head.isFlat, inner) :: work

                    case Doc.Align(inner) =>
                        work = Work(col, head.isFlat, inner) :: work

                    case Doc.Concat(first, second) =>
                        work = Work(head.indent, head.isFlat, first) :: Work(head.indent, head.isFlat, second) :: work

                    case Doc.Choice(broken, flat) =>
                        if head.isFlat then
                            work = Work(head.indent, true, flat) :: work
                        else
                            work = Work(head.indent, false, broken) :: work
                        end if

                    case Doc.Group(inner) =>
                        if head.isFlat then
                            work = Work(head.indent, true, inner) :: work
                        else
                            val flatFits = fits(width - col, Work(head.indent, true, inner) :: work)
                            if flatFits then
                                work = Work(head.indent, true, inner) :: work
                            else
                                work = Work(head.indent, false, inner) :: work
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

end Printer
