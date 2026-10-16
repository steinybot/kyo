package kyo

/** Locks the integrity of the committed scalafmt oracle corpus.
  *
  * The corpus is generated from scalafmt's own test resources and documentation
  * by `scripts/scalafmt-oracle.sh`; these tests do not run the formatter, they
  * verify that what was harvested and embedded is well-formed, traceable, and
  * byte-stable, so that the parity tests built on it compare against exactly
  * what scalafmt's suite commits.
  */
class ScalafmtOracleTest extends kyo.test.Test[Any]:

    "corpus" - {

        "contains fixtures from every harvested family" in {
            val families = ScalafmtOracle.cases.map(_.family).toSet
            assert(families == Set("binPack", "optIn", "docs", "classic"))
            assert(ScalafmtOracle.cases.length >= 300)
        }

        "ids are unique and traceable to a corpus file and case name" in {
            val ids = ScalafmtOracle.cases.map(_.id)
            assert(ids.distinct == ids)
            for c <- ScalafmtOracle.cases do
                assert(c.id.contains('/'), s"id not traceable: ${c.id}")
            end for
        }

        "no case carries upstream ONLY or SKIP markers" in {
            for c <- ScalafmtOracle.cases do
                assert(!c.id.contains("ONLY "), c.id)
                assert(!c.id.contains("SKIP "), c.id)
            end for
        }

        "every case has source, config, input, and newline-terminated output" in {
            for c <- ScalafmtOracle.cases do
                assert(c.style.nonEmpty, c.id)
                assert(c.original.nonEmpty, c.id)
                assert(c.expected.nonEmpty, c.id)
                assert(c.expected.endsWith("\n"), c.id)
            end for
        }
    }

    "embedded payload decoding" - {

        "hex decode is exact for a known string" in {
            // "send(alpha, beta)" in hex.
            val hex = "73656e6428616c7068612c206265746129"
            assert(ScalafmtOracle.decode(hex) == "send(alpha, beta)")
        }

        "known fixture round-trips byte-for-byte" in {
            val c = ScalafmtOracle.cases
                .find(_.id == "binPack/LambdaParameterFalse.stat:weird line break")
                .getOrElse(fail("fixture missing from corpus"))
            assert(c.original ==
                """class A {{{{
                  |        def streamRequestEntity(creator: (Source[ParserOutput.RequestOutput,
                  |                                                 NotUsed]) => RequestEntity): R = 1
                  |}}}}""".stripMargin)
            assert(c.expected ==
                """class A {
                  |  {
                  |    {
                  |      {
                  |        def streamRequestEntity(
                  |            creator: (
                  |                Source[ParserOutput.RequestOutput, NotUsed]
                  |            ) => RequestEntity
                  |        ): R = 1
                  |      }
                  |    }
                  |  }
                  |}
                  |""".stripMargin)
        }
    }

end ScalafmtOracleTest
