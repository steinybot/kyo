package kyo

/** Validates that the scalafmt parity engine renders oracle fixtures
  * byte-identically.
  *
  * Each test builds by hand the Doc tree an emitter would construct for the
  * oracle case's input (clauses carry the site policy scalafmt resolves from
  * its configuration) and asserts the engine output equals the expected
  * output committed by scalafmt's own suite. Fixtures are never compared
  * against self-generated expectations.
  */
class ScalafmtPrinterTest extends kyo.test.Test[Any]:

    private def caseById(id: String)(using kyo.test.AssertScope): ScalafmtOracle.Case =
        ScalafmtOracle.cases.find(_.id == id).getOrElse(fail(s"missing oracle case $id"))

    private def render(doc: Doc, config: ScalafmtConfig): String =
        Printer.scalafmt.print(doc, config.maxColumn) + "\n"

    private def callSite(
        config: ScalafmtConfig,
        binPack: ScalafmtConfig.BinPack.Site
    ): Doc.ClausePolicy =
        Doc.ClausePolicy(
            site = Doc.ClauseSite.Call,
            indent = config.indent.callSite,
            dangling = config.danglingParentheses.callSite,
            align = config.align.openParenCallSite,
            binPack = binPack
        )
    end callSite

    private def defnSite(config: ScalafmtConfig): Doc.ClausePolicy =
        Doc.ClausePolicy(
            site = Doc.ClauseSite.Defn,
            indent = config.indent.defnSite,
            dangling = config.danglingParentheses.defnSite,
            align = config.align.openParenDefnSite,
            binPack = ScalafmtConfig.BinPack.Site.Never
        )
    end defnSite

    private def extendSite(
        config: ScalafmtConfig,
        binPack: ScalafmtConfig.BinPack.Site
    ): Doc.ClausePolicy =
        Doc.ClausePolicy(
            site = Doc.ClauseSite.Extend,
            indent = config.indent.extendSite,
            dangling = false,
            align = false,
            binPack = binPack
        )
    end extendSite

    private def obj(header: String)(body: Doc): Doc =
        Doc.text(header).append(Doc.hardNewline)
            .append(body.indent(2))
            .append(Doc.hardNewline).append(Doc.text("}"))
    end obj

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
                defnSite(config),
                Doc.text("def method("),
                Doc.text("): Boolean")
            )(Doc.text("a: Int"), Doc.text("b: String"))
            val call = Doc.clause(
                callSite(config, ScalafmtConfig.BinPack.Site.Never),
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
                defnSite(config),
                Doc.text("def method("),
                Doc.text("): Boolean")
            )(Doc.text("a: Int"), Doc.text("b: String"))
            val call = Doc.clause(
                callSite(config, ScalafmtConfig.BinPack.Site.Never),
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
                extendSite(config, ScalafmtConfig.BinPack.Site.Never),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = false
            )(Doc.text("extends Bar"), Doc.text("with Baz"))
            val doc = obj("object A {")(clause)
            assert(render(doc, config) == c.expected)
        }

        "parentConstructors Always packs a forced-open clause (docs/binpack-parentconstructors-1)" in {
            val c = caseById("docs/binpack-parentconstructors-1.stat:example")
            // The mdoc fence overrides the docs base maxColumn with 30, so
            // both traits break; the first is forced open by the source break
            // after its name, the second by fit. Always packs the parents.
            val config = ScalafmtConfig(maxColumn = 30)
            val broken = Doc.clause(
                extendSite(config, ScalafmtConfig.BinPack.Site.Always),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = true
            )(Doc.text("extends Bar"), Doc.text("with Baz"))
            val flat = Doc.clause(
                extendSite(config, ScalafmtConfig.BinPack.Site.Always),
                Doc.text("trait Foo"),
                close = Doc.empty,
                sep = Doc.empty,
                trailingSep = false,
                forcedOpen = false
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
            callSite(config, ScalafmtConfig.BinPack.Site.Always),
            Doc.text("("),
            Doc.text(")")
        )(elems)
        Doc.text(prefix).append(clause)
    end literalListClause

    "clause layout rules" - {

        "flat at exact fit, broken one column below" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Never)
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
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Always)
            val clause = Doc.clause(policy, Doc.text("send("), Doc.text(")"))(
                Chunk.from(Seq("alpha", "beta", "gammaLongIdentifier", "delta").map(Doc.text(_)))
            )
            assert(Printer.scalafmt.print(clause, 30) ==
                """send(alpha, beta,
                  |  gammaLongIdentifier, delta)""".stripMargin)
        }

        "forcedOpen makes binPack dangle the close while still packing" in {
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Always)
            val clause = Doc.clause(
                policy,
                Doc.text("send("),
                Doc.text(")"),
                sep = Doc.text(","),
                trailingSep = false,
                forcedOpen = true
            )(Chunk.from(Seq("alpha", "beta", "gammaLongIdentifier", "delta").map(Doc.text(_))))
            assert(Printer.scalafmt.print(clause, 20) ==
                """send(
                  |  alpha, beta,
                  |  gammaLongIdentifier,
                  |  delta
                  |)""".stripMargin)
        }

        "nested binPack clauses stack continuation indentation" in {
            def policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Always)
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
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Never)
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
            val policy = Doc.ClausePolicy(Doc.ClauseSite.Call, 2, true, false, ScalafmtConfig.BinPack.Site.Never)
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
