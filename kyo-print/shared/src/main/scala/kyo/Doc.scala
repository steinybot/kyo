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
  */
sealed trait Doc derives CanEqual:

    def append(that: Doc): Doc =
        if this eq Doc.Empty then that
        else if that eq Doc.Empty then this
        else Doc.Concat(this, that)

    def indent(spaces: Int = 4): Doc =
        Doc.indent(spaces, this)

    def fallback(that: Doc): Doc =
        Doc.choice(this, that)

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
    case class Choice(broken: Doc, flat: Doc)  extends Doc

    val empty: Doc = Empty

    val space: Doc = Text(" ")

    val newline: Doc = Newline

    val hardNewline: Doc = HardNewline

    def text(str: String): Doc =
        if str.isEmpty then Empty
        else Text(str)

    def char(c: Char): Doc =
        if c == ' ' then space
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

    def choice(broken: Doc, flat: Doc): Doc =
        if broken == flat then broken
        else Choice(broken, flat)

    def choice(alts: Chunk[Doc]): Doc =
        if alts.isEmpty then empty
        else if alts.length == 1 then alts.head
        else alts.reduceRight(choice)

    def choice(alts: Doc*): Doc =
        choice(Chunk.from(alts))

end Doc
