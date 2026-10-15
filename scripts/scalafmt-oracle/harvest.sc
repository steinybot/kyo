//> using scala 3.7.2
//> using jvm 17
//> using file StatParser.scala

// Harvests scalafmt's own test corpus into the committed fixture object used by
// kyo-print's oracle tests. Not part of the build; invoked via
// scripts/scalafmt-oracle.sh.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object harvest:

    private type Fixture = StatParser.Fixture

    /** Length-prefixed hex payload: four big-endian Int lengths, then the UTF-8
      * bytes of style, extraConfig, original, expected in order.
      */
    def encode(f: Fixture): String =
        val fields = List(f.style, f.extraConfig, f.original, f.expected)
        val bytes  = fields.map(_.getBytes(StandardCharsets.UTF_8))
        val buf    = mutable.ArrayBuffer[Byte]()
        for len <- bytes.map(_.length) do
            buf += ((len >>> 24) & 0xff).toByte
            buf += ((len >>> 16) & 0xff).toByte
            buf += ((len >>> 8) & 0xff).toByte
            buf += (len & 0xff).toByte
        end for
        for field <- bytes; b <- field do buf += b
        buf.map(b => f"${b & 0xff}%02x").mkString
    end encode

    private def wrapHex(hex: String, width: Int = 96): String =
        hex.grouped(width).mkString("\"\"\"\n", "\"\"\" + \"\"\"\n", "\"\"\"")

    private def escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    def generate(scalafmtDir: Path, outFile: Path, families: List[String]): String =
        val resources = scalafmtDir.resolve("scalafmt-tests/src/test/resources")
        val sha       = git(scalafmtDir, "rev-parse", "HEAD")
        val describe  = git(scalafmtDir, "describe", "--tags", "--always")

        val fixtures = mutable.ListBuffer[Fixture]()
        for spec <- families do
            val (family, dir) =
                if spec.contains('=') then
                    val i   = spec.indexOf('=')
                    (spec.take(i), Paths.get(spec.drop(i + 1)))
                else (spec, resources.resolve(spec))
            if !Files.isDirectory(dir) then
                sys.error(s"unknown family dir: $dir (harvest docs/ via scripts/scalafmt-oracle.sh docs first?)")
            val paths = Files.list(dir).iterator().asScala
                .filter(_.toString.endsWith(".stat")).toList.sortBy(_.toString)
            for path <- paths do
                StatParser.parseStat(path, family) match
                    case None => println(s"skipping dev-tree file $path")
                    case Some(cases) => fixtures ++= cases.map(_._2)
        end for
        val sorted = fixtures.toList.sortBy(_.id)

        val sb = new StringBuilder()
        sb ++= "package kyo\n\n"
        sb ++= "/** Committed scalafmt oracle corpus for the parity tests of this module.\n"
        sb ++=
            """  * GENERATED FILE. Do not edit by hand.
  *
  * Regenerate with scripts/scalafmt-oracle.sh harvest from a scalafmt
  * checkout; the revision below records the exact tree harvested, and
  * scripts/scalafmt-oracle.sh check re-verifies the corpus against that
  * checkout.
  *
"""
        sb ++= s"""  * Source: scalafmt $describe ($sha), families: ${families.mkString(", ")}.
  * Cases are harvested verbatim from scalafmt's own committed expected outputs;
  * ids are <family>/<file>:<case name> as upstream names them.
  */
"""
        sb ++= "import java.nio.charset.StandardCharsets\n\n"
        sb ++= "object ScalafmtOracle:\n\n"
        sb ++=
            """    /** One oracle case: the config text scalafmt's suite applies, and the
      * input/output pair its CI commits for it. `extraConfig` is the per-case
      * `===` block, empty when the case has none.
      */
    final case class Case(
        id: String,
        family: String,
        style: String,
        extraConfig: String,
        original: String,
        expected: String
    ) derives CanEqual

    /** Revision of the scalafmt checkout this corpus was harvested from. */
    val sourceRevision: String = "$sha"

    private def decodeBytes(hex: String): Array[Byte] =
        var digits = 0
        var i      = 0
        while i < hex.length do
            if !hex.charAt(i).isWhitespace then digits += 1
            i += 1
        end while
        val out = new Array[Byte](digits / 2)
        var o   = 0
        var hi  = -1
        i = 0
        while i < hex.length do
            val c = hex.charAt(i)
            if !c.isWhitespace then
                val v = if c <= '9' then c - '0' else c - 'a' + 10
                if hi < 0 then hi = v
                else
                    out(o) = ((hi << 4) | v).toByte
                    o += 1
                    hi = -1
                end if
            end if
            i += 1
        end while
        out
    end decodeBytes

    /** Decodes a hex payload to text. Whitespace is skipped so payloads wrap. */
    def decode(hex: String): String = new String(decodeBytes(hex), StandardCharsets.UTF_8)

    private def fields(payload: String): List[String] =
        val bytes = decodeBytes(payload)
        val lens  = (0 until 4).toList.map(k =>
            ((bytes(4 * k) & 0xff) << 24) | ((bytes(4 * k + 1) & 0xff) << 16) |
                ((bytes(4 * k + 2) & 0xff) << 8) | (bytes(4 * k + 3) & 0xff)
        )
        var off = 16
        for len <- lens yield
            val s = new String(bytes, off, len, StandardCharsets.UTF_8)
            off += len
            s
        end for
    end fields

    private def entry(id: String, family: String, payload: String): Case =
        fields(payload) match
            case style :: extraConfig :: original :: expected :: Nil =>
                Case(id, family, style, extraConfig, original, expected)
            case other => sys.error(s"corrupt payload for $id: $other")
    end entry

    val cases: Chunk[Case] = Chunk(
"""
        for (f, i) <- sorted.zipWithIndex do
            if i > 0 then sb ++= ",\n"
            sb ++= s"        entry(\n"
            sb ++= s"            \"${escape(f.id)}\",\n"
            sb ++= s"            \"${escape(f.family)}\",\n"
            sb ++= s"            ${wrapHex(encode(f))}\n"
            sb ++= s"        )"
        sb ++= "\n    )\n\nend ScalafmtOracle\n"
        val result = sb.toString
        Files.createDirectories(outFile.getParent)
        Files.write(outFile, result.getBytes(StandardCharsets.UTF_8))
        result
    end generate

    private def git(dir: Path, args: String*): String =
        val p = new ProcessBuilder(("git" +: "-C" +: dir.toString +: args.toList)*)
            .redirectErrorStream(true).start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        p.waitFor()
        if p.exitValue() != 0 then sys.error(s"git ${args.mkString(" ")} failed: $out")
        out
    end git

end harvest

def runHarvest(args: Seq[String]): Unit =
    args.toList match
        case "stat" :: scalafmtDir :: outFile :: families if families.nonEmpty =>
            val out = harvest.generate(
                Paths.get(scalafmtDir),
                Paths.get(outFile),
                families
            )
            println(s"wrote ${out.length} chars, ")
        case _ =>
            sys.error("usage: harvest.sc stat <scalafmtDir> <outFile> <family>...")
    end match
end runHarvest

runHarvest(args)
