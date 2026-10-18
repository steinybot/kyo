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

    private def lambdaPolicy(config: ScalafmtConfig): Doc.ClausePolicy =
        config.clausePolicy(Doc.ClauseSite.Lambda)

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

    "oracle parity: configStyleArguments" - {

        def callClause(
            config: ScalafmtConfig,
            open: String,
            elems: Seq[String],
            forcedOpen: Boolean = false,
            forcedClose: Boolean = false
        ): Doc =
            Doc.clause(
                callPolicy(config),
                Doc.text(open),
                Doc.text(")"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = forcedOpen,
                forcedClose = forcedClose
            )(texts(elems*))

        def defnClause(
            config: ScalafmtConfig,
            elems: Seq[String],
            forcedOpen: Boolean = false,
            forcedClose: Boolean = false
        ): Doc =
            Doc.clause(
                defnPolicy(config),
                Doc.text("def f("),
                Doc.text("): Unit"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = forcedOpen,
                forcedClose = forcedClose
            )(texts(elems*)).append(Doc.text(" = ()"))

        val default  = ScalafmtConfig(maxColumn = 40)
        val noDangle = ScalafmtConfig(
            maxColumn = 40,
            danglingParentheses = ScalafmtConfig.DanglingParentheses(callSite = false)
        )
        val noDangleDefn = ScalafmtConfig(
            maxColumn = 40,
            danglingParentheses = ScalafmtConfig.DanglingParentheses(defnSite = false)
        )
        val noPrefer = ScalafmtConfig(
            maxColumn = 40,
            configStyle = ScalafmtConfig.ConfigStyle(callSite = false)
        )
        val aligned = ScalafmtConfig(
            maxColumn = 40,
            align = ScalafmtConfig.Align(openParenCallSite = true)
        )
        val always = ScalafmtConfig(
            maxColumn = 40,
            binPack = ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
        )
        val alwaysNoPrefer = always.copy(
            configStyle = ScalafmtConfig.ConfigStyle(callSite = false)
        )
        val alwaysNoDangle = always.copy(
            danglingParentheses = ScalafmtConfig.DanglingParentheses(callSite = false)
        )
        val literals = Seq("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee")

        "a close break alone folds when it fits" in {
            val c      = caseById("classic/cfgstyle-close-break-alone-folded.stat:example")
            val clause = callClause(
                default,
                "send(",
                Seq("alpha", "beta", "gammaLongIdentifier"),
                forcedClose = true
            )
            assert(render(obj("object a {")(clause), default) == c.expected)
        }

        "a close break alone folds at a definition site" in {
            val c      = caseById("classic/cfgstyle-defnsite-close-break-alone-folded.stat:example")
            val clause = defnClause(default, Seq("a: Int", "b: String"), forcedClose = true)
            assert(render(obj("object a {")(clause), default) == c.expected)
        }

        "breaks at both delimiters preserve the config-style form" in {
            val c      = caseById("classic/cfgstyle-defnsite-both-breaks-preserved.stat:example")
            val clause =
                defnClause(
                    default,
                    Seq("a: Int", "b: String"),
                    forcedOpen = true,
                    forcedClose = true
                )
            assert(render(obj("object a {")(clause), default) == c.expected)
        }

        "turning the preference off folds breaks at both delimiters" in {
            val c      = caseById("classic/cfgstyle-prefer-false-both-breaks-folded.stat:example")
            val clause =
                callClause(
                    noPrefer,
                    "send(",
                    Seq("alpha", "beta", "gamma"),
                    forcedOpen = true,
                    forcedClose = true
                )
            assert(render(obj("object a {")(clause), noPrefer) == c.expected)
        }

        "without dangling, a close break preserves the config-style form (scala.js rule)" in {
            val c      = caseById("classic/cfgstyle-scalajs-close-break-preserved.stat:example")
            val clause = callClause(
                noDangle,
                "send(",
                Seq("alpha", "beta", "gammaLongIdentifier"),
                forcedClose = true
            )
            assert(render(obj("object a {")(clause), noDangle) == c.expected)
        }

        "without dangling at a definition site, a close break preserves config style" in {
            val c      = caseById("classic/cfgstyle-defnsite-scalajs-close-break-preserved.stat:example")
            val clause = defnClause(noDangleDefn, Seq("a: Int", "b: String"), forcedClose = true)
            assert(render(obj("object a {")(clause), noDangleDefn) == c.expected)
        }

        "alignment does not block config-style preservation and is not applied to it" in {
            val c      = caseById("classic/cfgstyle-align-both-breaks-preserved.stat:example")
            val clause =
                callClause(
                    aligned,
                    "send(",
                    Seq("alpha", "beta", "gamma"),
                    forcedOpen = true,
                    forcedClose = true
                )
            assert(render(obj("object a {")(clause), aligned) == c.expected)
        }

        "config-style preservation overrides bin-packing" in {
            val c      = caseById("classic/binpack-always-configstyle-both-breaks.stat:example")
            val clause = callClause(
                always,
                "program(",
                literals,
                forcedOpen = true,
                forcedClose = true
            )
            assert(render(obj("object a {")(clause), always) == c.expected)
        }

        "without the preference, bin-packing keeps both delimiter breaks and packs between" in {
            val c      = caseById("classic/binpack-always-prefer-false-both-breaks-kept.stat:example")
            val clause = callClause(
                alwaysNoPrefer,
                "program(",
                literals,
                forcedOpen = true,
                forcedClose = true
            )
            assert(render(obj("object a {")(clause), alwaysNoPrefer) == c.expected)
        }

        "without dangling, bin-packing yields to a close break (scala.js rule)" in {
            val c      = caseById("classic/binpack-always-scalajs-close-break-preserved.stat:example")
            val clause =
                callClause(
                    alwaysNoDangle,
                    "program(",
                    Seq("alpha", "beta", "gamma"),
                    forcedClose = true
                )
            assert(render(obj("object a {")(clause), alwaysNoDangle) == c.expected)
        }

        "without the preference, a lone close break folds under bin-packing" in {
            val c      = caseById("classic/binpack-prefer-false-close-break-folded.stat:example")
            val clause =
                callClause(
                    alwaysNoPrefer,
                    "program(",
                    Seq("alpha", "beta", "gamma"),
                    forcedClose = true
                )
            assert(render(obj("object a {")(clause), alwaysNoPrefer) == c.expected)
        }

        "newlines.configStyleDefnSite.prefer (docs/newlines-configstylexxxsite-prefer-1)" in {
            val c      = caseById("docs/newlines-configstylexxxsite-prefer-1.stat:example")
            val config = ScalafmtConfig(maxColumn = 45)
            val defn1  = Doc.clause(
                defnPolicy(config),
                Doc.text("def method1("),
                Doc.text("): Boolean")
            )(texts("a: Int", "b: String"))
            val defn2 = Doc.clause(
                defnPolicy(config),
                Doc.text("def method2("),
                Doc.text("): Boolean")
            )(texts("a: Int", "b: String", "c: String"))
            val defn3 = Doc.clause(
                defnPolicy(config),
                Doc.text("def method3("),
                Doc.text("): Boolean"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = true
            )(texts("a: Int", "b: String", "c: String"))
            val body = Doc.text("// keeps single line").append(Doc.hardNewline).append(defn1)
                .append(Doc.hardNewline).append(Doc.hardNewline).append(Doc.text("// forces config style"))
                .append(Doc.hardNewline).append(defn2)
                .append(Doc.hardNewline).append(Doc.hardNewline).append(Doc.text("// preserves config style"))
                .append(Doc.hardNewline).append(defn3)
            val doc = obj("object a {")(body)
            assert(render(doc, config) == c.expected)
        }

        "newlines.source = fold folds broken call site to flat (classic/source-fold-callsite-broken)" in {
            val c      = caseById("classic/source-fold-callsite-broken.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Fold)
            )
            val clause = callClause(config, "send(", Seq("alpha", "beta", "gamma"), forcedOpen = true, forcedClose = true)
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = fold preserves flat call site (classic/source-fold-callsite-flat)" in {
            val c      = caseById("classic/source-fold-callsite-flat.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Fold)
            )
            val clause = callClause(config, "send(", Seq("alpha", "beta", "gamma"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = fold breaks overflowing call site from broken source (classic/source-fold-callsite-overflow-broken)" in {
            val c      = caseById("classic/source-fold-callsite-overflow-broken.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Fold)
            )
            val clause = callClause(
                config,
                "program(",
                Seq("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee"),
                forcedOpen = true,
                forcedClose = true
            )
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = fold breaks overflowing call site from flat source (classic/source-fold-callsite-overflow-flat)" in {
            val c      = caseById("classic/source-fold-callsite-overflow-flat.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Fold)
            )
            val clause = callClause(
                config,
                "program(",
                Seq("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee")
            )
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = fold folds broken defn site to flat (classic/source-fold-defnsite-broken)" in {
            val c      = caseById("classic/source-fold-defnsite-broken.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Fold)
            )
            val clause = Doc.clause(
                defnPolicy(config),
                Doc.text("def f("),
                Doc.text("): Unit = ()"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = true
            )(texts("a: Int", "b: String"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = keep keeps broken call site (classic/source-keep-callsite-broken)" in {
            val c      = caseById("classic/source-keep-callsite-broken.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Keep)
            )
            val clause = callClause(config, "send(", Seq("alpha", "beta", "gamma"), forcedOpen = true, forcedClose = true)
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = keep keeps flat call site flat (classic/source-keep-callsite-flat)" in {
            val c      = caseById("classic/source-keep-callsite-flat.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Keep)
            )
            val clause = callClause(config, "send(", Seq("alpha", "beta", "gamma"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.source = keep keeps broken defn site (classic/source-keep-defnsite-broken)" in {
            val c      = caseById("classic/source-keep-defnsite-broken.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(source = ScalafmtConfig.Newlines.Source.Keep)
            )
            val clause = Doc.clause(
                defnPolicy(config),
                Doc.text("def f("),
                Doc.text("): Unit = ()"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true,
                forcedClose = true
            )(texts("a: Int", "b: String"))
            assert(render(obj("object a {")(clause), config) == c.expected)
        }

        "newlines.beforeOpenParenXxxSite (docs/newlines-beforeopenparenxxxsite-1)" in {
            val c      = caseById("docs/newlines-beforeopenparenxxxsite-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 20,
                align = ScalafmtConfig.Align(openParenDefnSite = true),
                newlines = ScalafmtConfig.Newlines(
                    beforeOpenParenDefnSite = Some(ScalafmtConfig.Newlines.BeforeOpenParen.Fold),
                    beforeOpenParenCallSite = Some(ScalafmtConfig.Newlines.BeforeOpenParen.Unfold),
                    sometimesBeforeColonInMethodReturnType = true
                )
            )
            val defn1 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("foo1: String"))
            val defn2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("foo2: String"), Doc.text("foo3: String"))
            val defn  = Doc.text("def fooFunc")
                .append(defn1)
                .append(defn2)
                .append(Doc.hardNewline)
                .append(Doc.text("  : String = ???"))
            val call1 = Doc.clause(callPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("\"foo1\""))
            val call2 = Doc.clause(callPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("\"foo2\""), Doc.text("\"foo3\""))
            val call  = Doc.text("val res = fooFunc")
                .append(call1)
                .append(call2)
            val d = defn.append(Doc.hardNewline).append(call)
            assert(render(d, config) == c.expected)
        }

        "newlines.beforeOpenParenXxxSite (docs/newlines-beforeopenparenxxxsite-2)" in {
            val c      = caseById("docs/newlines-beforeopenparenxxxsite-2.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 20,
                align = ScalafmtConfig.Align(openParenCallSite = true),
                newlines = ScalafmtConfig.Newlines(
                    beforeOpenParenDefnSite = Some(ScalafmtConfig.Newlines.BeforeOpenParen.Unfold),
                    beforeOpenParenCallSite = Some(ScalafmtConfig.Newlines.BeforeOpenParen.Fold),
                    sometimesBeforeColonInMethodReturnType = false
                )
            )
            val defn1 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("foo1: String"))
            val defn2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text("): String = ???"))(
                Doc.text("foo2: String"),
                Doc.text("foo3: String")
            )
            val defn = Doc.text("def fooFunc")
                .append(defn1)
                .append(defn2)
            val call1 = Doc.clause(callPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("\"foo1\""))
            val call2 = Doc.clause(callPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("\"foo2\""), Doc.text("\"foo3\""))
            val call  = Doc.text("val res = fooFunc")
                .append(call1)
                .append(call2)
            val d = defn.append(Doc.hardNewline).append(call)
            assert(render(d, config) == c.expected)
        }

        "newlines.sometimesBeforeColonInMethodReturnType (docs/newlines-sometimesbeforecoloninmethodreturntype-1)" in {
            val c      = caseById("docs/newlines-sometimesbeforecoloninmethodreturntype-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(sometimesBeforeColonInMethodReturnType = true)
            )
            val doc = Doc.text("def someVeryLongMethodName")
                .append(Doc.hardNewline)
                .append(Doc.text("    : Map[String, String] = ???"))
            assert(render(doc, config) == c.expected)
        }

        "newlines.alwaysBeforeElseAfterCurlyIf = true (docs/newlines-alwaysbeforeelseaftercurlyif-1)" in {
            val c      = caseById("docs/newlines-alwaysbeforeelseaftercurlyif-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(alwaysBeforeElseAfterCurlyIf = true)
            )
            val ifBranch = Doc.text("if (someCond) {")
                .append(Doc.hardNewline).append(Doc.text("  foo()"))
                .append(Doc.hardNewline).append(Doc.text("}"))
            val elseBranch = Doc.hardNewline.append(Doc.text("else {"))
                .append(Doc.hardNewline).append(Doc.text("  bar()"))
                .append(Doc.hardNewline).append(Doc.text("}"))
            val doc = ifBranch.append(elseBranch)
            assert(render(doc, config) == c.expected)
        }

        "newlines.alwaysBeforeElseAfterCurlyIf = false (docs/newlines-alwaysbeforeelseaftercurlyif-2)" in {
            val c      = caseById("docs/newlines-alwaysbeforeelseaftercurlyif-2.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(alwaysBeforeElseAfterCurlyIf = false)
            )
            val ifBranch = Doc.text("if (someCond) {")
                .append(Doc.hardNewline).append(Doc.text("  foo()"))
                .append(Doc.hardNewline).append(Doc.text("}"))
            val elseBranch = Doc.space.append(Doc.text("else {"))
                .append(Doc.hardNewline).append(Doc.text("  bar()"))
                .append(Doc.hardNewline).append(Doc.text("}"))
            val doc = ifBranch.append(elseBranch)
            assert(render(doc, config) == c.expected)
        }

        "newlines.beforeCurlyLambdaParams = never (docs/newlines-beforecurlylambdaparams-1)" in {
            val c      = caseById("docs/newlines-beforecurlylambdaparams-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(beforeCurlyLambdaParams = ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Never)
            )
            val p  = lambdaPolicy(config)
            val l1 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l2 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l3 = Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l4 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i (long comment)\"")))
            val doc = Doc.text("// should keep one-line").append(Doc.hardNewline).append(l1)
                .append(Doc.hardNewline).append(l2)
                .append(Doc.hardNewline).append(Doc.text("// should break on arrow since case doesn't fit on a line"))
                .append(Doc.hardNewline).append(l3)
                .append(Doc.hardNewline).append(l4)
            assert(render(doc, config) == c.expected)
        }

        "newlines.beforeCurlyLambdaParams = always (docs/newlines-beforecurlylambdaparams-2)" in {
            val c      = caseById("docs/newlines-beforecurlylambdaparams-2.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(beforeCurlyLambdaParams = ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Always)
            )
            val p  = lambdaPolicy(config)
            val l1 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l2 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l3 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i (long comment)\"")))
            val l4 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2} (long comment)\"")))
            val doc = Doc.text("// should break on brace, though fits on the same line").append(Doc.hardNewline).append(l1)
                .append(Doc.hardNewline).append(l2)
                .append(Doc.hardNewline).append(l3)
                .append(Doc.hardNewline).append(Doc.text("// should break on brace and arrow as lambda doesn't fit on a line"))
                .append(Doc.hardNewline).append(l4)
            assert(render(doc, config) == c.expected)
        }

        "newlines.beforeCurlyLambdaParams = multiline (docs/newlines-beforecurlylambdaparams-3)" in {
            val c      = caseById("docs/newlines-beforecurlylambdaparams-3.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(beforeCurlyLambdaParams = ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.Multiline)
            )
            val p  = lambdaPolicy(config)
            val l1 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l2 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l3 = Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l4 = Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l5 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2} (long comment)\"")))
            val l6 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i (long comment)\"")))
            val doc = Doc.text("// should keep one-line").append(Doc.hardNewline).append(l1)
                .append(Doc.hardNewline).append(l2)
                .append(Doc.hardNewline).append(Doc.text("// should break on brace as lambda doesn't fit on the same line"))
                .append(Doc.hardNewline).append(l3)
                .append(Doc.hardNewline).append(l4)
                .append(Doc.hardNewline).append(Doc.text("// should break on brace and arrow as lambda doesn't fit on a line"))
                .append(Doc.hardNewline).append(l5)
                .append(Doc.hardNewline).append(l6)
            assert(render(doc, config) == c.expected)
        }

        "newlines.beforeCurlyLambdaParams = multilineWithCaseOnly (docs/newlines-beforecurlylambdaparams-4)" in {
            val c      = caseById("docs/newlines-beforecurlylambdaparams-4.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines =
                    ScalafmtConfig.Newlines(beforeCurlyLambdaParams = ScalafmtConfig.Newlines.BeforeCurlyLambdaParams.MultilineWithCaseOnly)
            )
            val p  = lambdaPolicy(config)
            val l1 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l2 = Doc.text("x.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l3 = Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2}\"")))
            val l4 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("s\"${x._1} -> ${x._2} (long comment)\"")))
            val l5 = Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i\"")))
            val l6 =
                Doc.text("x.zipWithIndex.map ").append(Doc.lambda(p, Doc.text("case (c, i) =>"), Doc.text("s\"$c -> $i (long comment)\"")))
            val doc = Doc.text("// should keep one-line").append(Doc.hardNewline).append(l1)
                .append(Doc.hardNewline).append(l2)
                .append(Doc.hardNewline).append(Doc.text("// should break after arrow as lambda doesn't fit on the same line"))
                .append(Doc.hardNewline).append(l3)
                .append(Doc.hardNewline).append(l4)
                .append(Doc.hardNewline).append(Doc.text("// should break on brace as lambda doesn't fit on the same line"))
                .append(Doc.hardNewline).append(l5)
                .append(Doc.hardNewline).append(Doc.text("// should break on brace and arrow as lambda doesn't fit on a line"))
                .append(Doc.hardNewline).append(l6)
            assert(render(doc, config) == c.expected)
        }

        "newlines.afterCurlyLambdaParams = squash (docs/newlines-aftercurlylambdaparams-1)" in {
            val c      = caseById("docs/newlines-aftercurlylambdaparams-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(afterCurlyLambdaParams = ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Squash)
            )
            val p   = lambdaPolicy(config)
            val l1  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)"), forcedOpen = true))
            val l2  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)")))
            val doc = Doc.text("// remove all blank lines if any").append(Doc.hardNewline)
                .append(Doc.text("// one-line formatting is allowed")).append(Doc.hardNewline)
                .append(l1).append(Doc.hardNewline).append(Doc.hardNewline)
                .append(l2)
            assert(render(doc, config) == c.expected)
        }

        "newlines.afterCurlyLambdaParams = never (docs/newlines-aftercurlylambdaparams-2)" in {
            val c      = caseById("docs/newlines-aftercurlylambdaparams-2.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(afterCurlyLambdaParams = ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Never)
            )
            val p   = lambdaPolicy(config)
            val l1  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)"), forcedOpen = true))
            val l2  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)")))
            val doc = Doc.text("// remove all blank lines if any").append(Doc.hardNewline)
                .append(Doc.text("// one-line formatting depends on newlines.source:")).append(Doc.hardNewline)
                .append(Doc.text("// yes for fold; no for unfold; otherwise, only if there was no break")).append(Doc.hardNewline)
                .append(l1).append(Doc.hardNewline).append(Doc.hardNewline)
                .append(l2)
            assert(render(doc, config) == c.expected)
        }

        "newlines.afterCurlyLambdaParams = keep (docs/newlines-aftercurlylambdaparams-3)" in {
            val c      = caseById("docs/newlines-aftercurlylambdaparams-3.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(afterCurlyLambdaParams = ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Keep)
            )
            val p   = lambdaPolicy(config)
            val l1  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)"), forcedOpen = true))
            val l2  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)")))
            val doc = Doc.text("// if blank lines are present, keep only one").append(Doc.hardNewline)
                .append(Doc.text("// one-line formatting depends on newlines.source:")).append(Doc.hardNewline)
                .append(Doc.text("// if no blank for fold; no for unfold; otherwise, only if there was no break")).append(Doc.hardNewline)
                .append(l1).append(Doc.hardNewline).append(Doc.hardNewline)
                .append(l2)
            assert(render(doc, config) == c.expected)
        }

        "newlines.afterCurlyLambdaParams = always (docs/newlines-aftercurlylambdaparams-4)" in {
            val c      = caseById("docs/newlines-aftercurlylambdaparams-4.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                newlines = ScalafmtConfig.Newlines(afterCurlyLambdaParams = ScalafmtConfig.Newlines.AfterCurlyLambdaParams.Always)
            )
            val p   = lambdaPolicy(config)
            val l1  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)"), forcedOpen = true))
            val l2  = Doc.text("something.map ").append(Doc.lambda(p, Doc.text("x =>"), Doc.text("f(x)")))
            val doc = Doc.text("// ensure a single blank line").append(Doc.hardNewline)
                .append(Doc.text("// one-line formatting is not allowed")).append(Doc.hardNewline)
                .append(l1).append(Doc.hardNewline).append(Doc.hardNewline)
                .append(l2)
            assert(render(doc, config) == c.expected)
        }
    }

    "oracle parity: verticalMultiline defn formatting" - {

        "below arityThreshold stays flat on single line" in {
            val c      = caseById("classic/verticalmultiline-below-threshold.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true, arityThreshold = 3)
            )
            val clause = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text("): Unit = ()"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val doc = obj("object a {")(clause)
            assert(render(doc, config) == c.expected)
        }

        "at arityThreshold breaks vertically" in {
            val c      = caseById("classic/verticalmultiline-at-threshold.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true, arityThreshold = 2)
            )
            val clause = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text("): Unit = ()"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val doc = obj("object a {")(clause)
            assert(render(doc, config) == c.expected)
        }

        "above arityThreshold breaks vertically" in {
            val c      = caseById("classic/verticalmultiline-above-threshold.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true, arityThreshold = 2)
            )
            val clause = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text("): Unit = ()"))(
                Doc.text("a: Int"),
                Doc.text("b: String"),
                Doc.text("c: Long")
            )
            val doc = obj("object a {")(clause)
            assert(render(doc, config) == c.expected)
        }

        "curried groups format with attached close and continuation at threshold" in {
            val c      = caseById("classic/verticalmultiline-curried-at-threshold.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true, arityThreshold = 2)
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text(")"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text("): Unit = ()"))(
                Doc.text("c: Double")
            )
            val doc = obj("object a {")(g1.append(g2))
            assert(render(doc, config) == c.expected)
        }

        "newlineAfterOpenParen forces breaks in continuation parameter groups" in {
            val c      = caseById("classic/verticalmultiline-newline-after-open-paren.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlineAfterOpenParen = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text(")"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text("): Unit = ()"))(
                Doc.text("c: Double"),
                Doc.text("d: Long")
            )
            val doc = obj("object a {")(g1.append(g2))
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter group with newlineAfterImplicitKW" in {
            val c      = caseById("classic/verticalmultiline-implicit-after.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlinesAfterImplicitKW = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text(")"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): Unit = ()"))(
                Doc.text("c: Double"),
                Doc.text("d: Long")
            )
            val doc = obj("object a {")(g1.append(g2))
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter group with newlineBeforeImplicitKW" in {
            val c      = caseById("classic/verticalmultiline-implicit-before.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlinesBeforeImplicitKW = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text(")"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): Unit = ()"))(
                Doc.text("c: Double"),
                Doc.text("d: Long")
            )
            val doc = obj("object a {")(g1.append(g2))
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter group with newlineBeforeImplicitKW and newlineAfterImplicitKW" in {
            val c      = caseById("classic/verticalmultiline-implicit-before-after.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlinesBeforeImplicitKW = true,
                    newlinesAfterImplicitKW = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def f("), Doc.text(")"))(
                Doc.text("a: Int"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): Unit = ()"))(
                Doc.text("c: Double"),
                Doc.text("d: Long")
            )
            val doc = obj("object a {")(g1.append(g2))
            assert(render(doc, config) == c.expected)
        }

        "arityThreshold boundary on classes and defs (docs/verticalmultiline-aritythreshold-1)" in {
            val c      = caseById("docs/verticalmultiline-aritythreshold-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true, arityThreshold = 2)
            )
            val foo = Doc.clause(defnPolicy(config), Doc.text("case class Foo("), Doc.text(")"))(
                Doc.text("x: String")
            )
            val bar = Doc.clause(
                defnPolicy(config).copy(dangling = false),
                Doc.text("case class Bar("),
                Doc.text(")")
            )(
                Doc.text("x: String"),
                Doc.text("y: String")
            )
            val fooDef = Doc.clause(defnPolicy(config), Doc.text("def foo("), Doc.text(")"))(
                Doc.text("x: String"),
                Doc.text("y: String")
            )
            val hello = Doc.clause(defnPolicy(config), Doc.text("def hello("), Doc.text(")"))(Doc.text("how: String"))
                .append(Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(")"))(Doc.text("are: String")))
                .append(Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(") = how + are + you"))(Doc.text("you: String")))
            val body = fooDef.append(Doc.hardNewline).append(hello)
            val doc  = foo.append(Doc.hardNewline).append(bar).append(Doc.hardNewline).append(obj("object A {")(body))
            assert(render(doc, config) == c.expected)
        }

        "newlineAfterOpenParen = true with indent.defnSite = 2 (docs/verticalmultiline-newlineafteropenparen-1)" in {
            val c      = caseById("docs/verticalmultiline-newlineafteropenparen-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                indent = ScalafmtConfig.Indent(defnSite = 2),
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlineAfterOpenParen = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def other("), Doc.text(")"))(
                Doc.text("a: String"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(") = a + b + c"))(
                Doc.text("c: String"),
                Doc.text("d: String")
            )
            val doc = g1.append(g2)
            assert(render(doc, config) == c.expected)
        }

        "danglingParentheses excluded with verticalMultiline (docs/verticalmultiline-excludedanglingparens-1)" in {
            val c      = caseById("docs/verticalmultiline-excludedanglingparens-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 40,
                indent = ScalafmtConfig.Indent(defnSite = 2),
                danglingParentheses = ScalafmtConfig.DanglingParentheses(defnSite = false),
                verticalMultiline = ScalafmtConfig.VerticalMultiline(
                    atDefnSite = true,
                    arityThreshold = 2,
                    newlineAfterOpenParen = true
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def other("), Doc.text(")"))(
                Doc.text("a: String"),
                Doc.text("b: String")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("("), Doc.text(") = a + b + c"))(
                Doc.text("c: String"),
                Doc.text("d: String")
            )
            val call = Doc.clause(callPolicy(config), Doc.text("other("), Doc.text(")"))(
                Doc.text("a"),
                Doc.text("b")
            ).append(Doc.clause(callPolicy(config), Doc.text("("), Doc.text(")"))(
                Doc.text("c"),
                Doc.text("d")
            ))
            val doc = g1.append(g2).append(Doc.hardNewline).append(call)
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter list modifier force = [before] (docs/vertical-multiline-with-implicit-parameter-lists-1)" in {
            val c      = caseById("docs/vertical-multiline-with-implicit-parameter-lists-1.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 60,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true),
                newlines = ScalafmtConfig.Newlines(
                    implicitParamListModifierForce = List(ScalafmtConfig.Newlines.BeforeAfter.Before)
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def format("), Doc.text(")"))(
                Doc.text("code: String"),
                Doc.text("age: Int")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): String"))(
                Doc.text("ev: Parser"),
                Doc.text("c: Context")
            )
            val doc = g1.append(g2)
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter list modifier force = [after] (docs/vertical-multiline-with-implicit-parameter-lists-2)" in {
            val c      = caseById("docs/vertical-multiline-with-implicit-parameter-lists-2.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 60,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true),
                newlines = ScalafmtConfig.Newlines(
                    implicitParamListModifierForce = List(ScalafmtConfig.Newlines.BeforeAfter.After)
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def format("), Doc.text(")"))(
                Doc.text("code: String"),
                Doc.text("age: Int")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): String"))(
                Doc.text("ev: Parser"),
                Doc.text("c: Context")
            )
            val doc = g1.append(g2)
            assert(render(doc, config) == c.expected)
        }

        "implicit parameter list modifier force = [before, after] (docs/vertical-multiline-with-implicit-parameter-lists-3)" in {
            val c      = caseById("docs/vertical-multiline-with-implicit-parameter-lists-3.stat:example")
            val config = ScalafmtConfig(
                maxColumn = 60,
                verticalMultiline = ScalafmtConfig.VerticalMultiline(atDefnSite = true),
                newlines = ScalafmtConfig.Newlines(
                    implicitParamListModifierForce = List(
                        ScalafmtConfig.Newlines.BeforeAfter.Before,
                        ScalafmtConfig.Newlines.BeforeAfter.After
                    )
                )
            )
            val g1 = Doc.clause(defnPolicy(config), Doc.text("def format("), Doc.text(")"))(
                Doc.text("code: String"),
                Doc.text("age: Int")
            )
            val g2 = Doc.clause(defnPolicy(config), Doc.text("(implicit "), Doc.text("): String"))(
                Doc.text("ev: Parser"),
                Doc.text("c: Context")
            )
            val doc = g1.append(g2)
            assert(render(doc, config) == c.expected)
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

    "aligned columns" - {
        "single column alignment pads to column boundary" in {
            val doc = Doc.align { col =>
                Doc.concat(
                    Doc.text("val a "),
                    col,
                    Doc.text("= 1"),
                    Doc.newline,
                    Doc.text("val longerName "),
                    col,
                    Doc.text("= 2")
                )
            }
            assert(Printer.scalafmt.print(doc, 80) == "val a          = 1\nval longerName = 2")
        }

        "multi-column alignment aligns table columns" in {
            val doc = Doc.align(2) { cols =>
                Doc.concat(
                    Doc.text("a"),
                    cols(0),
                    Doc.text("b"),
                    cols(1),
                    Doc.text("c"),
                    Doc.newline,
                    Doc.text("long"),
                    cols(0),
                    Doc.text("longer"),
                    cols(1),
                    Doc.text("d")
                )
            }
            assert(Printer.scalafmt.print(doc, 80) == "a   b     c\nlonglongerd")
        }
    }

    "scoped group choices" - {
        "all choices flat when group fits within width" in {
            val doc = Doc.group { choice =>
                Doc.concat(
                    Doc.text("call("),
                    choice.spaceOrNewline,
                    Doc.text("arg1,"),
                    choice.spaceOrNewline,
                    Doc.text("arg2)")
                )
            }
            assert(Printer.scalafmt.print(doc, 30) == "call( arg1, arg2)")
        }

        "all choices broken when group exceeds width" in {
            val doc = Doc.group { choice =>
                Doc.concat(
                    Doc.text("call("),
                    choice(Doc.empty, Doc.newline.indent(4)),
                    Doc.text("arg1,"),
                    choice(Doc.space, Doc.newline.indent(4)),
                    Doc.text("arg2)")
                )
            }
            assert(Printer.scalafmt.print(doc, 10) == "call(\n    arg1,\n    arg2)")
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
