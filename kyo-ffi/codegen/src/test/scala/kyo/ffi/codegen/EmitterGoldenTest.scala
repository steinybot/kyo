package kyo.ffi.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.ffi.codegen.emitters.*
import kyo.ffi.codegen.emitters.EmitterFixtures.*
import kyo.ffi.codegen.model.*

/** Byte-exact snapshot test for the three platform emitters.
  *
  * Approach: a curated list of hand-written [[TraitSpec]] fixtures exercises the emission shapes that matter (primitive return, struct
  * return, borrowed string, borrowed buffer, multi-value, callbacks in all kinds and carrier positions, variadic, enum, handle (bare and
  * nullable), union (param and struct field), packed and nested structs, companion config (scratchSize / checkedBorrows), nativeBundled,
  * blocking+array, the struct bulk-write marker, and the Native headers-unavailable stub mode). For each fixture we run each emitter and
  * compare the output against a captured golden file under `src/test/resources/golden/<platform>/<fixture>.txt`.
  *
  * Workflow:
  *   - Pre-refactor capture: run the test with `-Dupdate-golden=true`. The test writes the current output to the golden file and passes.
  *   - Post-refactor verification: run without the flag. Any byte-level diff fails the test.
  *
  * Why not enumerate kyo-ffi-it bindings: kyo-ffi-it is a separate sbt project whose bindings only surface as TASTy after a full compile
  * cycle. The hand-written fixtures here cover the same emission paths with lower setup cost.
  *
  * Rationale for the hand-written corpus: every branch inside the three emitters routes through one of a small number of emission
  * primitives, param marshal by TypeRef, return-shape dispatch, struct field read/write, callback stub emission, errno capture. The
  * fixtures together touch every such primitive at least once.
  */
class EmitterGoldenTest extends kyo.test.Test[Any]:

    /** Resolve the golden root anchored at the kyo-ffi-codegen module directory, independent of the forked test JVM's working directory. We
      * probe upward from the current working dir until we find a directory that contains `src/test/resources`.
      */
    private val goldenRoot: Path =
        val cwd = Paths.get("").toAbsolutePath
        // The codegen module lives at kyo-ffi/codegen. The forked test JVM's cwd is that module dir; a repo-root cwd
        // resolves into it. If cwd already ends with kyo-ffi/codegen use it, otherwise resolve it as a child of cwd.
        val marker = Paths.get("kyo-ffi", "codegen")
        val base   =
            if cwd.endsWith(marker) then cwd
            else cwd.resolve(marker)
        base.resolve("src/test/resources/golden")
    end goldenRoot

    private val updateGolden: Boolean =
        java.lang.System.getProperty("update-golden") == "true"

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val fixturePoint = StructSpec(
        fqcn = "kyo.example.golden.Point",
        simpleName = "Point",
        fields = List(StructField("x", TypeRef.IntT), StructField("y", TypeRef.IntT)),
        packed = false
    )

    private val fixtureReadResult = StructSpec(
        fqcn = "kyo.example.golden.ReadResult",
        simpleName = "ReadResult",
        fields = List(
            StructField("bytesRead", TypeRef.IntT),
            StructField("eof", TypeRef.BooleanT)
        ),
        packed = false
    )

    /** Primitive-return: `int add(int, int)`. Covers the simplest emission path. */
    private val specPrimitive: TraitSpec = mkTrait(
        "PrimitiveBindings",
        "kyo_prim",
        List(
            mkMethod(
                "add",
                "add",
                List(ParamSpec("a", TypeRef.IntT), ParamSpec("b", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        )
    )

    /** Void return: exercises the special void code path. */
    private val specVoid: TraitSpec = mkTrait(
        "VoidBindings",
        "kyo_void",
        List(mkMethod("noop", "noop", Nil, ReturnShape.Void))
    )

    /** Struct return + struct param. Covers struct layout, field reads/writes, coalesced out-cell allocation. */
    private val specStruct: TraitSpec = mkTrait(
        "StructBindings",
        "kyo_struct",
        List(
            mkMethod(
                "move",
                "move",
                List(ParamSpec("p", TypeRef.StructT("kyo.example.golden.Point")), ParamSpec("dx", TypeRef.IntT)),
                ReturnShape.Struct(fixturePoint)
            )
        ),
        structs = List(fixturePoint)
    )

    /** Multi-value return: head primitive, tail out-params. */
    private val specMultiValue: TraitSpec = mkTrait(
        "MultiValueBindings",
        "kyo_mv",
        List(
            mkMethod(
                "readInto",
                "read_into",
                List(ParamSpec("fd", TypeRef.IntT), ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT))),
                ReturnShape.MultiValue(fixtureReadResult),
                blocking = true
            )
        ),
        structs = List(fixtureReadResult)
    )

    /** String param + borrowed-string return. Exercises UTF-8 marshalling + readCStringBounded. */
    private val specBorrowedString: TraitSpec = mkTrait(
        "BorrowedStringBindings",
        "kyo_borrowed_str",
        List(
            mkMethod(
                "getenvLike",
                "getenv",
                List(ParamSpec("name", TypeRef.StringT)),
                ReturnShape.BorrowedString(4096)
            )
        )
    )

    /** Borrowed buffer return. */
    private val specBorrowedBuffer: TraitSpec = mkTrait(
        "BorrowedBufferBindings",
        "kyo_borrowed_buf",
        List(
            mkMethod(
                "mallocChunk",
                "malloc_chunk",
                List(ParamSpec("n", TypeRef.LongT)),
                ReturnShape.BorrowedBuffer(TypeRef.ByteT, "n")
            )
        )
    )

    /** Outcome: plain primitive packed into an errno-aware carrier. */
    private val specWithError: TraitSpec = mkTrait(
        "WithErrorBindings",
        "kyo_we",
        List(
            mkMethod(
                "failable",
                "failable",
                List(ParamSpec("x", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT),
                withError = true
            )
        )
    )

    /** Buffer + blocking. */
    private val specBuffer: TraitSpec = mkTrait(
        "BufferBindings",
        "kyo_buf",
        List(
            mkMethod(
                "process",
                "process",
                List(ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)), ParamSpec("len", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT),
                blocking = true
            )
        )
    )

    /** Handle param + Handle return. */
    private val specHandle: TraitSpec = mkTrait(
        "HandleBindings",
        "kyo_handle",
        List(
            mkMethod(
                "open",
                "open",
                List(ParamSpec("path", TypeRef.StringT)),
                ReturnShape.HandleReturn("kyo.example.golden.MyHandle", nullable = false)
            ),
            mkMethod(
                "close",
                "close",
                List(ParamSpec("h", TypeRef.HandleT("kyo.example.golden.MyHandle"))),
                ReturnShape.Void
            )
        )
    )

    /** Blocking + Outcome: `long recv(int fd, Buffer[Byte] buf, int len)` annotated `@Ffi.blocking`.
      *
      * Exercises the `A < kyo.Async` signature plus the per-backend fiber-suspending body: a `kyo.Sync.defer` block
      * on JVM/Native and the koffi `.async` + `Promise` bridge on JS. An errno-aware `Outcome` return additionally covers
      * errno capture inside the async completion callback.
      */
    private val specBlocking: TraitSpec = mkTrait(
        "BlockingBindings",
        "kyo_blocking",
        List(
            mkMethod(
                "recv",
                "recv",
                List(
                    ParamSpec("fd", TypeRef.IntT),
                    ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)),
                    ParamSpec("len", TypeRef.IntT)
                ),
                ReturnShape.Primitive(TypeRef.LongT),
                blocking = true,
                withError = true
            )
        )
    )

    /** Array param (non-blocking, pin path). */
    private val specArray: TraitSpec = mkTrait(
        "ArrayBindings",
        "kyo_arr",
        List(
            mkMethod(
                "sum",
                "sum_ints",
                List(ParamSpec("xs", TypeRef.ArrayT(TypeRef.IntT)), ParamSpec("n", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        )
    )

    /** Transient callback param in a catalog shape (`Int => Unit` = `I_U`): JVM dispatches to `UpcallBridge.stubShape_I_U`,
      * Native brackets the call with `CallbackRegistry.pushTransient_I_U`/`pop`, JS wraps in the safe wrapper and passes the function
      * directly.
      */
    private val specCallbackTransient: TraitSpec = mkTrait(
        "CallbackTransientBindings",
        "kyo_cb_t",
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
            )
        )
    )

    /** Retained callback (`(Int, Int) => Int` = `II_I`) with an `Ffi.Guard`: JVM reads the arena from the guard and promotes spills,
      * Native claims a registry slot pinned on the guard, JS registers a koffi handle with a guard-close cleanup.
      */
    private val specCallbackRetained: TraitSpec = mkTrait(
        "CallbackRetainedBindings",
        "kyo_cb_r",
        List(
            mkMethod(
                "register",
                "register_handler",
                List(
                    ParamSpec("cb", TypeRef.FnPtrT(List(TypeRef.IntT, TypeRef.IntT), TypeRef.IntT)),
                    ParamSpec("guard", TypeRef.GuardT)
                ),
                ReturnShape.Void,
                kind = CallbackKind.Retained
            )
        )
    )

    /** Callback over a pointer (`Buffer[Byte] => Unit` = `P_U`): catalog shape on Native, but NOT jvm-safe (pointer param) so JVM takes
      * the generic `UpcallBridge.stub1` path instead of a specialised stub shape.
      */
    private val specCallbackBuffer: TraitSpec = mkTrait(
        "CallbackBufferBindings",
        "kyo_cb_buf",
        List(
            mkMethod(
                "onData",
                "on_data",
                List(
                    ParamSpec("cb", TypeRef.FnPtrT(List(TypeRef.BufferT(TypeRef.ByteT)), TypeRef.UnitT)),
                    ParamSpec("buf", TypeRef.BufferT(TypeRef.ByteT)),
                    ParamSpec("len", TypeRef.IntT)
                ),
                ReturnShape.Void,
                kind = CallbackKind.Transient
            )
        )
    )

    /** Variadic method (`printf`-style): JVM/JS marshal fixed args then delegate to the variadic marshaller, Native emits a runtime
      * stub because `@extern` cannot express varargs.
      */
    private val specVarargs: TraitSpec = mkTrait(
        "VarargsBindings",
        "kyo_varargs",
        List(
            mkMethod(
                "logf",
                "logf",
                List(ParamSpec("fmt", TypeRef.StringT)),
                ReturnShape.Primitive(TypeRef.IntT),
                hasVarargs = true
            )
        )
    )

    /** Enum param (with a Scala-keyword param name exercising `safeName` backticking) + enum return via companion `fromInt`. */
    private val specEnum: TraitSpec = mkTrait(
        "EnumBindings",
        "kyo_enum",
        List(
            mkMethod(
                "mix",
                "mix",
                List(
                    ParamSpec("type", TypeRef.EnumT("kyo.example.golden.Color")),
                    ParamSpec("amount", TypeRef.IntT)
                ),
                ReturnShape.EnumReturn("kyo.example.golden.Color")
            )
        ),
        enums = List(EnumSpec("kyo.example.golden.Color", "Color"))
    )

    /** Union param (`Int | Point`, primitive + struct variants): runtime type match writing the active variant. */
    private val specUnion: TraitSpec = mkTrait(
        "UnionBindings",
        "kyo_union",
        List(
            mkMethod(
                "setAttr",
                "set_attr",
                List(ParamSpec("u", TypeRef.UnionT(List(TypeRef.IntT, TypeRef.StructT("kyo.example.golden.Point"))))),
                ReturnShape.Void
            )
        ),
        structs = List(fixturePoint)
    )

    /** Packed struct param + return: JVM lays out with `withByteAlignment(1)`, Native uses raw `Ptr[Byte]` offset arithmetic, JS
      * registers via `koffi.pack`.
      */
    private val fixturePackedPoint = StructSpec(
        fqcn = "kyo.example.golden.PackedPoint",
        simpleName = "PackedPoint",
        fields = List(
            StructField("x", TypeRef.IntT),
            StructField("b", TypeRef.ByteT),
            StructField("y", TypeRef.LongT)
        ),
        packed = true
    )

    private val specPacked: TraitSpec = mkTrait(
        "PackedBindings",
        "kyo_packed",
        List(
            mkMethod(
                "roundTrip",
                "packed_round_trip",
                List(ParamSpec("p", TypeRef.StructT("kyo.example.golden.PackedPoint"))),
                ReturnShape.Struct(fixturePackedPoint)
            )
        ),
        structs = List(fixturePackedPoint)
    )

    /** Nested struct (`Outer.inner: Point`) plus a union field (`Int | Long`): write recursion + first-variant read, union layout /
      * carrier / koffi union registration.
      */
    private val fixtureOuter = StructSpec(
        fqcn = "kyo.example.golden.Outer",
        simpleName = "Outer",
        fields = List(
            StructField("inner", TypeRef.StructT("kyo.example.golden.Point")),
            StructField("tag", TypeRef.IntT),
            StructField("extra", TypeRef.UnionT(List(TypeRef.IntT, TypeRef.LongT)))
        ),
        packed = false
    )

    private val specNested: TraitSpec = mkTrait(
        "NestedBindings",
        "kyo_nested",
        List(
            mkMethod(
                "retag",
                "retag",
                List(ParamSpec("o", TypeRef.StructT("kyo.example.golden.Outer"))),
                ReturnShape.Struct(fixtureOuter)
            )
        ),
        structs = List(fixtureOuter, fixturePoint)
    )

    /** Struct with String, enum, and Buffer fields (buffer extent from the sole Int/Long sibling `len`), written as a param and read as
      * a return: string field encode/decode, enum field value/fromInt, borrowed-pointer field wrap.
      */
    private val fixtureBlob = StructSpec(
        fqcn = "kyo.example.golden.Blob",
        simpleName = "Blob",
        fields = List(
            StructField("name", TypeRef.StringT),
            StructField("level", TypeRef.EnumT("kyo.example.golden.Color")),
            StructField("data", TypeRef.BufferT(TypeRef.ByteT)),
            StructField("len", TypeRef.IntT)
        ),
        packed = false
    )

    private val specStructRich: TraitSpec = mkTrait(
        "RichStructBindings",
        "kyo_rich",
        List(
            mkMethod(
                "poke",
                "poke",
                List(ParamSpec("b", TypeRef.StructT("kyo.example.golden.Blob"))),
                ReturnShape.Void
            ),
            mkMethod(
                "peek",
                "peek",
                List(ParamSpec("hint", TypeRef.IntT)),
                ReturnShape.Struct(fixtureBlob)
            )
        ),
        structs = List(fixtureBlob),
        enums = List(EnumSpec("kyo.example.golden.Color", "Color"))
    )

    /** FnPtr struct field with no top-level callback param: JVM emits a transient `cbArena`, Native routes through the
      * `I_U` catalog trampoline with push/pop teardown, JS registers a koffi proto handle in the companion.
      */
    private val fixtureWatcher = StructSpec(
        fqcn = "kyo.example.golden.Watcher",
        simpleName = "Watcher",
        fields = List(
            StructField("cb", TypeRef.FnPtrT(List(TypeRef.IntT), TypeRef.UnitT)),
            StructField("fd", TypeRef.IntT)
        ),
        packed = false
    )

    private val specStructFnPtr: TraitSpec = mkTrait(
        "WatcherBindings",
        "kyo_watch",
        List(
            mkMethod(
                "arm",
                "arm",
                List(ParamSpec("w", TypeRef.StructT("kyo.example.golden.Watcher"))),
                ReturnShape.Void
            )
        ),
        structs = List(fixtureWatcher)
    )

    /** Nullable Handle return (`Maybe[Handle[A]]`): NULL maps to Absent / `kyo.Absent`, non-null to Present. */
    private val specNullableHandle: TraitSpec = mkTrait(
        "NullableHandleBindings",
        "kyo_handle_n",
        List(
            mkMethod(
                "find",
                "find_opt",
                List(ParamSpec("path", TypeRef.StringT)),
                ReturnShape.HandleReturn("kyo.example.golden.MyHandle", nullable = true)
            )
        )
    )

    /** nativeBundled binding: the Native emitter omits `@link` (C is compiled into the binary), JVM/JS are unchanged. */
    private val specNativeBundled: TraitSpec = mkTrait(
        "BundledBindings",
        "kyo_bundled",
        List(
            mkMethod(
                "hello",
                "bundled_hello",
                Nil,
                ReturnShape.Primitive(TypeRef.IntT)
            )
        ),
        nativeBundled = true
    )

    /** Companion `Ffi.Config` with `scratchSize = Some(8192)` and `checkedBorrows = true`: JVM sizes the per-thread scratch block from
      * the override and routes borrows through `wrapBorrowedChecked`, Native/JS also honor checkedBorrows.
      */
    private val specConfig: TraitSpec = mkTrait(
        "ConfigBindings",
        "kyo_cfg",
        List(
            mkMethod(
                "grab",
                "grab",
                List(ParamSpec("n", TypeRef.LongT)),
                ReturnShape.BorrowedBuffer(TypeRef.ByteT, "n")
            ),
            mkMethod(
                "peekBlob",
                "peek_blob",
                List(ParamSpec("hint", TypeRef.IntT)),
                ReturnShape.Struct(fixtureBlob)
            )
        ),
        structs = List(fixtureBlob),
        companion = Some(ConfigSpec(
            library = "kyo_cfg",
            symbolPrefix = "",
            symbols = Map.empty,
            packedStructs = Set.empty,
            scratchSize = Some(8192),
            checkedBorrows = true
        ))
    )

    /** Blocking + Array param: JVM copies the array into scratch, Native zone-copies, JS routes through the async bridge. */
    private val specBlockingArray: TraitSpec = mkTrait(
        "BlockingArrayBindings",
        "kyo_blk_arr",
        List(
            mkMethod(
                "sendAll",
                "send_all",
                List(ParamSpec("buf", TypeRef.ArrayT(TypeRef.ByteT)), ParamSpec("len", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT),
                blocking = true
            )
        )
    )

    /** Simple primitive-only struct at 160 bytes (>= the 128-byte default threshold): the JVM emitter prefixes the writes with the
      * `// __kyoStructBulkWrite:` marker.
      */
    private val fixtureWide = StructSpec(
        fqcn = "kyo.example.golden.Wide",
        simpleName = "Wide",
        fields = (1 to 40).map(i => StructField(s"f$i", TypeRef.IntT)).toList,
        packed = false
    )

    private val specBulkWrite: TraitSpec = mkTrait(
        "WideBindings",
        "kyo_wide",
        List(
            mkMethod(
                "echo",
                "echo_wide",
                List(ParamSpec("w", TypeRef.StructT("kyo.example.golden.Wide"))),
                ReturnShape.Void
            )
        ),
        structs = List(fixtureWide)
    )

    /** Headers-unavailable emission is Native-only and has its own golden platform dir below. */
    private val specNoHeaders: TraitSpec = mkTrait(
        "NoHeadersBindings",
        "kyo_noh",
        List(
            mkMethod(
                "connect",
                "connect",
                List(ParamSpec("fd", TypeRef.IntT)),
                ReturnShape.Primitive(TypeRef.IntT)
            )
        ),
        headers = Seq("sys/socket.h", "netdb.h")
    )

    private val allFixtures: List[(String, TraitSpec)] = List(
        "primitive"       -> specPrimitive,
        "void"            -> specVoid,
        "struct"          -> specStruct,
        "multivalue"      -> specMultiValue,
        "borrowed_string" -> specBorrowedString,
        "borrowed_buffer" -> specBorrowedBuffer,
        "with_error"      -> specWithError,
        "buffer"          -> specBuffer,
        "blocking"        -> specBlocking,
        "handle"          -> specHandle,
        "array"           -> specArray,
        "cb_transient"    -> specCallbackTransient,
        "cb_retained"     -> specCallbackRetained,
        "cb_buffer"       -> specCallbackBuffer,
        "varargs"         -> specVarargs,
        "enum"            -> specEnum,
        "union"           -> specUnion,
        "packed"          -> specPacked,
        "nested"          -> specNested,
        "struct_rich"     -> specStructRich,
        "struct_fnptr"    -> specStructFnPtr,
        "handle_nullable" -> specNullableHandle,
        "native_bundled"  -> specNativeBundled,
        "config"          -> specConfig,
        "blocking_array"  -> specBlockingArray,
        "bulk_write"      -> specBulkWrite
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    private def checkOrUpdate(platform: String, key: String, actual: String)(using kyo.test.AssertScope): Unit =
        val file = goldenRoot.resolve(platform).resolve(s"$key.txt")
        if updateGolden then
            Files.createDirectories(file.getParent)
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8))
            succeed
        else if !Files.exists(file) then
            fail(
                s"golden file $file missing, run the test with -Dupdate-golden=true to capture the current output."
            )
        else
            val expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            if expected != actual then
                val expectedLines = expected.linesIterator.toList
                val actualLines   = actual.linesIterator.toList
                val firstDiff     = expectedLines.zip(actualLines).zipWithIndex.find { case ((e, a), _) => e != a }
                val where         = firstDiff match
                    case Some(((e, a), idx)) => s"line ${idx + 1}:\n  expected: $e\n  actual:   $a"
                    case None                => s"lengths differ: expected=${expectedLines.size}, actual=${actualLines.size}"
                val excerpt = actualLines.take(10).mkString("\n")
                fail(
                    s"golden diff for $platform/$key:\n$where\n\nfirst 10 lines of actual output:\n$excerpt"
                )
            else
                // The emitted output matches the golden file; record an evaluated assertion so the leaf is not
                // flagged by the no-assertion check.
                assert(expected == actual)
            end if
        end if
    end checkOrUpdate

    "JvmEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = JvmEmitter.emit(spec)
                checkOrUpdate("jvm", key, out)
            }
        }
    }

    "NativeEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = NativeEmitter.emit(spec)
                checkOrUpdate("native", key, out)
            }
        }
    }

    "JsEmitter golden" - {
        allFixtures.foreach { case (key, spec) =>
            key in {
                val out = JsEmitter.emit(spec)
                checkOrUpdate("js", key, out)
            }
        }
    }

    /** Headers-unavailable emission is a Native-only mode: stub impl methods + an empty `@extern` object. */
    "NativeEmitter golden (headers unavailable)" - {
        "noheaders" in {
            val out = NativeEmitter.emit(specNoHeaders, headersAvailable = false)
            checkOrUpdate("native-noheaders", "noheaders", out)
        }
    }

end EmitterGoldenTest
