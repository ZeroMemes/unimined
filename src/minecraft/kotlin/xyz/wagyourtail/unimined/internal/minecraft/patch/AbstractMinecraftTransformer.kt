package xyz.wagyourtail.unimined.internal.minecraft.patch

import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.task.RemapJarTask
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixParamAnnotations
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.util.*
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

abstract class AbstractMinecraftTransformer protected constructor(
    protected val project: Project,
    val provider: MinecraftProvider,
    val providerName: String
): MinecraftPatcher {

    open val merger: ClassMerger = ClassMerger()

    override val prodNamespace: MappingNamespaceTree.Namespace = provider.mappings.OFFICIAL

    override var onMergeFail: (clientNode: ClassNode, serverNode: ClassNode, fs: FileSystem, exception: Exception) -> Unit by FinalizeOnRead { cl, _, _, e ->
        throw RuntimeException("Error merging class ${cl.name}", e)
    }

    override var unprotectRuntime by FinalizeOnRead(false)

    override var canCombine: Boolean by FinalizeOnRead(LazyMutable {
        provider.minecraftData.mcVersionCompare(provider.version, "1.3") > -1
    })

    fun isAnonClass(node: ClassNode): Boolean =
        node.innerClasses?.firstOrNull { it.name == node.name }.let { it != null && it.innerName == null }

    open fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar = merge(clientjar, serverjar, false)

    fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar, ignoreFallback: Boolean): MinecraftJar {
        if (!canCombine) throw UnsupportedOperationException("Merging is not supported for this version")
        if (clientjar.mappingNamespace != serverjar.mappingNamespace ||(!ignoreFallback && clientjar.fallbackNamespace != serverjar.fallbackNamespace)) {
            throw IllegalArgumentException("client and server jars must have the same mapping namespace")
        }
        val merged = MinecraftJar(
            clientjar,
            envType = EnvType.COMBINED,
            patches = listOf("$providerName-merged") + clientjar.patches + serverjar.patches
        )

        if (merged.path.exists() && !project.unimined.forceReload) {
            return merged
        }

        merged.path.deleteIfExists()
        merged.path.openZipFileSystem(mapOf("create" to true, "mutable" to true)).use { mergedFS ->
            val clientClassEntries = mutableMapOf<String, ClassNode>()
            clientjar.path.forEachInZip { path, stream ->
                if (path.startsWith("META-INF/")) return@forEachInZip
                if (path.endsWith(".class")) {
                    if (shouldStripClass(path)) return@forEachInZip
                    // add entry
                    val classReader = ClassReader(stream)
                    val classNode = ClassNode()
                    classReader.accept(classNode, 0)
                    clientClassEntries[path] = classNode
                } else {
                    // copy directly
                    val mergedPath = mergedFS.getPath(path)
                    mergedPath.parent?.createDirectories()
                    mergedPath.writeBytes(stream.readBytes())
                }
            }
            val serverClassEntries = mutableMapOf<String, ClassNode>()
            serverjar.path.forEachInZip { path, stream ->
                if (path.startsWith("META-INF/")) return@forEachInZip
                if (path.endsWith(".class")) {
                    if (shouldStripClass(path)) return@forEachInZip
                    // add entry
                    val classReader = ClassReader(stream)
                    val classNode = ClassNode()
                    classReader.accept(classNode, 0)
                    serverClassEntries[path] = classNode
                } else {
                    // copy directly
                    val mergedPath = mergedFS.getPath(path)
                    mergedPath.parent?.createDirectories()
                    if (mergedPath.exists()) {
                        project.logger.info("[Unimined/MappingsProvider] Entry in server jar already exists in client jar: $path, skipping")
                        return@forEachInZip
                    }
                    mergedPath.writeBytes(stream.readBytes())
                }
            }
            // merge classes
            for ((name, node) in clientClassEntries) {
                val classWriter = ClassWriter(0)
                val serverNode = serverClassEntries[name]
                val out = try {
                    merger.accept(node, serverNode)
                } catch (e: Exception) {
                    onMergeFail(node, serverNode!!, mergedFS, e)
                    continue
                }
                out.accept(classWriter)
                val path = mergedFS.getPath(name)
                path.parent?.createDirectories()
                path.writeBytes(classWriter.toByteArray())
                serverClassEntries.remove(name)
            }
            for ((name, node) in serverClassEntries) {
                val classWriter = ClassWriter(0)
                val out = merger.accept(null, node)
                out.accept(classWriter)
                val path = mergedFS.getPath(name)
                path.parent?.createDirectories()
                path.writeBytes(classWriter.toByteArray())
            }
        }
        return merged
    }

    protected open val transform = listOf<(FileSystem) -> Unit>(
        FixParamAnnotations::apply
    )

    @ApiStatus.Internal
    open fun transform(minecraft: MinecraftJar): MinecraftJar {
        val target = MinecraftJar(
            minecraft,
            patches = minecraft.patches + listOf("fixed")
        )

        if (target.path.exists() && !project.unimined.forceReload) {
            return target
        }

        try {
            Files.copy(minecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            target.path.openZipFileSystem(mapOf("mutable" to true)).use { out ->
                transform.forEach { it(out) }
            }
        } catch (e: Exception) {
            target.path.deleteIfExists()
            throw e
        }
        return target
    }

    open fun applyExtraLaunches() {
    }

    @ApiStatus.Internal
    open fun applyClientRunTransform(config: RunConfig) {
        if (unprotectRuntime) {
            val unprotect = project.configurations.detachedConfiguration(
                project.dependencies.create("io.github.juuxel:unprotect:1.3.0")
            ).resolve().first { it.extension == "jar" }
            config.jvmArgs.add("-javaagent:${unprotect.absolutePath}")
        }
    }

    @ApiStatus.Internal
    open fun applyServerRunTransform(config: RunConfig) {
        if (unprotectRuntime) {
            val unprotect = project.configurations.detachedConfiguration(
                project.dependencies.create("io.github.juuxel:unprotect:1.3.0")
            ).resolve().first { it.extension == "jar" }
            config.jvmArgs.add("-javaagent:${unprotect.absolutePath}")
        }
    }

    @ApiStatus.Internal
    open fun apply() {
    }

    @ApiStatus.Internal
    open fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return baseMinecraft
    }

    override fun beforeRemapJarTask(remapJarTask: RemapJarTask, input: Path): Path {
        return input
    }

    @ApiStatus.Internal
    override fun afterRemapJarTask(remapJarTask: RemapJarTask, output: Path) {
        // do nothing
    }

    protected open val includeGlobs = listOf(
        "*",
        "META-INF/**",
        "net/minecraft/**",
        "com/mojang/blaze3d/**",
        "paulscode/sound/**",
        "com/jcraft/**"
    )

    /*
     * only accurate on official mappings
     */
    open fun shouldStripClass(path: String): Boolean {
        // check if in include globs
        for (glob in includeGlobs.map { Regex(GlobToRegex.apply(it)) }) {
            if (glob.matches(path)) return false
        }
        // otherwise strip
        return true
    }

    open fun beforeMappingsResolve() {
        // do nothing
    }

    open fun afterEvaluate() {}

    open fun libraryFilter(library: Library): Boolean {
        return true
    }
}