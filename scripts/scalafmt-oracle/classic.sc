//> using scala 3.7.2
//> using jvm 17

// Generates the "classic" oracle corpus at scripts/scalafmt-oracle/classic/*.stat
// from the committed probe definitions in scripts/scalafmt-oracle/classic-probes.stat.
// Invoked via scripts/scalafmt-oracle.sh classic.
//
// The probes pin newlines.source=classic behaviour (the scalafmt default) for
// option combinations whose expected outputs scalafmt's own suite does not
// commit in a shape this module's Doc trees can express. Expected outputs are
// never hand-written: every case is formatted by the scalafmt checkout itself,
// exactly as the docs family is, so the corpus stays an oracle rather than a
// self-generated expectation.
//
// Probe file format (input-only stat):
//   <<< case name
//   config lines
//   ---
//   program
//
// The generated files carry the shared base (scala213 dialect, maxColumn 40) as
// the stat head and the per-case config as the `===` block, so harvest and
// check need no special cases.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

object classic:

    private val cliVersion = "3.8.1-SNAPSHOT"
    private val baseHead   = "runner.dialect = scala213\nmaxColumn = 40"

    private case class Probe(name: String, config: String, program: String):
        def slug: String = name.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")

    private def parseProbes(content: String): List[Probe] =
        content.split("(?m)^<<< ").toList.drop(1).map { chunk =>
            val lines = chunk.split("\n", -1).toList
            val sep   = lines.indexWhere(_ == "---")
            require(sep > 0, s"probe missing --- separator:\n$chunk")
            Probe(
                name = lines.head.trim,
                config = lines.slice(1, sep).mkString("\n").trim,
                program = lines.drop(sep + 1).mkString("\n").stripTrailing()
            )
        }
    end parseProbes

    private def runSbt(scalafmtDir: Path, command: String): Int =
        val p = new ProcessBuilder(
            List("sbt", "--no-colors", "--error", command)*
        ).directory(scalafmtDir.toFile).redirectErrorStream(false).start()
        p.getInputStream.transferTo(System.out)
        val code = p.waitFor()
        if code != 0 then System.err.println(s"sbt failed (exit $code): $command")
        code
    end runSbt

    def run(scalafmtDir: Path, probesFile: Path, outFile: Path): Unit =
        val probes = parseProbes(
            new String(Files.readAllBytes(probesFile), StandardCharsets.UTF_8)
        )
        println(s"parsed ${probes.size} probes from $probesFile")

        val tmp    = Files.createTempDirectory("kyo-oracle-classic-")
        val groups = probes.groupBy(_.config).toList
        val fileOf = scala.collection.mutable.Map[Probe, String]()
        for (((config, group), gi) <- groups.zipWithIndex) do
            val conf = tmp.resolve(s"g$gi.scalafmt.conf")
            Files.write(
                conf,
                s"version = $cliVersion\n$baseHead\n$config\n".getBytes(StandardCharsets.UTF_8)
            )
            val names =
                for (p, i) <- group.zipWithIndex yield
                    val f = tmp.resolve(s"g${gi}_$i.scala")
                    Files.write(f, p.program.getBytes(StandardCharsets.UTF_8))
                    fileOf(p) = f.toString
                    f.toString
            val exit = runSbt(
                scalafmtDir,
                s"""cli/run -c ${conf.toString} ${names.mkString(" ")}"""
            )
            if exit != 0 then sys.error(s"scalafmt failed for classic group [$config]")
        end for

        Files.createDirectories(outFile)
        for p <- probes do
            val formatted = new String(
                Files.readAllBytes(Paths.get(fileOf(p))),
                StandardCharsets.UTF_8
            )
            val body = new StringBuilder()
            body ++= baseHead
            if p.config.nonEmpty then body ++= "\n" + p.config
            body ++= "\n<<< example\n"
            body ++= p.program
            body ++= "\n>>>\n"
            body ++= formatted.trim
            body ++= "\n"
            Files.write(
                outFile.resolve(s"${p.slug}.stat"),
                body.toString.getBytes(StandardCharsets.UTF_8)
            )
        end for
        println(s"wrote ${probes.size} classic case files to $outFile")
    end run

end classic

def runClassic(args: Seq[String]): Unit =
    args.toList match
        case scalafmtDir :: probesFile :: outFile :: Nil =>
            classic.run(Paths.get(scalafmtDir), Paths.get(probesFile), Paths.get(outFile))
        case _ =>
            sys.error("usage: classic.sc <scalafmtDir> <probesFile> <outFile>")
    end match
end runClassic

runClassic(args)
