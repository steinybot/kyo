package kyo.grpc.compiler

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import protocbridge.Artifact
import protocgen.CodeGenApp
import protocgen.CodeGenRequest
import protocgen.CodeGenResponse
import scala.jdk.CollectionConverters.*
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.GeneratorException
import scalapb.compiler.ProtobufGenerator
import scalapb.options.Scalapb

object CodeGenerator extends CodeGenApp {

    override def registerExtensions(registry: ExtensionRegistry): Unit =
        Scalapb.registerAllExtensions(registry)

    // When your code generator will be invoked from SBT via sbt-protoc, this will add the following
    // artifact to your users build whenever the generator is used in `PB.targets`:
    override def suggestedDependencies: Seq[Artifact] =
        Seq(
            Artifact(
                BuildInfo.organization,
                "kyo-grpc-core",
                BuildInfo.version,
                crossVersion = true
            )
        )

    // This is called by CodeGenApp after the request is parsed.
    // Example: scalapb.compiler.ProtobufGenerator.handleCodeGeneratorRequest
    def process(request: CodeGenRequest): CodeGenResponse =
        ProtobufGenerator.parseParameters(request.parameter) match {
            case Right(params) =>
                try {
                    val implicits = DescriptorImplicits.fromCodeGenRequest(params, request)
                    import implicits.ExtendedFileDescriptor
                    val files = request.filesToGenerate.filterNot(_.disableOutput).flatMap { file =>
                        if (file.scalaOptions.getSingleFile)
                            singleFile(file)
                        else
                            multipleFiles(file, implicits)
                    }
                    CodeGenResponse.succeed(files, Set(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL))
                } catch {
                    case e: GeneratorException =>
                        CodeGenResponse.fail(e.message)
                }
            case Left(error) =>
                CodeGenResponse.fail(error)
        }

    // TODO: There should be a separate code generator for client and server.
    //  It is virtually never the case that you have both in the same project so they should be separate.

    // TODO
    private def singleFile(file: FileDescriptor) =
        ???

    private def multipleFiles(file: FileDescriptor, implicits: DescriptorImplicits) =
        file.getServices.asScala.map { service =>
            new ServicePrinter(service, implicits).result
        }
}
