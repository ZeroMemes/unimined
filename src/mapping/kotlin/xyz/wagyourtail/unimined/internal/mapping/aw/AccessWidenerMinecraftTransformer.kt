package xyz.wagyourtail.unimined.internal.mapping.aw

import net.fabricmc.accesswidener.*
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.util.openZipFileSystem
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

object AccessWidenerMinecraftTransformer {

    class AwRemapper(val source: String, val target: String, val catchNsError: Boolean, val logger: Logger?): OutputConsumerPath.ResourceRemapper {

        constructor(source: String, target: String): this(source, target, false, null)

        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean {
            // read the beginning of the file and see if it begins with "accessWidener"
            return relativePath.extension.equals("accesswidener", true) ||
                    relativePath.extension.equals("aw", true)
        }

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val awr = AccessWidenerWriter()
            val aw = input.readBytes()
            try {
                AccessWidenerReader(AccessWidenerRemapper(awr, remapper.environment.remapper, source, target)).read(BufferedReader(InputStreamReader(ByteArrayInputStream(aw), StandardCharsets.UTF_8)))
                val output = destinationDirectory.resolve(relativePath)
                output.parent.createDirectories()
                Files.write(output, awr.write(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            } catch (t: IllegalArgumentException) {
                if (t.message?.startsWith("Cannot remap access widener from namespace") != true) throw t
                if (!catchNsError) {
                    throw t
                } else {
                    logger!!.warn("[Unimined/AccessWidenerTransformer] Skipping access widener $relativePath due to namespace mismatch, writing original!!")
                    Files.write(destinationDirectory.resolve(relativePath), aw, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
            }
        }
    }

    fun transform(
        accessWidener: Path,
        namespace: String,
        baseMinecraft: Path,
        output: Path,
        throwIfNSWrong: Boolean,
        logger: Logger
    ): Boolean {
        val aw = AccessWidener()
        AccessWidenerReader(aw).read(BufferedReader(accessWidener.reader()))
        if (aw.namespace == namespace) {
            Files.copy(baseMinecraft, output, StandardCopyOption.REPLACE_EXISTING)
            output.openZipFileSystem(mapOf("mutable" to true)).use { fs ->
                logger.debug("Transforming $output with access widener $accessWidener and namespace $namespace")
                for (target in aw.targets) {
                    try {
                        val targetClass = "/" + target.replace(".", "/") + ".class"
                        val targetPath = fs.getPath(targetClass)
                        logger.debug("Transforming $targetPath")
                        if (Files.exists(targetPath)) {
                            val reader = ClassReader(targetPath.inputStream())
                            val writer = ClassWriter(0)
                            val visitor = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, aw)
                            reader.accept(visitor, 0)
                            Files.write(
                                targetPath,
                                writer.toByteArray(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                        } else {
                            logger.warn("Could not find class $targetClass in $output")
                        }
                    } catch (e: Exception) {
                        logger.warn(
                            "An error occurred while transforming $target with access widener $accessWidener for namespace $namespace in $output",
                            e
                        )
                    }
                }
            }
            return true
        }
        if (throwIfNSWrong) {
            throw IllegalStateException("AccessWidener namespace (${aw.namespace}) does not match minecraft namespace ($namespace)")
        } else {
            logger.info("AccessWidener ($accessWidener) namespace (${aw.namespace}) does not match minecraft namespace ($namespace), it will not be applied!")
        }
        return false
    }

    fun mergeAws(
        inputs: List<Path>,
        output: Path,
        targetNamespace: MappingNamespaceTree.Namespace,
        mappingsProvider: MappingsProvider,
        mcProvider: MinecraftConfig
    ): Path {
        val merger = AccessWidenerMerger(targetNamespace.name)

        inputs.forEach {
            createVisitors(merger, mappingsProvider, targetNamespace.name, it.inputStream(), mcProvider)
        }

        output.bufferedWriter(
            StandardCharsets.UTF_8,
            1024,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).write(merger.writeToAccessWidenerWriter().writeString())

        return output
    }

    private fun createVisitors(
        awm: AccessWidenerMerger,
        mappingsProvider: MappingsProvider,
        target: String,
        input: InputStream,
        mcProvider: MinecraftConfig
    ) {
        AccessWidenerReader(AccessWidenerBetterRemapper(awm, mappingsProvider, target, mcProvider)).read(
            BufferedReader(InputStreamReader(input))
        )
    }
}