package kyo

/** Validates that the scalafmt parity engine renders oracle fixtures
  * byte-identically.
  *
  * Each test builds by hand the Doc tree an emitter would construct for the
  * oracle case's input (clauses carry the site policy scalafmt resolves from
  * its configuration) and asserts the engine output equals the expected
  * output committed by scalafmt's own suite. Fixtures are never compared
  * against self-generated expectations.
  *
  * The classic-family fixtures set `newlines.configStyleCallSite.prefer =
  * false` on the scalafmt side; the engine models no config-style preference
  * yet (it arrives with the optIn family), so its output corresponds to that
  * setting, and source line breaks fold the way scalafmt's default classic
  * newline source folds them.
  */
class ScalafmtPrinterTest extends kyo.test.Test[Any]:

    private def caseById(id: String)(using kyo.test.AssertScope): ScalafmtOracle.Case =
        ScalafmtOracle.cases.find(_.id == id).getOrElse(fail(s"missing oracle case $id"))

    private def render(doc: Doc, config: ScalafmtConfig): String =
        Printer.scalafmt.print(doc, config.maxColumn) + "\n"

    private def callPolicy(config: ScalafmtConfig): Doc.ClausePolicy =
        config.clausePolicy(Doc.ClauseSite.Call)

    private def defnPolicy(config: ScalafmtConfig): Doc.ClausePolicy =
        config.clausePolicy(Doc.ClauseSite.Defn)

    private def obj(header: String)(body: Doc): Doc =
        Doc.text(header).append(Doc.hardNewline)
            .append(body.indent(2))
            .append(Doc.hardNewline).append(Doc.text("}"))
    end obj

    private def texts(args: String*): Chunk[Doc] =
        Chunk.from(args.map(Doc.text(_)))

    "oracle parity" - {

        "danglingParentheses callSite=false defnSite=true (docs/danglingparentheses-defnsite-1)" in {
            val config = ScalafmtConfig(
                maxColumn = 25,
                danglingParentheses = ScalafmtConfig.DanglingParentheses(
                    callSite = false,
                    defnSite = true
                )
            )
            val defn = Doc.clause(
                defnPolicy(config),
                Doc.text("def method("),
                Doc.text("): Boolean")
            )(Doc.text("a: Int"), Doc.text("b: String"))
            val call = Doc.clause(
                callPolicy(config),
                Doc.text("method("),
                Doc.text(")")
            )(Doc.text("argument1"), Doc.text("argument2"))
            val body = Doc.text("// defnSite").append(Doc.hardNewline).append(defn)
                .append(Doc.hardNewline).append(Doc.hardNewline).append(Doc.text("// callSite"))
                .append(Doc.hardNewline).append(call)
            val doc = obj("object a {")(body)
            assert(render(doc, config) == caseById("docs/danglingparentheses-defnsite-1.stat:example").expected)
        }

        "danglingParentheses callSite=true defnSite=false (docs/danglingparentheses-callsite-1)" in {
            val config = ScalafmtConfig(
                maxColumn = 25,
                danglingParentheses = ScalafmtConfig.DanglingParentheses(
                    callSite = true,
                    defnSite = false
                )
            )
            val defn = Doc.clause(
                defnPolicy(config),
                Doc.text("def method("),
                Doc.text("): Boolean")
            )(Doc.text("a: Int"), Doc.text("b: String"))
            val call = Doc.clause(
                callPolicy(config),
                Doc.text("method("),
                Doc.text(")")
            )(Doc.text("argument1"), Doc.text("argument2"))
            val body = Doc.text("// defnSite").append(Doc.hardNewline).append(defn)
                .append(Doc.hardNewline).append(Doc.hardNewline).append(Doc.text("// callSite"))
                .append(Doc.hardNewline).append(call)
            val doc = obj("object a {")(body)
            assert(render(doc, config) == caseById("docs/danglingparentheses-callsite-1.stat:example").expected)
        }

        "literal argument lists bin-pack greedily (binPack/LiteralList.stat:basic)" in {
            val c      = caseById("binPack/LiteralList.stat:basic")
            val config = ScalafmtConfig(maxColumn = 79)
            val doc    = literalListClause(c, config)
            assert(render(doc, config) == c.expected)
        }

        "hex literal argument lists bin-pack greedily (binPack/LiteralList.stat:byte)" in {
            val c      = caseById("binPack/LiteralList.stat:byte")
            val config = ScalafmtConfig(maxColumn = 79)
            val doc    = literalListClause(c, config)
            assert(render(doc, config) == c.expected)
        }

        "constructor literals bin-pack greedily (binPack/LiteralList.stat:twenty tree)" in {
            val c      = caseById("binPack/LiteralList.stat:twenty tree")
            val config = ScalafmtConfig(maxColumn = 79)
            val doc    = literalListClause(c, config)
            assert(render(doc, config) == c.expected)
        }

        "parentConstructors Never breaks one parent per line (docs/binpack-parentconstructors-2)" in {
            val c      = caseById("docs/binpack-parentconstructors-2.stat:example")
            val config = ScalafmtConfig(maxColumn = 30)
            val clause = Doc.clause(
                config.clausePolicy(Doc.ClauseSite.Extend),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = false,
                forcedClose = false
            )(Doc.text("extends Bar"), Doc.text("with Baz"))
            val doc = obj("object A {")(clause)
            assert(render(doc, config) == c.expected)
        }

        "parentConstructors Always breaks before the first parent and packs (docs/binpack-parentconstructors-1)" in {
            val c = caseById("docs/binpack-parentconstructors-1.stat:example")
            // The mdoc fence overrides the docs base maxColumn with 30, so
            // both traits break: the whole parent chain cannot flow from the
            // header without overflowing, and Always falls back to breaking
            // before the first parent and packing the chain.
            val config = ScalafmtConfig(
                maxColumn = 30,
                binPack = ScalafmtConfig.BinPack(
                    parentConstructors = ScalafmtConfig.BinPack.ParentCtors.Always
                )
            )
            val broken = Doc.clause(
                config.clausePolicy(Doc.ClauseSite.Extend),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = true,
                forcedClose = false
            )(Doc.text("extends Bar"), Doc.text("with Baz"))
            val flat = Doc.clause(
                config.clausePolicy(Doc.ClauseSite.Extend),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = false,
                forcedClose = false
            )(Doc.text("extends Bar"), Doc.text("with Baz"))
            val doc = obj("object A {")(broken.append(Doc.hardNewline).append(flat))
            assert(render(doc, config) == c.expected)
        }
    }

    /** Builds the Doc for a `val x = List(literals)` statement from the oracle
      * case's own input text, so argument sequences are transcribed exactly.
      * The clause policy models scalafmt's `binPack.literalArgumentLists`: a
      * call site whose arguments are all literals is bin-packed even though
      * `binPack.callSite` is Never.
      */
    private def literalListClause(c: ScalafmtOracle.Case, config: ScalafmtConfig): Doc =
        val open   = c.original.indexOf("(")
        val prefix = c.original.substring(0, open).trim
        val args   = c.original.substring(open + 1, c.original.indexOf(")", open))
        val elems  = Chunk.from(args.split(",").map(s => Doc.text(s.trim)))
        val clause = Doc.clause(
            callPolicy(config).copy(binPack = Doc.BinPackMode.Always),
            Doc.text("("),
            Doc.text(")")
        )(elems)
        Doc.text(prefix).append(clause)
    end literalListClause

    "oracle parity: binPack callSite" - {

        val always = ScalafmtConfig(
            maxColumn = 40,
            binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
        )
        val oneline = ScalafmtConfig(
            maxColumn = 40,
            binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Oneline)
        )

        def betaCall(policy: Doc.ClausePolicy, fn: String, a: String, b: String): Doc =
            Doc.clause(policy, Doc.text(s"$fn("), Doc.text(")"))(Doc.text(a), Doc.text(b))

        def programCall(config: ScalafmtConfig, elems: Chunk[Doc]): Doc =
            Doc.clause(callPolicy(config), Doc.text("program("), Doc.text(")"))(elems)

        def middleMultiline(config: ScalafmtConfig): Doc =
            programCall(
                config,
                Chunk(
                    Doc.text("aaaa"),
                    betaCall(callPolicy(config), "beta", "gammaLongIdentifier1", "gammaLongIdentifier2"),
                    Doc.text("cccc")
                )
            )

        def firstMultiline(config: ScalafmtConfig): Doc =
            programCall(
                config,
                Chunk(
                    betaCall(callPolicy(config), "beta", "gammaLongIdentifier1", "gammaLongIdentifier2"),
                    Doc.text("aaaa"),
                    Doc.text("bbbb")
                )
            )

        def lastMultiline(config: ScalafmtConfig): Doc =
            programCall(
                config,
                Chunk(
                    Doc.text("aaaa"),
                    Doc.text("bbbb"),
                    betaCall(callPolicy(config), "beta", "gammaLongIdentifier1", "gammaLongIdentifier2")
                )
            )

        def twoMultiline(config: ScalafmtConfig): Doc =
            programCall(
                config,
                Chunk(
                    betaCall(callPolicy(config), "beta", "gammaLongIdentifier1", "gammaLongIdentifier2"),
                    betaCall(callPolicy(config), "delta", "epsilonLongIdentifier1", "epsilonLongIdentifier2"),
                    Doc.text("aaaa")
                )
            )

        "Always packs a middle multiline argument sharing its last line" in {
            val c = caseById("classic/callsite-always-middle-multiline.stat:example")
            assert(render(obj("object a {")(middleMultiline(always)), always) == c.expected)
        }

        "Oneline isolates a middle multiline argument" in {
            val c = caseById("classic/callsite-oneline-middle-multiline.stat:example")
            assert(render(obj("object a {")(middleMultiline(oneline)), oneline) == c.expected)
        }

        "Always renders a first multiline argument inline" in {
            val c = caseById("classic/callsite-always-first-multiline.stat:example")
            assert(render(obj("object a {")(firstMultiline(always)), always) == c.expected)
        }

        "Oneline breaks after the open when the first argument is multiline" in {
            val c = caseById("classic/callsite-oneline-first-multiline.stat:example")
            assert(render(obj("object a {")(firstMultiline(oneline)), oneline) == c.expected)
        }

        "Always renders a last multiline argument inline" in {
            val c = caseById("classic/callsite-always-last-multiline.stat:example")
            assert(render(obj("object a {")(lastMultiline(always)), always) == c.expected)
        }

        "Oneline renders a last multiline argument like Always" in {
            val c = caseById("classic/callsite-oneline-last-multiline.stat:example")
            assert(render(obj("object a {")(lastMultiline(oneline)), oneline) == c.expected)
        }

        "Always packs single-line arguments by fit" in {
            val c   = caseById("classic/callsite-always-flat.stat:example")
            val doc = programCall(always, texts("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"))
            assert(render(obj("object a {")(doc), always) == c.expected)
        }

        "Oneline packs single-line arguments exactly like Always" in {
            val c   = caseById("classic/callsite-oneline-flat.stat:example")
            val doc = programCall(oneline, texts("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"))
            assert(render(obj("object a {")(doc), oneline) == c.expected)
        }

        "Oneline tucks the close when the open broke and dangling is off" in {
            val config = ScalafmtConfig(
                maxColumn = 40,
                binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Oneline),
                danglingParentheses = ScalafmtConfig.DanglingParentheses(callSite = false)
            )
            val c = caseById("classic/callsite-oneline-first-multiline-closed.stat:example")
            assert(render(obj("object a {")(firstMultiline(config)), config) == c.expected)
        }

        "Always packs across two multiline arguments" in {
            val c = caseById("classic/callsite-always-two-multiline.stat:example")
            assert(render(obj("object a {")(twoMultiline(always)), always) == c.expected)
        }

        "Oneline isolates each of two multiline arguments" in {
            val c = caseById("classic/callsite-oneline-two-multiline.stat:example")
            assert(render(obj("object a {")(twoMultiline(oneline)), oneline) == c.expected)
        }

        "Always with open-paren alignment packs at the aligned column" in {
            val config = ScalafmtConfig(
                maxColumn = 40,
                binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always),
                align = ScalafmtConfig.Align(openParenCallSite = true)
            )
            val c = caseById("classic/callsite-always-align-openparen.stat:example")
            assert(render(obj("object a {")(middleMultiline(config)), config) == c.expected)
        }

        "Always with open-paren alignment packs flat arguments at the aligned column" in {
            val config = ScalafmtConfig(
                maxColumn = 40,
                binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always),
                align = ScalafmtConfig.Align(openParenCallSite = true)
            )
            val c   = caseById("classic/callsite-always-align-flat.stat:example")
            val doc = programCall(config, texts("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"))
            assert(render(obj("object a {")(doc), config) == c.expected)
        }

        "a source break after the open is folded under Always" in {
            val c      = caseById("classic/callsite-always-source-open-break-joined.stat:example")
            val clause = Doc.clause(
                callPolicy(always),
                Doc.text("program("),
                Doc.text(")"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = false
            )(texts("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"))
            assert(render(obj("object a {")(clause), always) == c.expected)
        }

        "a single source break is folded under Never when the flat form fits" in {
            val c      = caseById("classic/callsite-never-single-source-break-folded.stat:example")
            val config = ScalafmtConfig(maxColumn = 40)
            val clause = Doc.clause(
                callPolicy(config),
                Doc.text("send("),
                Doc.text(")"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = false
            )(texts("alpha", "beta", "gammaLongIdentifier"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "source breaks at both delimiters preserve the broken form under Never" in {
            val c      = caseById("classic/callsite-never-both-source-breaks-preserved.stat:example")
            val config = ScalafmtConfig(maxColumn = 40)
            val clause = Doc.clause(
                callPolicy(config),
                Doc.text("send("),
                Doc.text(")"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = true
            )(texts("alpha", "beta", "gamma"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }
    }

    "oracle parity: binPack defnSite" - {

        val always = ScalafmtConfig(
            maxColumn = 40,
            binPack = ScalafmtConfig.BinPack(defnSite = ScalafmtConfig.BinPack.Site.Always)
        )
        val oneline = ScalafmtConfig(
            maxColumn = 40,
            binPack = ScalafmtConfig.BinPack(defnSite = ScalafmtConfig.BinPack.Site.Oneline)
        )

        def simpleDefn(config: ScalafmtConfig): Doc =
            Doc.clause(
                defnPolicy(config),
                Doc.text("def program("),
                Doc.text("): Unit")
            )(texts("alpha: Int", "betaLongArgument: String", "gamma: Seq[LongType]"))
                .append(Doc.text(" = ()"))

        def defaultCall(config: ScalafmtConfig): Doc =
            Doc.clause(
                callPolicy(config),
                Doc.text("defaultExpression("),
                Doc.text(")")
            )(texts("argumentA", "argumentB"))

        def multilineParamDefn(config: ScalafmtConfig): Doc =
            Doc.clause(
                defnPolicy(config),
                Doc.text("def f("),
                Doc.text("): Unit")
            )(
                Chunk(
                    Doc.text("a: Int"),
                    Doc.text("b: LongTypeBB = ").append(defaultCall(config)),
                    Doc.text("c: Int")
                )
            ).append(Doc.text(" = ()"))

        "Always packs parameters by fit with the close attached" in {
            val c = caseById("classic/defnsite-always.stat:example")
            assert(render(obj("object a {")(simpleDefn(always)), always) == c.expected)
        }

        "Oneline packs single-line parameters like Always" in {
            val c = caseById("classic/defnsite-oneline.stat:example")
            assert(render(obj("object a {")(simpleDefn(oneline)), oneline) == c.expected)
        }

        "Always packs a parameter whose default breaks across lines" in {
            val c = caseById("classic/defnsite-always-multiline-param.stat:example")
            assert(render(obj("object a {")(multilineParamDefn(always)), always) == c.expected)
        }

        "Oneline isolates a parameter whose default breaks across lines" in {
            val c = caseById("classic/defnsite-oneline-multiline-param.stat:example")
            assert(render(obj("object a {")(multilineParamDefn(oneline)), oneline) == c.expected)
        }

        "a source break after the open is folded under defnSite Always" in {
            val c      = caseById("classic/defnsite-always-source-open-break-joined.stat:example")
            val clause = Doc.clause(
                defnPolicy(always),
                Doc.text("def program("),
                Doc.text("): Unit"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = false
            )(texts("alpha: Int", "betaLongArgument: String", "gamma: Seq[LongType]"))
                .append(Doc.text(" = ()"))
            assert(render(obj("object a {")(clause), always) == c.expected)
        }
    }

    "oracle parity: binPack parentConstructors" - {

        def config(ctors: ScalafmtConfig.BinPack.ParentCtors): ScalafmtConfig =
            ScalafmtConfig(maxColumn = 40, binPack = ScalafmtConfig.BinPack(parentConstructors = ctors))

        val always        = config(ScalafmtConfig.BinPack.ParentCtors.Always)
        val never         = config(ScalafmtConfig.BinPack.ParentCtors.Never)
        val oneline       = config(ScalafmtConfig.BinPack.ParentCtors.Oneline)
        val ifPrimaryFlat = config(ScalafmtConfig.BinPack.ParentCtors.OnelineIfPrimaryOneline)

        def ctorCall(cfg: ScalafmtConfig, fn: String): Doc =
            Doc.clause(
                callPolicy(cfg),
                Doc.text(s"$fn("),
                Doc.text(")")
            )(texts("argumentOneLongName", "argumentTwoLongName"))

        def traitHeader(cfg: ScalafmtConfig, parents: Doc*): Doc =
            Doc.clause(
                cfg.clausePolicy(Doc.ClauseSite.Extend),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty
            )(Chunk.from(parents))

        def classHeader(cfg: ScalafmtConfig, primary: Doc, parents: Doc*): Doc =
            Doc.clause(
                cfg.clausePolicy(Doc.ClauseSite.Extend),
                primary,
                close = Doc.empty,
                sep = Doc.empty
            )(Chunk.from(parents))

        def flatPrimary(cfg: ScalafmtConfig): Doc =
            Doc.clause(
                defnPolicy(cfg),
                Doc.text("class Foo("),
                Doc.text(")")
            )(texts("alpha: Int"))

        def brokenPrimary(cfg: ScalafmtConfig): Doc =
            Doc.clause(
                defnPolicy(cfg),
                Doc.text("class Foo("),
                Doc.text(")")
            )(texts("alpha: Int", "betaLongName: String"))

        "Always flows a multiline parent from the header" in {
            val c   = caseById("classic/parentconstructors-always-multiline-parent.stat:example")
            val doc = traitHeader(
                always,
                Doc.text("extends ").append(ctorCall(always, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), always) == c.expected)
        }

        "Never lists one parent per line" in {
            val c   = caseById("classic/parentconstructors-never-multiline-parent.stat:example")
            val doc = traitHeader(
                never,
                Doc.text("extends ").append(ctorCall(never, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), never) == c.expected)
        }

        "Oneline lists one parent per line when a parent is multiline" in {
            val c   = caseById("classic/parentconstructors-oneline-multiline-parent.stat:example")
            val doc = traitHeader(
                oneline,
                Doc.text("extends ").append(ctorCall(oneline, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), oneline) == c.expected)
        }

        "OnelineIfPrimaryOneline lists one parent per line when a parent is multiline" in {
            val c   = caseById("classic/parentconstructors-onelineifprimaryoneline-multiline-parent.stat:example")
            val doc = traitHeader(
                ifPrimaryFlat,
                Doc.text("extends ").append(ctorCall(ifPrimaryFlat, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), ifPrimaryFlat) == c.expected)
        }

        "Always flows the chain from a flat primary" in {
            val c   = caseById("classic/parentconstructors-always-primary-flat.stat:example")
            val doc = classHeader(
                always,
                flatPrimary(always),
                Doc.text("extends ").append(ctorCall(always, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), always) == c.expected)
        }

        "Oneline breaks before the first parent of a flat primary" in {
            val c   = caseById("classic/parentconstructors-oneline-primary-flat.stat:example")
            val doc = classHeader(
                oneline,
                flatPrimary(oneline),
                Doc.text("extends ").append(ctorCall(oneline, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), oneline) == c.expected)
        }

        "OnelineIfPrimaryOneline breaks before the first parent of a flat primary" in {
            val c   = caseById("classic/parentconstructors-onelineifprimaryoneline-primary-flat.stat:example")
            val doc = classHeader(
                ifPrimaryFlat,
                flatPrimary(ifPrimaryFlat),
                Doc.text("extends ").append(ctorCall(ifPrimaryFlat, "Bar")),
                Doc.text("with Baz"),
                Doc.text("with Qux")
            )
            assert(render(obj("object a {")(doc), ifPrimaryFlat) == c.expected)
        }

        "Always starts the parents inline after a broken primary" in {
            val c   = caseById("classic/parentconstructors-always-primary-broken.stat:example")
            val doc = classHeader(
                always,
                brokenPrimary(always),
                Doc.text("extends ").append(ctorCall(always, "Bar")),
                Doc.text("with Baz")
            )
            assert(render(obj("object a {")(doc), always) == c.expected)
        }

        "Oneline packs the chain inline after a broken primary" in {
            val c   = caseById("classic/parentconstructors-oneline-primary-broken.stat:example")
            val doc = classHeader(
                oneline,
                brokenPrimary(oneline),
                Doc.text("extends ").append(ctorCall(oneline, "Bar")),
                Doc.text("with Baz")
            )
            assert(render(obj("object a {")(doc), oneline) == c.expected)
        }

        "OnelineIfPrimaryOneline lists one parent per line after a broken primary" in {
            val c   = caseById("classic/parentconstructors-onelineifprimaryoneline-primary-broken.stat:example")
            val doc = classHeader(
                ifPrimaryFlat,
                brokenPrimary(ifPrimaryFlat),
                Doc.text("extends ").append(ctorCall(ifPrimaryFlat, "Bar")),
                Doc.text("with Baz")
            )
            assert(render(obj("object a {")(doc), ifPrimaryFlat) == c.expected)
        }

        "Always flows a mid-list multiline parent from the header" in {
            val c   = caseById("classic/parentconstructors-always-midlist-multiline.stat:example")
            val doc = traitHeader(
                always,
                Doc.text("extends Baz"),
                Doc.text("with ").append(ctorCall(always, "Qux")),
                Doc.text("with Bar")
            )
            assert(render(obj("object a {")(doc), always) == c.expected)
        }

        "Oneline lists one parent per line around a mid-list multiline parent" in {
            val c   = caseById("classic/parentconstructors-oneline-midlist-multiline.stat:example")
            val doc = traitHeader(
                oneline,
                Doc.text("extends Baz"),
                Doc.text("with ").append(ctorCall(oneline, "Qux")),
                Doc.text("with Bar")
            )
            assert(render(obj("object a {")(doc), oneline) == c.expected)
        }
    }

    "clause layout rules" - {

        "flat at exact fit, broken one column below" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Never)
            val clause = Doc.clause(policy, Doc.text("send("), Doc.text(")"))(
                Doc.text("alpha"),
                Doc.text("beta"),
                Doc.text("gammaLongIdentifier")
            )
            assert(Printer.scalafmt.print(clause, 38) == "send(alpha, beta, gammaLongIdentifier)")
            assert(Printer.scalafmt.print(clause, 37) ==
                """send(
                  |  alpha,
                  |  beta,
                  |  gammaLongIdentifier
                  |)""".stripMargin)
        }

        "binPack keeps the open attached and the close attached to the last argument" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Always)
            val clause = Doc.clause(policy, Doc.text("send("), Doc.text(")"))(
                Chunk.from(Seq("alpha", "beta", "gammaLongIdentifier", "delta").map(Doc.text(_)))
            )
            assert(Printer.scalafmt.print(clause, 30) ==
                """send(alpha, beta,
                  |  gammaLongIdentifier, delta)""".stripMargin)
        }

        "nested binPack clauses stack continuation indentation" in {
            def policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Always)
            val inner2 = Doc.clause(policy, Doc.text("baz1("), Doc.text(")"))(
                Doc.text("qux1"),
                Doc.text("qux2")
            )
            val inner1 = Doc.clause(policy, Doc.text("bar1("), Doc.text(")"))(
                inner2,
                Doc.text("baz2")
            )
            val outer = Doc.clause(policy, Doc.text("foo("), Doc.text(")"))(
                inner1,
                Doc.text("bar2(baz3, baz4)")
            )
            assert(Printer.scalafmt.print(outer, 20) ==
                """foo(bar1(baz1(qux1,
                  |      qux2), baz2),
                  |  bar2(baz3, baz4))""".stripMargin)
        }

        "an argument containing a hard newline breaks a Never clause" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Never)
            val clause = Doc.clause(policy, Doc.text("send("), Doc.text(")"))(
                Doc.text("alpha"),
                Doc.text("beta").append(Doc.hardNewline).append(Doc.text("gamma"))
            )
            assert(Printer.scalafmt.print(clause, 80) ==
                """send(
                  |  alpha,
                  |  beta
                  |  gamma
                  |)""".stripMargin)
        }
    }

    "printStream" - {
        "emits the same text as print, chunked by line" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, Doc.BinPackMode.Never)
            val clause = Doc.clause(policy, Doc.text("send("), Doc.text(")"))(
                Doc.text("alpha"),
                Doc.text("beta")
            )
            val doc     = clause.append(Doc.hardNewline).append(Doc.text("done"))
            val printed = Printer.scalafmt.print(doc, 10)
            val stream  = Printer.scalafmt.printStream(doc, 10).run.eval
            assert(stream.mkString == printed)
            assert(stream.length > 1)
        }
    }

end ScalafmtPrinterTest
