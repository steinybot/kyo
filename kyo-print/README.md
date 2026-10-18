<!-- doctest:setup
```scala
import kyo.*

case class Param(name: String, tpe: String)
case class Method(name: String, params: Chunk[Param], returnType: String, body: Chunk[String])
case class Call(fn: String, args: Chunk[String])

val args = Chunk(Doc.text("alpha"), Doc.text("beta"), Doc.text("gamma"))
```
-->

# kyo-print

A `Doc` is a description of a layout, not a string. You build an immutable tree of text, indentation, line breaks, and grouped alternatives; nothing decides where lines break until a `Printer` renders the tree against a column budget. The same `Doc` renders wide, narrow, or fully minified without being rebuilt. The vocabulary is small: soft line breaks (`Doc.newline`) collapse to a single space when their enclosing group fits; hard line breaks (`Doc.hardNewline`) always emit a newline; `Doc.group` marks a region the printer may flatten as a unit; `Doc.choice` offers explicit alternative layouts that the printer selects between; `Doc.indent` and `Doc.align` control where broken lines continue.

Documents are usually authored with the `doc` string interpolator, whose source indentation is normalized away so templates read like their output, and rendered with one of three engines: `Printer.greedy` (linear-time Wadler-Lindig lookahead), `Printer.optimal` (dynamic-programming search minimizing overflow, then line count), or `Printer.compact` (minified single-line output). For code-generation work that must reproduce scalafmt's documented break styles, `ScalafmtConfig` models the relevant option subset (max column, per-site indents, dangling parens, open-paren alignment, bin-packing) as an inert configuration value; user code either maps it to the bracketing combinators or hands it to `Printer.scalafmt`, the parity engine that resolves the same options at layout time over policy-carrying clauses.

The module compiles for JVM, JavaScript, Scala Native, and Wasm from one shared source set and depends only on `kyo-prelude`.

```scala
val greeting = Doc.group(
    Doc.text("Hello,").append(Doc.newline).append(Doc.text("world"))
)

assert(Printer.greedy.print(greeting, 80) == "Hello, world")
assert(Printer.greedy.print(greeting, 5) == "Hello,\nworld")
```

## Documents as layout values

Reach for a `Doc` whenever produced text must adapt to a column budget: generated code, structured logs, diagnostics, CLI help. Instead of formatting strings eagerly and hand-rolling line breaks, you describe the content once, mark where breaks may occur, and let a printer place them for each width. Because the tree is an immutable value, the same document can be rendered at 120 columns for a wide terminal, 40 for a narrow one, and minified for a machine reader, all from one construction.

`Doc` is a sealed trait with nine public cases, so the tree is also pattern-matchable when a consumer needs to inspect or transform a layout:

```scala
import kyo.*

def widthOf(doc: Doc): Int = doc match
    case Doc.Empty        => 0
    case Doc.Text(text)   => text.length
    case Doc.Indent(_, d) => widthOf(d)
    case Doc.Align(d)     => widthOf(d)
    case Doc.Concat(a, b) => widthOf(a) + widthOf(b)
    case Doc.Group(d, _)     => widthOf(d)
    case Doc.Choice(_, _, _) => 0 // decided by the printer
    case Doc.Newline      => 1
    case Doc.HardNewline  => 0

assert(widthOf(Doc.text("abc")) == 3)
```

> **Note:** The companion object's constructors normalize trivial cases (`Doc.text("")` yields `Doc.empty`, `Doc.char(' ')` yields the shared single-space document, `Doc.indent(0, d)` yields `d`), so a matched tree is canonical: you never see a stacked `Indent(2, Indent(3, d))` node.

### Text, characters, and the empty document

When a document has no internal structure, the atomic constructors are `Doc.text` for a literal string, `Doc.char` for a single character, `Doc.space` for one column of space, and `Doc.empty` as the identity for concatenation.

```scala
val label: Doc = Doc.text("value:").append(Doc.space).append(Doc.text("42"))

assert(Printer.greedy.print(label, 80) == "value: 42")

assert(Doc.text("") == Doc.empty)
assert(Doc.char(' ') == Doc.space)
assert(Doc.empty.append(label) == label)
```

`Doc.space` is overloaded: with no arguments it is the single-space document above; given documents as arguments (`Doc.space(a, b)`), it lays them out horizontally separated by single spaces, as covered in [Sequences and separators](#sequences-and-separators).

> **Caution:** `Doc.text` does not validate its argument. A string containing `\n` becomes a single `Text` node whose embedded newline printers count as an ordinary one-column character, corrupting width accounting while still emitting a real line break into the output. Use the [`doc` interpolator](#authoring-templates-with-the-doc-interpolator) for multiline content; it splits multiline strings and literals into `hardNewline`-separated lines the printers understand.

### Line breaks: soft and hard

When you want a position that may become a line break, you have two documents with different guarantees. `Doc.newline` is soft: it collapses to a single space when the enclosing group fits, and emits a real newline when it does not (or when there is no enclosing group). `Doc.hardNewline` always emits a newline, in every mode.

```scala
// A bare soft newline sits in no group, so it always breaks.
val soft = Doc.text("Hello,").append(Doc.newline).append(Doc.text("world"))
assert(Printer.greedy.print(soft, 80) == "Hello,\nworld")

// A hard newline breaks even inside a group that would otherwise flatten.
val forced = Doc.group(Doc.text("a").append(Doc.hardNewline).append(Doc.text("b")))
assert(Printer.greedy.print(forced, 80) == "a\nb")
```

> **Note:** A group containing a `hardNewline` can never fully flatten. The printer measures the flat form as fitting only up to the hard break, so the soft breaks around it still collapse, but the hard break itself is always emitted.

### Indentation and alignment

When lines break, two documents control where the next line starts. `indent` adds a fixed margin relative to the current indentation level; `align` continues broken lines at the current output column instead of a fixed margin.

```scala
val steps = Doc.text("stepOne").append(Doc.newline).append(Doc.text("stepTwo"))

assert(Printer.greedy.print(steps.indent(2), 80) ==
    """stepOne
      |  stepTwo""".stripMargin)

assert(Printer.greedy.print(steps.indent(), 80) ==
    """stepOne
      |    stepTwo""".stripMargin) // default: 4 spaces

val pair = Doc.text("pair: ").append(
    Doc.align(Doc.text("one").append(Doc.newline).append(Doc.text("two")))
)

assert(Printer.greedy.print(pair, 80) ==
    """pair: one
      |      two""".stripMargin)
```

Nested `indent` calls merge additively into a single node, and negative indents cancel, so indentation arithmetic happens in the tree rather than accumulating wrappers:

```scala
val a = Doc.text("a")

assert(a.indent(2).indent(3) == Doc.Indent(5, a))
assert(a.indent(2).indent(-2) == a)
```

Both `group` and `align` are idempotent (`Doc.group(Doc.group(d)) == Doc.group(d)`), and `Doc.grouped` / `Doc.aligned` are aliases of the companion methods of the same name.

### Groups: flat when they fit

When a region should render on one line if the column budget allows and break otherwise, wrap it in `group`. This is the workhorse idiom: soft newlines inside a group are the candidate break points, and the printer decides among them by measuring whether the flattened region fits in the remaining columns.

```scala
val call = Doc.group(
    Doc.text("send").append(Doc.text("("))
        .append(Doc.text("alpha")).append(Doc.text(",")).append(Doc.newline)
        .append(Doc.text("beta")).append(Doc.text(",")).append(Doc.newline)
        .append(Doc.text("gamma"))
        .append(Doc.text(")"))
)

assert(Printer.greedy.print(call, 80) == "send(alpha, beta, gamma)")
assert(Printer.greedy.print(call, 12) ==
    """send(alpha,
      |beta,
      |gamma)""".stripMargin)
```

A group is measured as a unit: if the flat form fits, every soft newline inside it becomes a space; if not, every soft newline inside it breaks. The bracketing combinators in [Breaking bracketed clauses](#breaking-bracketed-clauses) build exactly this shape for you, so most documents never assemble groups by hand.

### Choices: alternative layouts

When the layouts you want are not "same content, flat or broken" but genuinely different renderings, `choice` describes explicit alternatives. The binary form is `Doc.choice(broken, flat)`; the varargs and `Chunk` forms chain it right-associatively with the alternatives ordered from most preferred (usually the widest) to last resort, and `d.fallback(that)` is `Doc.choice(d, that)`. Identical adjacent alternatives collapse to a single document.

```scala
val name = Doc.choice(
    Doc.text("long alternative that exceeds width"),
    Doc.text("medium alt"),
    Doc.text("short")
)

assert(Printer.optimal.print(name, 50) == "long alternative that exceeds width")
assert(Printer.optimal.print(name, 15) == "medium alt")
assert(Printer.optimal.print(name, 8) == "short")
```

> **Caution:** A bare `Doc.choice` outside any group always renders its first (broken) alternative under `Printer.greedy`, because greedy only measures when it enters a `group`. Wrap a binary choice in a group for flat-or-broken selection under `greedy` (this is what `chop` and friends do internally), and reach for `Printer.optimal` when a tree contains more than two alternatives per choice point.

## Sequences and separators

When a document is built from many pieces rather than two, the sequence combinators interleave a separator or wrap the whole run in delimiters, in a fixed arrangement that does not adapt to width. Each has a method form on `Chunk[Doc]` and a companion form.

### Interleaving with a separator

`separate` places one separator document between consecutive elements; `join` additionally wraps the run in open and close delimiters.

```scala
val params = Chunk(Doc.text("id: Long"), Doc.text("name: String"))

assert(Printer.greedy.print(params.separate(Doc.text(", ")), 80) == "id: Long, name: String")

assert(
    Printer.greedy.print(
        params.join(Doc.text("["), Doc.text("; "), Doc.text("]")),
        80
    ) == "[id: Long; name: String]"
)
```

An empty chunk yields `Doc.empty` from `separate` and the concatenated delimiters from `join`; a single-element chunk yields the element with no separator.

### Vertical and horizontal layout

`stack` (alias `lines`) lays documents out vertically separated by soft newlines; `space` (alias `spaced`) lays them out horizontally separated by single spaces. Neither adapts to width on its own: `stack` always breaks its soft newlines and `space` never breaks them. Wrap the result in `group` to make either arrangement width-sensitive.

```scala
assert(Printer.greedy.print(Doc.stack(Doc.text("first"), Doc.text("second")), 80) ==
    """first
      |second""".stripMargin)

assert(Printer.greedy.print(Doc.space(Doc.text("val"), Doc.text("x"), Doc.text("="), Doc.text("1")), 80) ==
    "val x = 1")
```

### Delimiting a body

`between` wraps a single body in open and close delimiters; `enclose` is its curried form, convenient when the same delimiters frame many different bodies.

```scala
val quoted = Doc.between(Doc.text("\""), Doc.text("hello"), Doc.text("\""))
assert(Printer.greedy.print(quoted, 80) == "\"hello\"")

val parenthesized = Doc.enclose(Doc.text("("), Doc.text(")"))(Doc.text("1 + 2"))
assert(Printer.greedy.print(parenthesized, 80) == "(1 + 2)")
```

## Breaking bracketed clauses

When the structure is an open delimiter, a separator, and a close delimiter around many items (argument lists, parameter lists, collection literals), the interesting question is what happens when the clause does not fit. Four combinators answer it with four strategies, differing in where the close delimiter lands and how continuation lines indent. These are the layer scalafmt break styles are built from.

All four share the shape `combinator(open, sep, close)(docs: Chunk[Doc])` with an equivalent method form on `Chunk[Doc]`, and all four render flat when the flat form fits the budget. The examples below all use `val args = Chunk(Doc.text("alpha"), Doc.text("beta"), Doc.text("gamma"))` and render it inside a `send(...)` clause.

### `chop`: one item per line, dangling close

When a clause breaks and the close delimiter should sit on its own line aligned with the opening (scalafmt's dangling parentheses), use `chop`. Broken items are indented by a fixed margin, 4 spaces by default, configurable as the fourth parameter.

```scala
val call = args.chop(Doc.text("send("), Doc.text(","), Doc.text(")"))

assert(Printer.greedy.print(call, 80) == "send(alpha, beta, gamma)")

assert(Printer.greedy.print(call, 15) ==
    """send(
      |    alpha,
      |    beta,
      |    gamma
      |)""".stripMargin)

assert(Printer.greedy.print(args.chop(Doc.text("send("), Doc.text(","), Doc.text(")"), 2), 15) ==
    """send(
      |  alpha,
      |  beta,
      |  gamma
      |)""".stripMargin)
```

### `chopClosed`: one item per line, attached close

When the close delimiter should stick to the last item instead of getting its own line, use `chopClosed`. It differs from `chop` in nothing else: same broken body, same indent parameter, same flat form.

```scala
val callClosed = args.chopClosed(Doc.text("send("), Doc.text(","), Doc.text(")"))

assert(Printer.greedy.print(callClosed, 15) ==
    """send(
      |    alpha,
      |    beta,
      |    gamma)""".stripMargin)
```

`chop` and `chopClosed` divide the dangling question between them. When your style guide closes brackets on their own line (the scalafmt `danglingParentheses` default), use `chop`; when the close must hug the final item, use `chopClosed`.

### `hang`: continuations under the opening

When broken lines should align under the opening delimiter rather than at a fixed margin, use `hang`. It aligns the broken body to the output column where the opening ends, which suits short openers; it takes no indent parameter because the indent is derived from the opener.

```scala
val aligned = args.hang(Doc.text("send("), Doc.text(","), Doc.text(")"))

assert(Printer.greedy.print(aligned, 15) ==
    """send(alpha,
      |     beta,
      |     gamma)""".stripMargin)
```

### `wrap`: fill lines within the budget

When items should pack onto each line as the column budget allows, like text wrapping, rather than breaking all-or-nothing, use `wrap`. Unlike the other three, it groups each separator individually, so line N can be full while line N+1 continues; the close delimiter attaches to the last item and continuation lines align under the opening.

```scala
val items = Chunk(
    Doc.text("alpha"),
    Doc.text("beta"),
    Doc.text("gamma"),
    Doc.text("delta"),
    Doc.text("epsilon")
)

val packed = items.wrap(Doc.text("["), Doc.text(", "), Doc.text("]"))

assert(Printer.greedy.print(packed, 80) == "[alpha, beta, gamma, delta, epsilon]")

assert(Printer.greedy.print(packed, 20) ==
    """[alpha, beta, gamma,
      | delta, epsilon]""".stripMargin)
```

`chop`, `chopClosed`, and `hang` are all-or-nothing: the clause either renders flat or breaks at every separator. `wrap` fills lines greedily instead. Use the first three for code-like output where one-per-line is the broken convention; use `wrap` for data-like lists where any number of items per line is acceptable.

> **Note:** For a single-element chunk, `hang` and `wrap` bypass breaking entirely and render `open + item + close` with no alternative, while `chop` and `chopClosed` still build a broken/flat choice with the lone element indented. An empty chunk renders as the concatenated delimiters for all four.

## Authoring templates with the doc interpolator

When a generator emits multi-line output, writing `append` chains obscures the shape of the result. The `doc` string interpolator lets you write the template as a literal that reads like its output: source indentation is normalized away, and embedded values participate in layout instead of being spliced opaquely.

```scala
val name = "world"

val method = doc"""
    def greet(name: String): String =
        s"hello $name"
    """

assert(Printer.greedy.print(method, 80) ==
    """def greet(name: String): String =
      |    s"hello $name"""".stripMargin)
```

### Baseline stripping

The interpolator strips the minimum leading whitespace across non-blank lines (the baseline) from every line, so a template indented to sit inside a method renders at its own indentation. Blank lines never widen the baseline. A blank first line and the newline closing it are dropped, as is a blank last line and the newline preceding it, so the customary opening and closing newlines cost nothing. Interior blank lines are preserved, and trailing whitespace is trimmed from every line.

```scala
val d = doc"""
      deep
    shallow
    """
assert(Printer.greedy.print(d, 80) == "  deep\nshallow")

val interior = doc"""
    a

    b
    """
assert(Printer.greedy.print(interior, 80) == "a\n\nb")

val oneBlankLeadingLine = doc"""

    a
    """
assert(Printer.greedy.print(oneBlankLeadingLine, 80) == "\na")
```

The last example shows the one asymmetry: only the newline closing the first blank line is dropped, so a deliberately empty leading line still renders. Drop exactly one blank line before your content and keep the ones you want to see.

### Embedding values

Interpolated positions accept `Doc` or `String` values. A single-line value participates as ordinary text. A multiline value (either a `Doc` with breaks or a `String` containing newlines) is aligned: its continuation lines indent to the column where the value is inserted, not to the surrounding template's indentation, so the embedded block stays visually attached to its introduction.

```scala
val body = Doc.stack(Doc.text("a"), Doc.text("b"))

val d = doc"""
    prefix $body
    """

assert(Printer.greedy.print(d, 80) ==
    """prefix a
      |       b""".stripMargin)
```

> **Note:** Literal line breaks in the template are emitted as `hardNewline` documents, so even `Printer.compact` keeps the template's line structure while minifying everything else. This is also why the interpolator is the recommended cure for the `Doc.text` newline caution above: it splits multiline strings into lines the printers account for correctly.

## Rendering against a width budget

A `Doc` says nothing about columns. The `Printer` trait is the rendering side: it walks the tree against a width and produces a string or a stream of lines. Three engines ship with the module, and the trait is implementable for custom engines.

### The `Printer` interface

When the built-in engines do not apply, or you want to post-process output, implement the two-method trait. `print` renders to a `String`; `printStream` emits one chunk per line as a `Stream[String, Any]`.

```scala
val upper: Printer = new Printer:
    def print(doc: Doc, width: Int): String =
        Printer.greedy.print(doc, width).toUpperCase

    def printStream(doc: Doc, width: Int)(using Frame): Stream[String, Any] =
        Printer.greedy.printStream(doc, width).map(_.toUpperCase)

assert(upper.print(Doc.text("hello"), 80) == "HELLO")
```

### `Printer.greedy`: the workhorse

When in doubt, render with `greedy`. It is a linear-time Wadler-Lindig printer: at each `group` it looks ahead within the current line's remaining budget and commits to flat or broken on the spot. Every example above uses it.

Its limitation is the mirror of its speed: decisions are local. A bare `choice` outside a group renders its first alternative unconditionally, and a chain of interacting decisions can commit early where a global view would have found a layout with less overflow. On documents built from single-choice shapes (all of the bracketing combinators, groups, plain text) its output is exactly the optimal printer's.

### `Printer.optimal`: global layout search

When a document contains multi-alternative `choice` trees and every column counts, render with `optimal`. It explores the alternatives with dynamic programming over a Pareto frontier, minimizing a cost that penalizes overflow quadratically and then prefers fewer lines.

```scala
val name = Doc.choice(
    Doc.text("first-option-long").append(Doc.space).append(
        Doc.choice(Doc.text("second-long"), Doc.text("s2"))
    ),
    Doc.text("first-short").append(Doc.space).append(
        Doc.choice(Doc.text("second-long-choice"), Doc.text("s2"))
    )
)

// Greedy commits to the first alternative of each choice and overflows.
assert(Printer.greedy.print(name, 22) == "first-option-long second-long")

// Optimal compares the combinations and finds the one that fits.
assert(Printer.optimal.print(name, 22) == "first-option-long s2")
```

### `Printer.compact`: minified output

When the render target is a machine reader and indentation is noise, use `compact`. It renders everything flat: soft newlines collapse to single spaces, choices resolve to their flat alternative, and indentation and alignment are discarded. Hard newlines survive as bare line breaks, so documents authored with the `doc` interpolator keep their line structure.

```scala
val call = args.chop(Doc.text("send("), Doc.text(","), Doc.text(")"))

assert(Printer.compact.print(call, 80) == "send(alpha, beta, gamma)")

assert(Printer.compact.print(
    doc"""
    def greet(name: String): String =
        s"hello"
    """,
    80
) ==
    """def greet(name: String): String =
      |s"hello"""".stripMargin)
```

> **Caution:** `Printer.compact` accepts a `width` parameter and ignores it entirely. Passing a budget has no effect on its output.

### Streaming line by line

When output goes to a consumer that benefits from receiving lines as they are decided (a terminal, a socket, a log appender), `printStream` emits one chunk per line. Each chunk includes its trailing newline, except the final line when the document does not end on a break; consumers concatenate directly and never add separators.

```scala
val call = args.chop(Doc.text("send("), Doc.text(","), Doc.text(")"))

val lines: Chunk[String] = Printer.greedy.printStream(call, 15).run.eval

assert(lines == Chunk("send(\n", "    alpha,\n", "    beta,\n", "    gamma\n", ")"))
```

All three engines implement `printStream`, and a stream's concatenation always equals the same engine's `print` output for the same width.

## Configuring scalafmt-compatible styles

When generated code must be formatted the way scalafmt formats it, the decisions map to this module's combinators, and `ScalafmtConfig` carries the option values that drive the mapping. It models the scalafmt subset that governs clause breaking: maximum column, per-site indents, dangling parentheses, open-parenthesis alignment, and bin-packing at call sites, definition sites, and template parent constructors. Defaults match the scalafmt `default` preset.

```scala
val config = ScalafmtConfig.default

assert(config.maxColumn == 80)
assert(config.indent.callSite == 2)
assert(config.indent.defnSite == 4)
assert(config.indent.extendSite == 4)
assert(config.danglingParentheses.callSite)
assert(config.danglingParentheses.defnSite)
assert(!config.align.openParenCallSite)
assert(!config.align.openParenDefnSite)
assert(config.binPack.callSite == ScalafmtConfig.BinPack.Site.Never)
assert(config.binPack.defnSite == ScalafmtConfig.BinPack.Site.Never)
assert(config.binPack.parentConstructors == ScalafmtConfig.BinPack.ParentCtors.Never)
```

The nested types each mirror one scalafmt section: `Indent` (`callSite`, `defnSite`, `extendSite`), `DanglingParentheses` (`callSite`, `defnSite`), `Align` (`openParenCallSite`, `openParenDefnSite`, both off by default as in scalafmt since v1.6), and `BinPack`: `callSite` and `defnSite` take a `Site` (`Never`, `Always`, `Oneline`) while `parentConstructors` takes a `ParentCtors` (`Never`, `Always`, `Oneline`, `OnelineIfPrimaryOneline`). The constructor default of `Never` mirrors scalafmt's `parentConstructors = source` under its default classic newline source, which resolves to `Never`.

> **Note:** `ScalafmtConfig` is an inert option model. Nothing in the module reads it automatically; user code maps options to combinators itself, as below. Unlike the bracketing combinators' default indent of 4, `indent.callSite` defaults to 2, so passing configuration through means threading the explicit indent parameter rather than relying on defaults.

### Reading options, selecting combinators

Because the config is data, the idiomatic pattern is a function from config to `Doc` that reads the options from the environment and selects the matching combinator: alignment turns on `hang`, dangling parentheses choose between `chop` and `chopClosed`, and bin-packing selects `wrap`. This mapping decides the bracketing at construction time, which approximates scalafmt for these shapes; the parity engine below covers the cases a construction-time decision cannot express.

```scala
def renderCall(call: Call): Doc < Env[ScalafmtConfig] =
    Env.use[ScalafmtConfig] { config =>
        val docs  = call.args.map(Doc.text(_))
        val open  = Doc.text(s"${call.fn}(")
        val sep   = Doc.text(",")
        val close = Doc.text(")")
        if config.align.openParenCallSite then
            docs.hang(open, sep, close)
        else if config.binPack.callSite == ScalafmtConfig.BinPack.Site.Never then
            if config.danglingParentheses.callSite then
                docs.chop(open, sep, close, config.indent.callSite)
            else
                docs.chopClosed(open, sep, close, config.indent.callSite)
            end if
        else
            docs.wrap(open, sep, close)
        end if
    }

def render(config: ScalafmtConfig)(doc: Doc < Env[ScalafmtConfig]): String =
    Env.run(config) {
        Env.use[ScalafmtConfig] { c =>
            doc.map(Printer.greedy.print(_, c.maxColumn))
        }
    }.eval

val send = Call("send", Chunk("alpha", "beta", "gammaLongIdentifier"))

// Flat form fits the default 80 columns.
assert(render(ScalafmtConfig.default)(renderCall(send)) == "send(alpha, beta, gammaLongIdentifier)")

// One column short: dangling close, call-site indent of 2.
assert(render(ScalafmtConfig(maxColumn = 37))(renderCall(send)) ==
    """send(
      |  alpha,
      |  beta,
      |  gammaLongIdentifier
      |)""".stripMargin)

// Alignment: continuations under the opening parenthesis.
assert(render(ScalafmtConfig(maxColumn = 37, align = ScalafmtConfig.Align(openParenCallSite = true)))(renderCall(send)) ==
    """send(alpha,
      |     beta,
      |     gammaLongIdentifier)""".stripMargin)

// Dangling off: the close sticks to the last argument.
assert(
    render(
        ScalafmtConfig(maxColumn = 37, danglingParentheses = ScalafmtConfig.DanglingParentheses(callSite = false))
    )(renderCall(send)) ==
        """send(
      |  alpha,
      |  beta,
      |  gammaLongIdentifier)""".stripMargin
)

// Bin-packing: fill lines within the budget.
assert(
    render(
        ScalafmtConfig(maxColumn = 30, binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always))
    )(renderCall(send)) ==
        """send(alpha, beta,
      |     gammaLongIdentifier)""".stripMargin
)
```

The same pattern extends to definition sites (reading `indent.defnSite`, `danglingParentheses.defnSite`, and `align.openParenDefnSite` for `def` parameter lists) and to template bodies (indenting `extends` and `with` clauses by `indent.extendSite` once the template breaks).

### The parity engine

Scalafmt decides how a clause breaks during layout, conditioned on what else broke: `Oneline` bin-packing treats an argument that itself spans lines differently from `Always`, a template's parent packing depends on whether the parameter clause stayed on one line, and source line breaks fold or persist per scalafmt's classic rules. Construction-time combinator choice cannot express those, so `Printer.scalafmt` renders a `Doc.clause`: the clause carries a site policy, resolved from the config by `clausePolicy`, and the engine makes every decision against the width alone. Its output is validated byte-for-byte against fixtures committed from scalafmt's own test suite.

```scala
val oneline = ScalafmtConfig(
    maxColumn = 40,
    binPack =
        ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Oneline)
)
val always = oneline.copy(binPack =
    ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
)

def call(config: ScalafmtConfig, fn: String, args: Doc*): Doc =
    Doc.clause(config.clausePolicy(Doc.ClauseSite.Call), Doc.text(s"$fn("), Doc.text(")"))(
        Chunk.from(args)
    )

// The nested call cannot fit on one line, so it is a multiline argument.
val beta = call(oneline, "beta", Doc.text("gammaLongIdentifier1"), Doc.text("gammaLongIdentifier2"))

// Oneline isolates a multiline argument on its own lines.
assert(Printer.scalafmt.print(call(oneline, "program", Doc.text("aaaa"), beta, Doc.text("cccc")), 40) ==
    """program(aaaa,
      |  beta(gammaLongIdentifier1,
      |    gammaLongIdentifier2),
      |  cccc)""".stripMargin)

// Always lets later arguments share the multiline argument's last line.
assert(Printer.scalafmt.print(call(always, "program", Doc.text("aaaa"), beta, Doc.text("cccc")), 40) ==
    """program(aaaa,
      |  beta(gammaLongIdentifier1,
      |    gammaLongIdentifier2), cccc)""".stripMargin)
```

## Putting it together

The pieces compose into a small method-signature generator. The signature breaks one parameter per line at an indent of 2 with a dangling close, the body is kept on hard newlines so structure survives even `Printer.compact`, and the whole document renders at any budget from one construction.

```scala
def paramDoc(p: Param): Doc = doc"${p.name}: ${p.tpe}"

def signature(m: Method): Doc =
    Doc.text("def ").append(Doc.text(m.name)).append(
        m.params.map(paramDoc).chop(Doc.text("("), Doc.text(","), Doc.text(")"), 2)
    ).append(Doc.text(s": ${m.returnType} ="))

def methodDoc(m: Method): Doc =
    signature(m).append(Doc.hardNewline).append(
        Doc.separate(Doc.hardNewline)(m.body.map(Doc.text(_))).indent(4)
    )

val m = Method(
    "transfer",
    Chunk(
        Param("from", "Account"),
        Param("to", "Account"),
        Param("amount", "BigDecimal"),
        Param("currency", "Currency"),
        Param("note", "Maybe[String]")
    ),
    "Unit",
    Chunk("val delta = amount * rate", "debit(from, delta)", "credit(to, delta)")
)
```

Rendered wide, the parameter list does not fit 80 columns flat, so `chop` breaks it one parameter per line with the dangling close; the body follows at a fixed indent of 4:

```text
def transfer(
  from: Account,
  to: Account,
  amount: BigDecimal,
  currency: Currency,
  note: Maybe[String]
): Unit =
    val delta = amount * rate
    debit(from, delta)
    credit(to, delta)
```

The same value streams line by line, `Printer.greedy.printStream(methodDoc(m), 80).run.eval` returning one chunk per line including each trailing newline, and minifies under `Printer.compact` with only the hard newlines surviving:

```text
def transfer(from: Account, to: Account, amount: BigDecimal, currency: Currency, note: Maybe[String]): Unit =
val delta = amount * rate
debit(from, delta)
credit(to, delta)
```
