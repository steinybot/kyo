package kyo.ffi.codegen.emitters

import EmitterFixtures.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.ffi.codegen.model.*

class ReachabilityMetadataEmitterTest extends kyo.test.Test[Any]:

    "registers the generated impl for reflective instantiation" in {
        val spec = mkTrait("Sockets", "kyo_net", List(mkMethod("noop", "noop", Nil, ReturnShape.Void)))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""type": "kyo.example.SocketsImpl""""))
        assert(json.contains(""""name": "<init>""""))
        assert(json.contains(""""reflection""""))
    }

    "maps primitive and pointer parameters to foreign tokens with errno capture" in {
        val spec = mkTrait(
            "Sockets",
            "kyo_net",
            List(
                mkMethod(
                    "recv",
                    "recv",
                    List(
                        ParamSpec("fd", TypeRef.IntT),
                        ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)),
                        ParamSpec("len", TypeRef.IntT)
                    ),
                    ReturnShape.Primitive(TypeRef.LongT)
                )
            )
        )
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""returnType": "jlong""""))
        assert(json.contains(""""parameterTypes": ["jint", "void*", "jint"]"""))
        assert(json.contains(""""captureCallState": true"""))
    }

    "emits the void token for a void return" in {
        val spec =
            mkTrait("Sockets", "kyo_net", List(mkMethod("close", "close", List(ParamSpec("fd", TypeRef.IntT)), ReturnShape.Void)))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""returnType": "void""""))
        assert(json.contains(""""parameterTypes": ["jint"]"""))
    }

    "a non-blocking array method adds the critical option" in {
        val spec = mkTrait(
            "Sockets",
            "kyo_net",
            List(
                mkMethod(
                    "writeAll",
                    "write_all",
                    List(ParamSpec("buf", TypeRef.ArrayT(TypeRef.ByteT))),
                    ReturnShape.Primitive(TypeRef.IntT)
                )
            )
        )
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""critical": { "allowHeapAccess": true }"""))
    }

    "a zero-parameter primitive method emits an empty parameter list and both sections" in {
        val spec = mkTrait("Sockets", "kyo_net", List(mkMethod("getpid", "getpid", Nil, ReturnShape.Primitive(TypeRef.IntT))))
        val json = ReachabilityMetadataEmitter.emit(spec)
        assert(json.contains(""""foreign""""))
        assert(json.contains(""""downcalls""""))
        assert(json.contains(""""returnType": "jint", "parameterTypes": []"""))
    }

    // -------------------------------------------------------------------------
    // Byte-exact goldens
    //
    // The contains-assertions above pin individual fields; the goldens below pin the FULL manifest
    // layout (section order, indentation, comma placement, upcall dedup) against
    // src/test/resources/golden/reachability/<key>.txt. Capture with -Dupdate-golden=true, verify
    // byte-identical without it, same workflow as EmitterGoldenTest.
    // -------------------------------------------------------------------------

    private val goldenRoot: Path =
        val cwd    = Paths.get("").toAbsolutePath
        val marker = Paths.get("kyo-ffi", "codegen")
        val base   =
            if cwd.endsWith(marker) then cwd
            else cwd.resolve(marker)
        base.resolve("src/test/resources/golden/reachability")
    end goldenRoot

    private val updateGolden: Boolean =
        java.lang.System.getProperty("update-golden") == "true"

    private def checkOrUpdate(key: String, actual: String)(using kyo.test.AssertScope): Unit =
        val file = goldenRoot.resolve(s"$key.txt")
        if updateGolden then
            Files.createDirectories(file.getParent)
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8))
            succeed
        else if !Files.exists(file) then
            fail(s"golden file $file missing, run the test with -Dupdate-golden=true to capture the current output.")
        else
            val expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            if expected != actual then
                val firstDiff = expected.linesIterator.zip(actual.linesIterator).zipWithIndex.find { case ((e, a), _) => e != a }
                val where     = firstDiff match
                    case Some(((e, a), idx)) => s"line ${idx + 1}:\n  expected: $e\n  actual:   $a"
                    case None                => s"lengths differ: expected=${expected.length}, actual=${actual.length}"
                fail(s"golden diff for reachability/$key:\n$where")
            else
                assert(expected == actual)
            end if
        end if
    end checkOrUpdate

    /** Single trait with a callback param (upcalls section), a void method, and a non-blocking array method (critical option). */
    private val goldenSpec = mkTrait(
        "Meta",
        "kyo_meta",
        List(
            mkMethod(
                "onEvent",
                "on_event",
                List(
                    ParamSpec("cb", TypeRef.FnPtrT(List(TypeRef.IntT), TypeRef.UnitT)),
                    ParamSpec("data", TypeRef.IntT)
                ),
                ReturnShape.Void,
                kind = CallbackKind.Transient
            ),
            mkMethod(
                "writeAll",
                "write_all",
                List(ParamSpec("buf", TypeRef.ArrayT(TypeRef.ByteT))),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        )
    )

    /** Two traits whose callback params share one C signature: emitModule must deduplicate the upcall and merge both reflection
      * entries and all downcalls into one manifest.
      */
    private val goldenSecondSpec = mkTrait(
        "MetaOther",
        "kyo_meta2",
        List(
            mkMethod(
                "watch",
                "watch",
                List(ParamSpec("cb", TypeRef.FnPtrT(List(TypeRef.IntT), TypeRef.UnitT))),
                ReturnShape.Void,
                kind = CallbackKind.Transient
            ),
            mkMethod("getpid", "getpid", Nil, ReturnShape.Primitive(TypeRef.IntT))
        )
    )

    "golden single trait" in {
        checkOrUpdate("single", ReachabilityMetadataEmitter.emit(goldenSpec))
    }

    "golden merged module deduplicates upcalls" in {
        checkOrUpdate("module", ReachabilityMetadataEmitter.emitModule(Seq(goldenSpec, goldenSecondSpec)))
    }

end ReachabilityMetadataEmitterTest
