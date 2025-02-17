package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg1

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.util.deleteRecursively
import xyz.wagyourtail.unimined.util.openZipFileSystem
import xyz.wagyourtail.unimined.util.readZipInputStreamFor
import xyz.wagyourtail.unimined.util.withSourceSet
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

class FG1MinecraftTransformer(project: Project, val parent: ForgeLikeMinecraftTransformer): JarModAgentMinecraftTransformer(
    project,
    parent.provider,
    providerName = "${parent.providerName}-FG1"
) {
    init {
        project.logger.lifecycle("[Unimined/Forge] Using FG1 transformer")
    }

    override val prodNamespace: MappingNamespaceTree.Namespace
        get() = provider.mappings.OFFICIAL

    var resolvedForgeDeps = false

    override val merger: ClassMerger
        get() = parent.merger

    override fun beforeMappingsResolve() {
        val forge = parent.forge.dependencies.first()
        provider.mappings.mappingsDeps.apply {
            if (isEmpty() && !parent.customSearge)
                provider.mappings {
                    forgeBuiltinMCP(forge.version!!.substringAfter("${provider.version}-"))
                }
        }
    }

    override fun apply() {
        // get and add forge-src to mappings
        parent.forge.dependencies.forEach(jarModConfiguration.dependencies::add)

        val forge = parent.forge.resolve().first().toPath()

        // resolve dyn libs
        forge.readZipInputStreamFor("cpw/mods/fml/relauncher/CoreFMLLibraries.class", false) {
            resolveDynLibs(provider.localCache.resolve("fmllibs").toFile(), getDynLibs(it))
        }

        super.apply()
    }

    private val forgeDeps: Configuration = project.configurations.maybeCreate("forgeDeps".withSourceSet(provider.sourceSet)).also {
        provider.minecraft.extendsFrom(it)
    }

    lateinit var deps: List<Pair<Pair<String, String>, Dependency>>

    fun resolveDynLibs(workingDirectory: File, wanted: Set<String>) {
        val path = workingDirectory.toPath().createDirectories()

        deps = listOf(
            Pair(
                Pair("guava-12.0.1.jar", "guava-12.0.1.jar"), project.dependencies.create(
                    "com.google.guava:guava:12.0.1"
                )
            ),
            Pair(
                Pair("guava-14.0-rc3.jar", "guava-14.0-rc3.jar"), project.dependencies.create(
                    "com.google.guava:guava:14.0-rc3"
                )
            ),
            Pair(
                Pair("asm-all-4.1.jar", "asm-all-4.1.jar"), project.dependencies.create(
                    "org.ow2.asm:asm-all:4.1"
                )
            ),
            Pair(
                Pair("bcprov-jdk15on-1.48.jar", "bcprov-jdk15on-148.jar"), project.dependencies.create(
                    "org.bouncycastle:bcprov-jdk15on:1.48"
                )
            ),
            Pair(
                Pair("bcprov-jdk15on-1.47.jar", "bcprov-jdk15on-147.jar"), project.dependencies.create(
                    "org.bouncycastle:bcprov-jdk15on:1.47"
                )
            ),
        )

        deps.forEach { dep ->
            if (wanted.contains(dep.first.second)) {
                forgeDeps.dependencies.add(dep.second)
            }
        }

        if (wanted.contains("asm-all-4.0.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/asm-all-4.0.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("asm-all-4.0.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("asm-all-4.0.jar").toString())
                )
            )
        }

        if (wanted.contains("scala-library.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/scala-library.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("scala-library.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("scala-library.jar").toString())
                )
            )
        }

        if (wanted.contains("argo-small-3.2.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/argo-small-3.2.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("argo-small-3.2.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("argo-small-3.2.jar").toString())
                )
            )
        }

        if (wanted.contains("argo-2.25.jar")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/argo-2.25.jar").use { it1 ->
                val bytes = it1!!.readBytes()
                path.resolve("argo-2.25.jar")
                    .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("argo-2.25.jar").toString())
                )
            )
        }

        if (wanted.contains("deobfuscation_data_1.5.2.zip")) {
            FG1MinecraftTransformer::class.java.getResourceAsStream("/fmllibs/deobfuscation_data_1.5.2.zip")
                .use { it1 ->
                    val bytes = it1!!.readBytes()
                    path.resolve("deobfuscation_data_1.5.2.zip")
                        .writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }

            forgeDeps.dependencies.add(
                project.dependencies.create(
                    project.files(path.resolve("deobfuscation_data_1.5.2.zip").toString())
                )
            )
        }
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)

        // resolve dyn libs
        val dynLibFolder = config.workingDir.resolve("lib")
        dynLibFolder.mkdirs()
        for (file in forgeDeps.resolve()) {
            if (file.exists() && file.extension != "pom") {
                file.copyTo(dynLibFolder.resolve(file.name), overwrite = true)
            }
        }

        config.jvmArgs.add("-Dminecraft.applet.TargetDirectory=\"${config.workingDir.absolutePath}\"")
        if (parent.mainClass != null) config.mainClass = parent.mainClass!!
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        val out = fixForge(baseMinecraft)
        return out.path.openZipFileSystem().use { fs ->
            val ats = listOf(fs.getPath("forge_at.cfg"), fs.getPath("fml_at.cfg")).filter { Files.exists(it) }
            // make sure remap at's to modern
            for (at in ats) {
                AccessTransformerMinecraftTransformer.toModern(at)
            }
            // apply at's
            parent.applyATs(
                out,
                ats
            )
        }
    }

    private fun fixForge(baseMinecraft: MinecraftJar): MinecraftJar {
        if (!baseMinecraft.patches.contains("fixForge") && baseMinecraft.mappingNamespace != provider.mappings.OFFICIAL) {
            val target = MinecraftJar(
                baseMinecraft,
                patches = baseMinecraft.patches + "fixForge",
            )

            if (target.path.exists() && !project.unimined.forceReload) {
                return target
            }

            Files.copy(baseMinecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            val mc = URI.create("jar:${target.path.toUri()}")
            try {
                FileSystems.newFileSystem(mc, mapOf("mutable" to true), null).use { out ->
                    val dynPath = out.getPath("cpw/mods/fml/relauncher/CoreFMLLibraries.class")

                    val dyn = if (dynPath.exists()) dynPath.inputStream().use { it ->
                        getDynLibs(it)
                    } else setOf()

                    if (dyn.any { it.contains("argo") }) {
                        out.getPath("argo").apply {
                            if (exists()) {
                                deleteRecursively()
                            }
                        }
                    }
                    if (dyn.any { it.contains("bcprov") }) {
                        out.getPath("org/bouncycastle").apply {
                            if (exists()) {
                                deleteRecursively()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            return target
        }
        return baseMinecraft
    }

    private fun getDynLibs(stream: InputStream): Set<String> {
        project.logger.lifecycle("[Unimined/ForgeTransformer] Getting dynamic libraries from FML")
        val classReader = ClassReader(stream)

        val classNode = ClassNode()
        classReader.accept(classNode, 0)

        // get string array stored to libraries from clinit
        val clInit = classNode.methods.first { it.name == "<clinit>" }
        val iterator = clInit.instructions.iterator()
        val outSet = mutableSetOf<String>()
        var arrayFlag = false
        while (iterator.hasNext()) {
            val insn = iterator.next()
            if (insn is TypeInsnNode && insn.opcode == Opcodes.ANEWARRAY && insn.desc == "java/lang/String") {
                arrayFlag = true
            }
            if (arrayFlag) {
                if (insn is LdcInsnNode) {
                    outSet.add(insn.cst as String)
                }
                if (insn is MethodInsnNode && insn.opcode == Opcodes.INVOKESTATIC && insn.name == "debfuscationDataName") {
                    outSet.add("deobfuscation_data_${provider.version}.zip")
                }
            }
            if (insn is FieldInsnNode && insn.opcode == Opcodes.PUTSTATIC) {
                if (insn.name == "libraries") {
                    break
                } else {
                    arrayFlag = false
                    outSet.clear()
                }
            }
        }
        return outSet
    }
}