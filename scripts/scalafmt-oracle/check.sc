//> using scala 3.7.2
//> using jvm 17
//> using file StatParser.scala

// Spot-checks the harvested oracle corpus by re-formatting every case's input
// with the scalafmt checkout the corpus was harvested from, and comparing the
// result to the committed expected output. Invoked via
// scripts/scalafmt-oracle.sh check.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object check:

    private type Fixture = StatParser.Fixture

    // Dev version the checkout's own build reports to the CLI. Only used to
    // satisfy the CLI's version check; formatting comes from the checkout code.
    private val cliVersion = "3.8.1-SNAPSHOT"

    private def runSbt(scalafmtDir: Path, command: String): Int =
        val p = new ProcessBuilder(
            List("sbt", "--no-colors", "--error", command)*
        ).directory(scalafmtDir.toFile).redirectErrorStream(false).start()
        p.getInputStream.transferTo(System.out)
        val code = p.waitFor()
        if code != 0 then System.err.println(s"sbt failed (exit $code): $command")
        code
    end runSbt

    def run(scalafmtDir: Path, families: List[String]): Unit =
        val resources = scalafmtDir.resolve("scalafmt-tests/src/test/resources")
        val fixtures  = mutable.ListBuffer[Fixture]()
        for spec <- families do
            val (family, dir) =
                if spec.contains('=') then
                    val i = spec.indexOf('=')
                    (spec.take(i), Paths.get(spec.drop(i + 1)))
                else (spec, resources.resolve(spec))
            for path <- sortedStats(dir) do
                StatParser.parseStat(path, family).foreach(cases =>
                    fixtures ++= cases.map(_._2)
                )

        val groups = fixtures.toList.groupBy(f => (f.style, f.extraConfig)).toList
            .sortBy(_._1.toString)
        println(s"checking ${fixtures.size} cases in ${groups.size} config groups")

        var failures = List.empty[String]
        for (((style, extra), group), idx) <- groups.zipWithIndex do
            val tmp      = Files.createTempDirectory(s"kyo-oracle-$idx-")
            val conf     = tmp.resolve(".scalafmt.conf")
            val confText = s"version = $cliVersion\nrunner.dialect = scala213\n$style\n$extra\n"
            Files.write(conf, confText.getBytes(StandardCharsets.UTF_8))
            val names =
                for (c, i) <- group.zipWithIndex yield
                    val f = tmp.resolve(s"c$i.scala")
                    Files.write(f, c.original.getBytes(StandardCharsets.UTF_8))
                    f.toString
            val command = s"""cli/run -c ${conf.toString} ${names.mkString(" ")}"""
            val exit    = runSbt(scalafmtDir, command)
            if exit != 0 then
                failures ::= s"group $idx config [$confText] sbt exit $exit"
            else
                group.zipWithIndex.foreach { (c, i) =>
                    val obtained = new String(
                        Files.readAllBytes(tmp.resolve(s"c$i.scala")),
                        StandardCharsets.UTF_8
                    )
                    if obtained != c.expected then
                        failures ::= s"${c.id}: output differs\n--- expected ---\n${c.expected}\n--- obtained ---\n$obtained"
                }
            end if
        end for

        failures.reverse.foreach(println)
        println(
            if failures.isEmpty then s"OK: ${fixtures.size} cases reproduce scalafmt output"
            else s"FAILED: ${failures.size} of ${fixtures.size} cases differ"
        )
        if failures.nonEmpty then sys.exit(1)
    end run

    private def sortedStats(dir: Path): List[Path] =
        if !Files.isDirectory(dir) then sys.error(s"unknown family dir: $dir")
        Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".stat"))
            .toList.sortBy(_.toString)
    end sortedStats

end check

def runCheck(args: Seq[String]): Unit =
    args.toList match
        case scalafmtDir :: families if families.nonEmpty =>
            check.run(Paths.get(scalafmtDir), families)
        case _ =>
            sys.error("usage: check.sc <scalafmtDir> <family>...")
    end match
end runCheck

runCheck(args)
