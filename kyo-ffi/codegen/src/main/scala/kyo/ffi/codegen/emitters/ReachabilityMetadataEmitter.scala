package kyo.ffi.codegen.emitters

import kyo.Doc
import kyo.Printer
import kyo.ffi.codegen.model.*

/** Emits the GraalVM native-image `reachability-metadata.json` for one `Ffi` trait's generated JVM impl.
  *
  * The FFM downcall and upcall descriptors are taken verbatim from [[JvmEmitter.functionDescriptor]] and
  * [[JvmEmitter.callbackDescriptor]], the same `FunctionDescriptor`s the generated impl builds at class-construction
  * time, then each Panama `ValueLayout` name is re-spelled as the native-image foreign token (`JAVA_INT` becomes `jint`,
  * `ADDRESS` becomes `void*`). Reusing the emitter's descriptors is what keeps this metadata from drifting away from the
  * generated code: a new binding, a changed signature, or a new option flows through the shared descriptor logic into both
  * the impl and this manifest.
  *
  * The generated `<simpleName>Impl` is registered for reflective instantiation because `FfiReflect` loads it on the JVM via
  * `Class.forName(...).getDeclaredConstructor().newInstance()`. The remaining reflection a native-image build needs (the kyo
  * scheduler, `Tag`, `Log`, and so on) is auto-derived by native-image's own analysis and is not emitted here.
  */
object ReachabilityMetadataEmitter:

    /** Panama `ValueLayout` name (as emitted by [[JvmEmitter]]) to its native-image foreign type token. Downcall and callback
      * signatures only ever use these primitive layouts and `ADDRESS` (structs are always passed by pointer), so no struct
      * layout token is needed here.
      */
    private val layoutToken: Map[String, String] = Map(
        "JAVA_BOOLEAN" -> "jboolean",
        "JAVA_BYTE"    -> "jbyte",
        "JAVA_CHAR"    -> "jchar",
        "JAVA_SHORT"   -> "jshort",
        "JAVA_INT"     -> "jint",
        "JAVA_LONG"    -> "jlong",
        "JAVA_FLOAT"   -> "jfloat",
        "JAVA_DOUBLE"  -> "jdouble",
        "ADDRESS"      -> "void*"
    )

    private def token(layout: String): String =
        layoutToken.getOrElse(
            layout.trim,
            throw new IllegalStateException(
                s"ReachabilityMetadataEmitter: no native-image foreign token for Panama layout '$layout'"
            )
        )

    /** Map a descriptor's return + parameter layouts to their native-image foreign tokens. A `None` return is the
      * `FunctionDescriptor.ofVoid` shape and maps to the `void` token.
      */
    private def tokens(desc: JvmEmitter.FunctionDescriptorExpr): (String, List[String]) =
        (desc.returnLayout.map(token).getOrElse("void"), desc.paramLayouts.map(token))
    end tokens

    private def str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private def arr(items: Seq[String]): String = items.mkString("[", ", ", "]")

    private def downcall(method: MethodSpec, structsByName: Map[String, StructSpec]): String =
        val (ret, params) = tokens(JvmEmitter.functionDescriptor(method, structsByName))
        // errno capture is always on (mirrors JvmEmitter's `capture` option); critical(true) only for a non-blocking method
        // with an array parameter, which pins the array off-heap for a zero-copy call.
        val options =
            if !method.blocking && method.hasArrayParam then
                """{ "captureCallState": true, "critical": { "allowHeapAccess": true } }"""
            else
                """{ "captureCallState": true }"""
        s"""      { "returnType": ${str(ret)}, "parameterTypes": ${arr(params.map(str))}, "options": $options }"""
    end downcall

    /** Distinct callback C signatures reachable from the trait: top-level function-pointer parameters and function-pointer
      * struct fields. Each becomes a general (descriptor-only) upcall registration.
      */
    private def callbackSignatures(spec: TraitSpec, structsByName: Map[String, StructSpec]): List[(List[TypeRef], TypeRef)] =
        val fromParams =
            spec.methods.flatMap(_.params).collect { case ParamSpec(_, TypeRef.FnPtrT(ps, r)) => (ps, r) }
        val fromStructFields =
            structsByName.values.toList.flatMap(_.fields).collect { case StructField(_, TypeRef.FnPtrT(ps, r)) => (ps, r) }
        (fromParams ++ fromStructFields).distinct
    end callbackSignatures

    private def upcall(sig: (List[TypeRef], TypeRef)): String =
        val (params, ret) = sig
        val (r, ps)       = tokens(JvmEmitter.callbackDescriptor(params, ret))
        s"""      { "returnType": ${str(r)}, "parameterTypes": ${arr(ps.map(str))} }"""
    end upcall

    /** The `reachability-metadata.json` content for one trait's generated JVM impl. */
    def emit(spec: TraitSpec): String = emitModule(Seq(spec))

    /** The merged `reachability-metadata.json` for all of a module's generated JVM impls: the union of every trait's reflection
      * entry, downcall, and (deduplicated) upcall. One file per module, so a jar carries a single manifest that native-image
      * auto-discovers. Callers pass only the traits emitted for the JVM platform and only when the list is non-empty.
      */
    def emitModule(specs: Seq[TraitSpec]): String =
        val reflectionEntries =
            specs.map { spec =>
                val implFqcn = s"${spec.packageName}.${spec.simpleName}Impl"
                s"""    { "type": ${str(implFqcn)}, "methods": [ { "name": "<init>", "parameterTypes": [] } ] }"""
            }
        val downcalls =
            specs.flatMap { spec =>
                val structsByName = spec.structs.map(s => s.simpleName -> s).toMap
                spec.methods.map(m => downcall(m, structsByName))
            }
        val upcalls =
            specs.flatMap { spec =>
                val structsByName = spec.structs.map(s => s.simpleName -> s).toMap
                callbackSignatures(spec, structsByName)
            }.distinct.map(upcall)

        // Comma-terminated JSON entry lines: every entry except the last carries the separating comma.
        def commaTerminated(entries: List[String]): List[String] =
            entries match
                case Nil => Nil
                case _   => entries.init.map(_ + ",") :+ entries.last

        val lines = List.newBuilder[String]
        lines += "{"
        lines += """  "reflection": ["""
        lines ++= commaTerminated(reflectionEntries.toList)
        lines += "  ],"
        lines += """  "foreign": {"""
        lines += """    "downcalls": ["""
        lines ++= commaTerminated(downcalls.toList)
        if upcalls.isEmpty then
            lines += "    ]"
        else
            lines += "    ],"
            lines += """    "upcalls": ["""
            lines ++= commaTerminated(upcalls.toList)
            lines += "    ]"
        end if
        lines += "  }"
        lines += "}"

        Printer.greedy.print(EmitterBase.hardTextLines(lines.result()).append(Doc.hardNewline), EmitterBase.RenderWidth)
    end emitModule

end ReachabilityMetadataEmitter
