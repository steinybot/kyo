//> using scala 3.7.2
//> using jvm 17
//> using file StatParser.scala

// Extracts the ```scala mdoc:scalafmt example blocks from scalafmt's
// docs/configuration.md, formats each program with the scalafmt checkout, and
// writes the results as a synthetic .stat corpus at
// scripts/scalafmt-oracle/docs/configuration.stat. Invoked via
// scripts/scalafmt-oracle.sh docs.
//
// The example blocks carry only the config diff for their example; the base is
// the mdoc modifier's own base (scalafmt-docs ScalafmtModifier: default preset,
// maxColumn 40, scala213 dialect; sbt variant for programs containing
// `package`). That base is baked into each generated stat head so the corpus is
// self-describing and the harvest/check flow needs no special cases.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

object docs:

    private val cliVersion = "3.8.1-SNAPSHOT"

    private case class Block(section: String, idx: Int, config: String, program: String):
        def id: String = s"$section-$idx"

    /** Splits configuration.md into (heading, mdoc:scalafmt block) pairs. */
    private def parseMarkdown(md: String): List[Block] =
        val lines   = md.split("\n", -1).toList
        val blocks  = mutable.ListBuffer[Block]()
        var section = ""
        var inFence = false
        var fence   = mutable.ListBuffer[String]()
        var count   = 0
        for line <- lines do
            if inFence then
                if line.trim == "```" then
                    val body = fence.toList
                    fence = mutable.ListBuffer[String]()
                    inFence = false
                    body.indexOf("---") match
                        case -1 => () // not an example: config-only or malformed
                        case sep =>
                            val config  = body.take(sep).mkString("\n").trim
                            val program = body.drop(sep + 1).mkString("\n")
                            count += 1
                            blocks += Block(section, count, config, program)
                    end match
                else fence += line
            else if line.startsWith("```scala mdoc:scalafmt") then inFence = true
            else if line.startsWith("## ") then
                section = line.drop(3).trim
                count = 0
            else if line.startsWith("### ") then
                section = line.drop(4).trim
                count = 0
        end for
        blocks.toList
    end parseMarkdown

    private def runSbt(scalafmtDir: Path, command: String): Int =
        val p = new ProcessBuilder(
            List("sbt", "--no-colors", "--error", command)*
        ).directory(scalafmtDir.toFile).redirectErrorStream(false).start()
        p.getInputStream.transferTo(System.out)
        val code = p.waitFor()
        if code != 0 then System.err.println(s"sbt failed (exit $code): $command")
        code
    end runSbt

    private def baseHead(program: String): String =
        if program.contains("package") then "runner.dialect = sbt1\nmaxColumn = 40"
        else "runner.dialect = scala213\nmaxColumn = 40"
    end baseHead

    def run(scalafmtDir: Path, outFile: Path): Unit =
        val md = new String(
            Files.readAllBytes(scalafmtDir.resolve("docs/configuration.md")),
            StandardCharsets.UTF_8
        )
        val blocks = parseMarkdown(md)
        println(s"extracted ${blocks.size} mdoc:scalafmt example blocks")

        val tmp   = Files.createTempDirectory("kyo-oracle-docs-")
        val heads = blocks.map(b => (baseHead(b.program), b.config)).distinct.sorted
        val fileOf = mutable.Map[Block, String]()

        for ((base, config), gi) <- heads.zipWithIndex do
            val group = blocks.filter(b => baseHead(b.program) == base && b.config == config)
            val conf  = tmp.resolve(s"g$gi.scalafmt.conf")
            val text  = s"version = $cliVersion\n$base\n$config\n"
            Files.write(conf, text.getBytes(StandardCharsets.UTF_8))
            val names =
                for (b, i) <- group.zipWithIndex yield
                    val f = tmp.resolve(s"g${gi}_$i.scala")
                    Files.write(f, b.program.getBytes(StandardCharsets.UTF_8))
                    fileOf(b) = f.toString
                    f.toString
            val exit = runSbt(scalafmtDir, s"""cli/run -c ${conf.toString} ${names.mkString(" ")}""")
            if exit != 0 then sys.error(s"scalafmt failed for docs group [$text]")
        end for

        Files.createDirectories(outFile)
        for b <- blocks do
            val formatted = new String(
                Files.readAllBytes(Paths.get(fileOf(b))),
                StandardCharsets.UTF_8
            )
            val head = (baseHead(b.program) + "\n" + b.config).trim
            val body = new mutable.StringBuilder()
            body ++= head
            body ++= "\n<<< example\n"
            body ++= b.program.stripTrailing()
            body ++= "\n>>>\n"
            body ++= formatted.trim
            body ++= "\n"
            val slug = b.section.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
            val file = outFile.resolve(s"$slug-${b.idx}.stat")
            Files.write(file, body.toString.getBytes(StandardCharsets.UTF_8))
        end for
        println(s"wrote ${blocks.size} docs case files to $outFile")
    end run

end docs

def runDocs(args: Seq[String]): Unit =
    args.toList match
        case scalafmtDir :: outFile :: Nil =>
            docs.run(Paths.get(scalafmtDir), Paths.get(outFile))
        case _ =>
            sys.error("usage: docs.sc <scalafmtDir> <outFile>")
    end match
end runDocs

runDocs(args)
