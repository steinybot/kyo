package kyo

/** Validates that Doc layout combinators reproduce the scalafmt styles
  * documented for the options modeled by [[ScalafmtConfig]].
  *
  * Canonical fixtures are built as `Doc < Env[ScalafmtConfig]` values: each
  * fixture reads the configuration from the environment and selects the
  * combinators matching the documented style. `render` evaluates a fixture
  * under a configuration with the greedy printer and the configured
  * `maxColumn` as the layout width.
  */
class ScalafmtConfigTest extends kyo.test.Test[Any]:

    private def render(config: ScalafmtConfig)(doc: Doc < Env[ScalafmtConfig]): String =
        Env.run(config) {
            Env.use[ScalafmtConfig] { c =>
                doc.map(Printer.greedy.print(_, c.maxColumn))
            }
        }.eval

    private def materialize(config: ScalafmtConfig)(doc: Doc < Env[ScalafmtConfig]): Doc =
        Env.run(config)(doc).eval

    /** Call site fixture: `send(...)` broken according to indent, dangling,
      * align, and binPack call-site options. Align takes precedence over
      * bin-packing, mirroring how scalafmt disables packing when arguments
      * align to the opening parenthesis.
      */
    private def callArgs(args: String*): Doc < Env[ScalafmtConfig] =
        Env.use[ScalafmtConfig] { config =>
            val docs  = Chunk.from(args.map(Doc.text(_)))
            val open  = Doc.text("send(")
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

    /** Definition site fixture: `def process(...)` broken according to indent,
      * dangling, and align defn-site options.
      */
    private def defParams(params: (String, String)*): Doc < Env[ScalafmtConfig] =
        Env.use[ScalafmtConfig] { config =>
            val docs  = Chunk.from(params.map((n, t) => Doc.text(s"$n: $t")))
            val open  = Doc.text("def process(")
            val sep   = Doc.text(",")
            val close = Doc.text(")")
            if config.align.openParenDefnSite then
                docs.hang(open, sep, close)
            else if config.danglingParentheses.defnSite then
                docs.chop(open, sep, close, config.indent.defnSite)
            else
                docs.chopClosed(open, sep, close, config.indent.defnSite)
            end if
        }

    /** Template fixture: `extends` and `with` clauses indented by the
      * extend-site option once the template breaks.
      */
    private def template(clauses: String*): Doc < Env[ScalafmtConfig] =
        Env.use[ScalafmtConfig] { config =>
            Doc.text("class Repository").append(Doc.newline).append(
                Doc.stack(clauses.map(Doc.text(_))*).indent(config.indent.extendSite)
            )
        }

    "defaults" - {
        "default preset mirrors the scalafmt default preset" in {
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
        }

        "construction without arguments yields the default preset" in {
            assert(ScalafmtConfig() == ScalafmtConfig.default)
        }
    }

    "environment" - {
        "fixtures read the configuration from the environment" in {
            val fixture = callArgs("alpha", "beta", "gammaLongIdentifier")
            assert(render(ScalafmtConfig.default)(fixture) == "send(alpha, beta, gammaLongIdentifier)")
            assert(render(ScalafmtConfig(maxColumn = 37))(fixture) != "send(alpha, beta, gammaLongIdentifier)")
        }
    }

    "maxColumn" - {
        val fixture = callArgs("alpha", "beta", "gammaLongIdentifier")

        "call stays flat at exact fit" in {
            // Flat form "send(alpha, beta, gammaLongIdentifier)" is 38 columns.
            assert(render(ScalafmtConfig(maxColumn = 38))(fixture) == "send(alpha, beta, gammaLongIdentifier)")
        }

        "call breaks one column below exact fit" in {
            val expected =
                """send(
                  |  alpha,
                  |  beta,
                  |  gammaLongIdentifier
                  |)""".stripMargin
            assert(render(ScalafmtConfig(maxColumn = 37))(fixture) == expected)
        }
    }

    "indent" - {
        "callSite widens broken argument indentation" in {
            val config   = ScalafmtConfig(maxColumn = 37, indent = ScalafmtConfig.Indent(callSite = 4))
            val expected =
                """send(
                  |    alpha,
                  |    beta,
                  |    gammaLongIdentifier
                  |)""".stripMargin
            assert(render(config)(callArgs("alpha", "beta", "gammaLongIdentifier")) == expected)
        }

        "defnSite widens broken parameter indentation" in {
            val config   = ScalafmtConfig(maxColumn = 30)
            val expected =
                """def process(
                  |    alpha: Int,
                  |    beta: String
                  |)""".stripMargin
            assert(render(config)(defParams(("alpha", "Int"), ("beta", "String"))) == expected)
        }

        "defnSite of 2 narrows broken parameter indentation" in {
            val config   = ScalafmtConfig(maxColumn = 30, indent = ScalafmtConfig.Indent(defnSite = 2))
            val expected =
                """def process(
                  |  alpha: Int,
                  |  beta: String
                  |)""".stripMargin
            assert(render(config)(defParams(("alpha", "Int"), ("beta", "String"))) == expected)
        }

        "extendSite indents broken template clauses" in {
            val expected =
                """class Repository
                  |    extends Store
                  |    with Logging""".stripMargin
            assert(render(ScalafmtConfig.default)(template("extends Store", "with Logging")) == expected)
        }

        "extendSite of 2 narrows broken template clauses" in {
            val config   = ScalafmtConfig(indent = ScalafmtConfig.Indent(extendSite = 2))
            val expected =
                """class Repository
                  |  extends Store
                  |  with Logging""".stripMargin
            assert(render(config)(template("extends Store", "with Logging")) == expected)
        }
    }

    "danglingParentheses" - {
        "callSite false attaches the closing paren to the last argument" in {
            val config = ScalafmtConfig(
                maxColumn = 37,
                danglingParentheses =
                    ScalafmtConfig.DanglingParentheses(callSite = false)
            )
            val expected =
                """send(
                  |  alpha,
                  |  beta,
                  |  gammaLongIdentifier)""".stripMargin
            assert(render(config)(callArgs("alpha", "beta", "gammaLongIdentifier")) == expected)
        }

        "defnSite false attaches the closing paren to the last parameter" in {
            val config = ScalafmtConfig(
                maxColumn = 30,
                danglingParentheses =
                    ScalafmtConfig.DanglingParentheses(defnSite = false)
            )
            val expected =
                """def process(
                  |    alpha: Int,
                  |    beta: String)""".stripMargin
            assert(render(config)(defParams(("alpha", "Int"), ("beta", "String"))) == expected)
        }
    }

    "align" - {
        "openParenCallSite aligns arguments under the opening paren" in {
            val config   = ScalafmtConfig(maxColumn = 37, align = ScalafmtConfig.Align(openParenCallSite = true))
            val expected =
                """send(alpha,
                  |     beta,
                  |     gammaLongIdentifier)""".stripMargin
            assert(render(config)(callArgs("alpha", "beta", "gammaLongIdentifier")) == expected)
        }

        "openParenDefnSite aligns parameters under the opening paren" in {
            val config   = ScalafmtConfig(maxColumn = 30, align = ScalafmtConfig.Align(openParenDefnSite = true))
            val expected =
                """def process(alpha: Int,
                  |            beta: String)""".stripMargin
            assert(render(config)(defParams(("alpha", "Int"), ("beta", "String"))) == expected)
        }
    }

    "binPack" - {
        val args = Seq("alpha", "beta", "gammaLongIdentifier", "delta")

        "Never keeps one argument per line" in {
            val config   = ScalafmtConfig(maxColumn = 30)
            val expected =
                """send(
                  |  alpha,
                  |  beta,
                  |  gammaLongIdentifier,
                  |  delta
                  |)""".stripMargin
            assert(render(config)(callArgs(args*)) == expected)
        }

        "Always packs arguments onto lines as the budget allows" in {
            val config = ScalafmtConfig(
                maxColumn = 30,
                binPack =
                    ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
            )
            val expected =
                """send(alpha, beta,
                  |     gammaLongIdentifier,
                  |     delta)""".stripMargin
            assert(render(config)(callArgs(args*)) == expected)
        }

        "Oneline packs single-line arguments like Always" in {
            // Oneline only isolates multiline arguments, which this fixture does not model.
            val always = ScalafmtConfig(
                maxColumn = 30,
                binPack =
                    ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
            )
            val oneline = ScalafmtConfig(
                maxColumn = 30,
                binPack =
                    ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Oneline)
            )
            assert(render(oneline)(callArgs(args*)) == render(always)(callArgs(args*)))
        }
    }

    "printer agreement" - {
        "optimal and greedy agree on every fixture and configuration of the matrix" in {
            val fixtures: Seq[(String, ScalafmtConfig, Doc < Env[ScalafmtConfig])] = Seq(
                ("call default", ScalafmtConfig.default, callArgs("alpha", "beta", "gammaLongIdentifier")),
                ("call broken", ScalafmtConfig(maxColumn = 37), callArgs("alpha", "beta", "gammaLongIdentifier")),
                (
                    "call indent 4",
                    ScalafmtConfig(
                        maxColumn = 37,
                        indent =
                            ScalafmtConfig.Indent(callSite = 4)
                    ),
                    callArgs("alpha", "beta", "gammaLongIdentifier")
                ),
                (
                    "call closed",
                    ScalafmtConfig(
                        maxColumn = 37,
                        danglingParentheses =
                            ScalafmtConfig.DanglingParentheses(callSite = false)
                    ),
                    callArgs("alpha", "beta", "gammaLongIdentifier")
                ),
                (
                    "call aligned",
                    ScalafmtConfig(
                        maxColumn = 37,
                        align =
                            ScalafmtConfig.Align(openParenCallSite = true)
                    ),
                    callArgs("alpha", "beta", "gammaLongIdentifier")
                ),
                (
                    "call packed",
                    ScalafmtConfig(
                        maxColumn = 30,
                        binPack =
                            ScalafmtConfig.BinPack(callSite = ScalafmtConfig.BinPack.Site.Always)
                    ),
                    callArgs("alpha", "beta", "gammaLongIdentifier", "delta")
                ),
                ("defn default", ScalafmtConfig(maxColumn = 30), defParams(("alpha", "Int"), ("beta", "String"))),
                (
                    "defn closed",
                    ScalafmtConfig(
                        maxColumn = 30,
                        danglingParentheses =
                            ScalafmtConfig.DanglingParentheses(defnSite = false)
                    ),
                    defParams(("alpha", "Int"), ("beta", "String"))
                ),
                (
                    "defn aligned",
                    ScalafmtConfig(
                        maxColumn = 30,
                        align =
                            ScalafmtConfig.Align(openParenDefnSite = true)
                    ),
                    defParams(("alpha", "Int"), ("beta", "String"))
                ),
                ("template", ScalafmtConfig.default, template("extends Store", "with Logging"))
            )

            for (name, config, fixture) <- fixtures do
                val doc     = materialize(config)(fixture)
                val greedy  = Printer.greedy.print(doc, config.maxColumn)
                val optimal = Printer.optimal.print(doc, config.maxColumn)
                assert(optimal == greedy, s"fixture $name: optimal differs from greedy")
            end for
        }
    }

end ScalafmtConfigTest
