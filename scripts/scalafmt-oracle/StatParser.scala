// Shared .stat parser used by harvest.sc and check.sc via `//> using file`.
// Mirrors scalafmt's HasTests.parseDiffTests so both the corpus generator and
// the spot-checker see exactly the cases upstream's CI runs. Keep this file
// free of top-level statements: scala-cli hoists it as plain definitions.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.matching.Regex

object StatParser:

    final case class Fixture(
        id: String,
        family: String,
        style: String,
        extraConfig: String,
        original: String,
        expected: String
    )

    /** Mirrors the ONLY/SKIP prefix handling of scalafmt's HasTests. */
    private def stripMarker(name: String): (String, Option[String]) =
        val trimmed = name.strip()
        if trimmed.startsWith("ONLY ") then (trimmed.drop(5), Some("ONLY"))
        else if trimmed.startsWith("SKIP ") then (trimmed.drop(5), Some("SKIP"))
        else if trimmed == "ONLY" || trimmed == "SKIP" then ("", Some(trimmed))
        else (name, None)
    end stripMarker

    /** Parses one .stat file the way scalafmt's HasTests.parseDiffTests does.
      * Returns None when the file is a dev-tree artifact (module-level ONLY)
      * that upstream CI would reject.
      */
    def parseStat(path: Path, family: String): Option[List[(String, Fixture)]] =
        val content    = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val sep        = if content.contains("\r\n") then "\r\n" else "\n"
        val splitIdx   = content.indexOf(s"${sep}<<< ")
        val firstIdx   =
            if content.startsWith("<<< ") then 0
            else if splitIdx >= 0 then splitIdx + sep.length
            else -1
        if firstIdx < 0 then return None

        val head  = content.substring(0, firstIdx)
        val tail  = content.substring(firstIdx)
        val style = head.trim
        if style.startsWith("ONLY") then return None

        val tailChunks = tail.split(s"(?:^|$sep)<<< ").toList.filter(_.nonEmpty)

        val regex =
            s"(?s)(.+?)$sep(?:(.+?)$sep===$sep)?(.+?)$sep>>>(?: +(.+?))?$sep(.*)".r

        val out = mutable.ListBuffer[(String, Fixture)]()
        for chunk <- tailChunks do
            val m = regex.findFirstMatchIn(chunk).getOrElse(
                sys.error(s"invalid test block in $path:\n$chunk")
            )
            val rawName = m.group(1)
            val (name, marker) = stripMarker(rawName)
            marker match
                case Some("SKIP") => // dropped: upstream would not run it
                case _ =>
                    val extra = Option(m.group(2)).getOrElse("")
                    out += ((name, Fixture(
                        id = s"$family/${path.getFileName}:$name",
                        family = family,
                        style = style,
                        extraConfig = extra,
                        original = m.group(3),
                        expected = m.group(5).trim + "\n"
                    )))
        end for
        Some(out.toList)
    end parseStat

end StatParser
