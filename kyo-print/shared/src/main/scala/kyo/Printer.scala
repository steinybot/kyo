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

    def printStream(doc: Doc, width: Int): Stream[String, Any]

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
object Printer
