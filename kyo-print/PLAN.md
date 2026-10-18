# Implementation Plan: `kyo-print` Module

## 1. Executive Summary & Design Architecture

`kyo-print` is a native, zero-dependency pretty-printing and document layout module for the Kyo ecosystem across all four supported platforms: JVM, JavaScript, Scala Native, and WebAssembly.

It addresses the limitations observed with external libraries like [Typelevel Paiges](https://github.com/typelevel/paiges) on `kyo-grpc` (which required vendoring workarounds for internal invariant violations and operator clutter) and replaces ad-hoc, error-prone string concatenation across `kyo-ffi/codegen`, `kyo-ui`, and `kyo-test`.

Following the foundational work on pretty-printing layout algebras by [Philip Wadler (*A prettier printer*, 2003)](https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf) and recent advances by Porncharoenwase et al. ([*A pretty expressive printer*, OOPSLA 2023](https://github.com/sorawee/pretty-expressive) / [OOPSLA 2023 Artifact](https://github.com/sorawee/pretty-expressive-oopsla23-artifact)), `kyo-print` separates layout into two independent concerns:
1. **The Pretty Printing Language (PPL)**: An immutable, algebraic document AST (`Doc`) representing text, indentation, and branching layout choices (`Doc.choice`).
2. **Pluggable Printers**: Algorithms that interpret the PPL against page-width constraints to select the optimal layout (`Printer.Greedy`, `Printer.Optimal`, `Printer.Compact`).

### References & Prior Art

- **Wadler's Prettier Printer**: [A prettier printer (Philip Wadler, 2003)](https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf)
- **Pretty-Expressive**: [Pretty-Expressive Repository](https://github.com/sorawee/pretty-expressive) and [OOPSLA 2023 Artifact](https://github.com/sorawee/pretty-expressive-oopsla23-artifact) (Sorawee Porncharoenwase, Justin Pombrio, Emina Torlak)
- **Typelevel Paiges**: [Paiges Repository](https://github.com/typelevel/paiges)

```
                     ┌───────────────────────────────┐
                     │      Doc (Pure PPL AST)       │
                     └──────────────┬────────────────┘
                                    │
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
   ┌──────────────────┐   ┌───────────────────┐   ┌───────────────────┐
   │  Printer.Greedy  │   │  Printer.Optimal  │   │  Printer.Compact  │
   │  Linear O(N)     │   │  Pareto-Optimal   │   │  Zero-Indent      │
   │  Local Lookahead │   │  DAG Search       │   │  Minified Output  │
   │  (Wadler-Lindig) │   │  (Porncharoenwase)│   │  (Single Line)    │
   └──────────────────┘   └───────────────────┘   └───────────────────┘
```

---

## 2. API & Component Specification

### 2.1. The Orthogonal Core Primitives (`Doc`)

Every layout construct in `kyo-print` is derived from 8 orthogonal primitives:

| Primitive | Type / Signature | Semantics |
| :--- | :--- | :--- |
| `empty` | `Doc` | Identity element for concatenation ($O(1)$). |
| `text(str)` | `String => Doc` | Atomic literal text with known display column width. Assumed free of raw `\n`. |
| `newline` | `Doc` | Soft line break: collapses to space when rendered flat; emits `\n` + indent when broken. |
| `hardNewline` | `Doc` | Hard line break: always emits `\n` + indent, even when enclosing group is flat. |
| `append` | `(Doc, Doc) => Doc` | Associative binary concatenation. |
| `indent` | `(Int, Doc) => Doc` | Relatively increases indentation by $N$ spaces for all line breaks within the document. |
| `grouped` | `Doc => Doc` | Attempts single-line layout; falls back to breaking layout if the group exceeds available column width. |
| `choice` | `(Doc, Doc) => Doc` | Declares alternative formats: first alternative is preferred in breaking mode, second in flat mode (or explored by optimal printer). |
| `aligned` | `Doc => Doc` | Aligns all nested line breaks to the current column position. |

### 2.2. Derived Action-Verb Combinators

All combinator operations are named with active verbs adhering to Kyo conventions:

```scala
package kyo

sealed trait Doc derives CanEqual:
    def append(that: Doc): Doc
    def indent(spaces: Int = 4): Doc
    def fallback(that: Doc): Doc
    def group: Doc
    def align: Doc

object Doc:
    // Core Primitives
    val empty: Doc
    val space: Doc
    val newline: Doc
    val hardNewline: Doc
    def text(str: String): Doc
    def char(c: Char): Doc
    def choice(broken: Doc, flat: Doc): Doc
    def choice(alts: Doc*): Doc

    // Linear Sequences (Verbs)
    def stack(docs: Doc*): Doc
    def stack(docs: Chunk[Doc]): Doc
    def space(docs: Doc*): Doc
    def space(docs: Chunk[Doc]): Doc

    // Delimited Sequences (Verbs)
    def separate(sep: Doc)(docs: Doc*): Doc
    def separate(sep: Doc)(docs: Chunk[Doc]): Doc
    def join(start: Doc, sep: Doc, end: Doc)(docs: Doc*): Doc
    def join(start: Doc, sep: Doc, end: Doc)(docs: Chunk[Doc]): Doc

    // Scalafmt Layout Strategies (Verbs)
    def chop(open: Doc, sep: Doc, close: Doc, indent: Int = 4)(docs: Doc*): Doc
    def chop(open: Doc, sep: Doc, close: Doc, indent: Int = 4)(docs: Chunk[Doc]): Doc
    def chopClosed(open: Doc, sep: Doc, close: Doc, indent: Int = 4)(docs: Doc*): Doc
    def chopClosed(open: Doc, sep: Doc, close: Doc, indent: Int = 4)(docs: Chunk[Doc]): Doc

    def hang(open: Doc, sep: Doc, close: Doc)(docs: Doc*): Doc
    def hang(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc

    def wrap(open: Doc, sep: Doc, close: Doc)(docs: Doc*): Doc
    def wrap(open: Doc, sep: Doc, close: Doc)(docs: Chunk[Doc]): Doc

    // Enclosures
    def between(open: Doc, body: Doc, close: Doc): Doc
    def enclose(open: Doc, close: Doc)(body: Doc): Doc
```

### 2.3. Zero-Trailing-Whitespace Invariant

In all breaking sequence combinators (`separate`, `join`, `chop`), separators are defined via `Doc.choice`:
```scala
val commaSep = Doc.choice(broken = Doc.text(",").append(Doc.newline), flat = Doc.text(", "))
```
When breaking across multiple lines, the separator emits `,` immediately followed by `\n` and indentation. Trailing spaces are strictly impossible.

### 2.4. Template Interpolation (`doc"..."`)

The `doc"..."` interpolator enables authoring code generators whose in-source structure directly mirrors output:
```scala
val methodDoc =
    doc"""
    def $name(${params.chop("(", ",", ")")}): $returnType =
        $body
    """
```
- Multi-line literals determine baseline indentation from the first non-empty line.
- Embedded multiline `Doc` instances have their continuation lines indented to match the insertion column.

### 2.5. Pluggable Printers (`Printer`)

```scala
package kyo

trait Printer:
    def print(doc: Doc, width: Int): String
    def printStream(doc: Doc, width: Int): Stream[String, Any]

object Printer:
    val greedy: Printer     // Wadler-Lindig linear-time lookahead
    val optimal: Printer    // Pretty-Expressive dynamic programming DAG search
    val compact: Printer    // Single-line flat rendering
```

### 2.6. Effect Integration

`Doc` is a pure value and `Printer` is a layout strategy. To evaluate an effectful document (`Doc < S`), standard Kyo `.map` operations are used rather than a dedicated handler object, keeping the API minimal and avoiding lock-in. Printers are passed explicitly so users control the layout engine:

```scala
// Pure evaluation
val output: String = Printer.greedy.print(doc, width = 100)

// Effectful evaluation
val stringEffect: String < S = docEffect.map(Printer.greedy.print(_, 100))

// Streaming effectful evaluation (extracting the Emit effect from the pure stream)
val streamEffect: Stream[String, S] = Stream(docEffect.map(Printer.greedy.printStream(_, 100).emit))
```

---

## 3. Implementation Phases

```
┌────────────────────────────────────────────────────────┐
│ Phase 1: Build & Project Setup                         │
│ - Register `kyo-print` in build.sbt (JVM, JS, SN, Wasm) │
│ - Add to kyoJVM, kyoJS, kyoNative, kyoWasm aggregates  │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Phase 2: Core PPL & Unit Tests                         │
│ - Implement Doc.scala AST & 8 primitives               │
│ - Implement derived combinators (join, chop, hang, etc)│
│ - Comprehensive DocTest.scala                          │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Phase 3: Printer Backends                              │
│ - Implement Printer.greedy (Wadler-Lindig engine)      │
│ - Implement Printer.compact                            │
│ - Implement Printer.optimal (DAG Pareto search)        │
│ - PrinterTest.scala verifying layout decisions         │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Phase 4: Template Interpolation                        │
│ - StringContext extension `doc"..."`                   │
│ - Column alignment for embedded multiline docs         │
│ - DocInterpolationTest.scala                           │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Phase 5: Scalafmt Parity Test Suite                    │
│ - Define ScalafmtConfig model in `kyo-print/shared/src/main/scala/kyo/ScalafmtConfig.scala` (ensuring no orphan test class)                          │
│ - Reusable effectful fixtures Doc < Env[ScalafmtConfig]│
│ - Test matrix validating documented Scalafmt styles    │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ Phase 6: Downstream Migration                          │
│ - Migrate kyo-grpc code-gen (drop paiges-core)         │
│ - Refactor kyo-ffi/codegen (eliminate indentBody)      │
└────────────────────────────────────────────────────────┘
```

### Phase 1: Build Configuration
- In `build.sbt`:
  - Declare `lazy val `kyo-print`` using `crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)`.
  - Depend on `kyo-prelude`.
  - Enable `.withKyoTest`.
  - Add `kyo-print.jvm` to `kyoJVM` aggregate.
  - Add `kyo-print.js` to `kyoJS` aggregate.
  - Add `kyo-print.native` to `kyoNative` aggregate.
  - Add `kyo-print.wasm` to `kyoWasm` aggregate.

### Phase 2: Core PPL & Combinators
- Source file: `kyo-print/shared/src/main/scala/kyo/Doc.scala`.
- Implement `Doc` ADT nodes: `Empty`, `Text`, `Newline`, `HardNewline`, `Indent`, `Align`, `Concat`, `Group`, `Choice`.
- Implement collection combinators: `lines`, `spaced`, `separate`, `join`, `chop`, `chopClosed`, `hang`, `wrap`.
- Test file: `kyo-print/shared/src/test/scala/kyo/DocTest.scala`.
- Tests verify:
  - Associativity of concatenation.
  - Correct spacing in flat vs breaking modes.
  - Zero trailing whitespace on multi-line separators.
  - Proper additive nesting of indentation.

### Phase 3: Printer Engines & Streaming
- Source file: `kyo-print/shared/src/main/scala/kyo/Printer.scala`.
- `Printer.greedy`:
  - Tracks current column and indentation stack.
  - Lookahead function measuring flat width up to line budget.
- `Printer.compact`:
  - Emits text and single spaces, ignoring indentation.
- `Printer.optimal`:
  - Evaluates multi-alternative `Doc.choice` trees via dynamic programming over the sub-document DAG.
- Test file: `kyo-print/shared/src/test/scala/kyo/PrinterTest.scala`.
- Tests verify:
  - Width boundary conditions (exact fit vs 1-character overflow).
  - Stream chunking and equivalence to buffered print.
  - Equivalence between greedy and optimal on standard single-choice shapes.

### Phase 4: Template Interpolation
- Source file: `kyo-print/shared/src/main/scala/kyo/DocInterpolation.scala`.
- Extension method `doc"..."` on `StringContext`.
- Test file: `kyo-print/shared/src/test/scala/kyo/DocInterpolationTest.scala`.
- Tests verify:
  - Multi-line indentation preservation.
  - Mixed string and `Doc` argument interpolation.
  - Margin stripping and leading newline normalization.

### Phase 5: Scalafmt Parity Test Suite
- Test file: `kyo-print/shared/src/test/scala/kyo/ScalafmtConfigTest.scala`.
- Models options from official Scalafmt documentation:
  - `indent.callSite`, `indent.defnSite`, `indent.extendSite`.
  - `danglingParentheses.callSite`, `danglingParentheses.defnSite`.
  - `align.openParenCallSite`, `align.openParenDefnSite`.
  - `binPack.callSite`.
- Evaluates canonical `Doc < Env[ScalafmtConfig]` fixtures against varied configurations.

### Phase 6: Downstream Migration
- On `kyo-grpc` branch:
  - Refactor `MethodBuilder.scala`, `ClassBuilder.scala`, `ObjectBuilder.scala`, `TraitBuilder.scala`, `ServicePrinter.scala`.
  - Delete `Docx.scala` and `ExtendedSyntax.scala`.
  - Remove `org.typelevel:paiges-core` from `build.sbt`.
- On `main` branch (`kyo-ffi`):
  - Refactor `JvmEmitter.scala` and `NativeEmitter.scala` to eliminate manual `StringBuilder` accumulation and `linesIterator.map(...)` string splitting.

---

## 4. Pre-Submission Quality Checklist

- [ ] `using` clauses correctly ordered: `Tag` before `Frame` (inline), `Frame` before evidence (non-inline), `AllowUnsafe` last.
- [ ] All cross-platform sources live in `shared/src/main/scala/kyo/`.
- [ ] All tests live in `shared/src/test/scala/kyo/` and extend `kyo.test.Test[Any]`.
- [ ] No symbolic operators in public APIs.
- [ ] Action-verb naming across all combinators.
- [ ] Public classes and traits have 8-35 lines of scaladoc.
- [ ] `sbt 'checkClassNames JVM'` passes.
- [ ] Tests pass on JVM, JS, Native, and Wasm.
