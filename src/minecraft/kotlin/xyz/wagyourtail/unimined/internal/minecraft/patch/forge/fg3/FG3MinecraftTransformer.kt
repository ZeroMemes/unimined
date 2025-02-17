package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3

import com.google.gson.JsonParser
import net.minecraftforge.binarypatcher.ConsoleTool
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.logging.LogLevel
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.*
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.internal.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.ForgeLikeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpConfigData
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpConfigStep
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.mcpconfig.McpExecutor
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.AssetsDownloader
import xyz.wagyourtail.unimined.internal.minecraft.transform.fixes.FixFG2At
import xyz.wagyourtail.unimined.internal.minecraft.transform.merge.ClassMerger
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.*

class FG3MinecraftTransformer(project: Project, val parent: ForgeLikeMinecraftTransformer): JarModMinecraftTransformer(
    project, parent.provider, jarModProvider = "forge", providerName = "${parent.providerName}-FG3"
) {

    init {
        project.logger.lifecycle("[Unimined/Forge] Using FG3 transformer")
        parent.provider.minecraftRemapper.addResourceRemapper { JsCoreModRemapper(project.logger) }
        parent.provider.minecraftRemapper.addExtension { StringClassNameRemapExtension(project.gradle.startParameter.logLevel) {
//            it.matches(Regex("^net/minecraftforge/.*"))
            it == "net/minecraftforge/registries/ObjectHolderRegistry"
        } }
        unprotectRuntime = true
    }

    override val prodNamespace by lazy { provider.mappings.getNamespace("searge") }

    override val merger: ClassMerger
        get() = throw UnsupportedOperationException("FG3+ does not support merging with unofficial merger.")


    @ApiStatus.Internal
    val clientExtra = project.configurations.maybeCreate("clientExtra".withSourceSet(provider.sourceSet)).also {
        provider.minecraft.extendsFrom(it)
    }

    val mcpConfig: Dependency by lazy {
        project.dependencies.create(
            userdevCfg["mcp"]?.asString ?: "de.oceanlabs.mcp:mcp_config:${provider.version}@zip"
        )
    }

    val mcpConfigData by lazy {
        val configuration = project.configurations.detachedConfiguration()
        configuration.dependencies.add(mcpConfig)
        configuration.resolve()
        val config = configuration.getFile(mcpConfig, Regex("zip"))
        val configJson = config.toPath().readZipInputStreamFor("config.json") {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }
        McpConfigData.fromJson(configJson)
    }

    val forgeUd by lazy {
        val forgeDep = parent.forge.dependencies.first()

        // detect if userdev3 or userdev
        //   read if forgeDep has binpatches file
        val forgeUni = parent.forge.getFile(forgeDep)
        val userdevClassifier = forgeUni.toPath().readZipInputStreamFor<String?>(
            "binpatches.pack.lzma", false
        ) {
            "userdev3"
        } ?: "userdev"

        val userdev = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:$userdevClassifier"

        val forgeUd = project.configurations.detachedConfiguration()
        forgeUd.dependencies.add(project.dependencies.create(userdev))

        // get forge userdev jar
        forgeUd.getFile(forgeUd.dependencies.last())
    }

    override val transform = (listOf<(FileSystem) -> Unit>(
        FixFG2At::fixForgeATs
    ) + super.transform).toMutableList()

    override fun beforeMappingsResolve() {
        project.logger.info("[Unimined/ForgeTransformer] FG3: beforeMappingsResolve")
        provider.mappings {
            val mcpConfigUserSpecified = mappingsDeps.entries.firstOrNull { it.value.dep.group == "de.oceanlabs.mcp" && it.value.dep.name == "mcp_config" }
            if (mcpConfigUserSpecified != null && !parent.customSearge) {
                if (mcpConfigUserSpecified.value.dep.version != mcpConfig.version) {
                    project.logger.warn("[Unimined/ForgeTransformer] FG3 does not support custom mcp_config (searge) version specification. Using ${mcpConfig.version} from userdev.")
                }
                mappingsDeps.remove(mcpConfigUserSpecified.key)
            }
            if (!parent.customSearge) searge(mcpConfig.version!!)
        }
    }

    override fun apply() {
//        val installer = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer"
//        forgeInstaller.dependencies.add(project.dependencies.create(installer))

        for (element in userdevCfg.get("libraries")?.asJsonArray ?: listOf()) {
            if (element.asString.contains("legacydev")) continue
            val dep = element.asString.split(":")
            if (dep[1] == "gson" && dep[2] == "2.8.0") {
                provider.minecraftLibraries.dependencies.add(project.dependencies.create("${dep[0]}:${dep[1]}:2.8.9"))
            } else {
                provider.minecraftLibraries.dependencies.add(project.dependencies.create(element.asString))
            }
        }

        if (userdevCfg.has("inject")) {
            project.logger.lifecycle("[Unimined/ForgeTransformer] Attempting inject forge userdev into minecraft jar")
            this.addTransform { outputJar ->
                forgeUd.toPath().forEachInZip { s, input ->
                    if (s.startsWith(userdevCfg.get("inject").asString)) {
                        val target = outputJar.getPath(s.substring(userdevCfg.get("inject").asString.length))
                        project.logger.info("[Unimined/ForgeTransformer] Injecting $s into minecraft jar")
                        Files.createDirectories(target.parent)
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString
            if (!mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[Unimined/ForgeTransformer] Inserting mcp mappings")
                provider.minecraftLibraries.dependencies.add(
                    project.dependencies.create(project.files(parent.srgToMCPAsMCP))
                )
            }
        }

        super.apply()
    }

    val userdevCfg by lazy {
        forgeUd.toPath().readZipInputStreamFor("config.json") {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }!!
    }

    @Throws(IOException::class)
    private fun executeMcp(step: String, outputPath: Path, envType: EnvType) {
        val type = when (envType) {
            EnvType.CLIENT -> "client"
            EnvType.SERVER -> "server"
            EnvType.COMBINED -> "joined"
            EnvType.DATAGEN -> "server"
        }
        val steps: List<McpConfigStep> = mcpConfigData.steps[type]!!
        val executor = McpExecutor(
            project,
            provider,
            outputPath.parent.resolve("mcpconfig").createDirectories(),
            steps,
            mcpConfigData.functions
        )
        val output: Path = executor.executeUpTo(step)
        Files.copy(output, outputPath, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("Merging client and server jars...")
        val output = MinecraftJar(
            clientjar,
            parentPath = provider.minecraftData.mcVersionFolder
                .resolve(providerName),
            envType = EnvType.COMBINED,
            mappingNamespace = if (userdevCfg["notchObf"]?.asBoolean == true) provider.mappings.OFFICIAL else provider.mappings.getNamespace("searge"),
            fallbackNamespace = provider.mappings.OFFICIAL
        )
        createClientExtra(clientjar, serverjar, output.path)
        if (output.path.exists() && !project.unimined.forceReload) {
            return output
        }
        if (userdevCfg["notchObf"]?.asBoolean == true) {
            executeMcp("merge", output.path, EnvType.COMBINED)
        } else {
            executeMcp("rename", output.path, EnvType.COMBINED)
        }
        return output
    }

    private fun createClientExtra(
        baseMinecraftClient: MinecraftJar,
        @Suppress("UNUSED_PARAMETER") baseMinecraftServer: MinecraftJar?,
        patchedMinecraft: Path
    ) {
        val clientExtra = patchedMinecraft.parent.createDirectories()
            .resolve("client-extra-${provider.version}.jar")

        if (this.clientExtra.dependencies.isEmpty()) {
            this.clientExtra.dependencies.add(
                project.dependencies.create(
                    project.files(clientExtra.toString())
                )
            )
        }

        if (clientExtra.exists()) {
            if (project.unimined.forceReload) {
                clientExtra.deleteExisting()
            } else {
                return
            }
        }

        clientExtra.openZipFileSystem(mapOf("mutable" to true, "create" to true)).use { extra ->
            baseMinecraftClient.path.openZipFileSystem().use { base ->
                for (path in Files.walk(base.getPath("/"))) {
                    // skip meta-inf
                    if (path.nameCount > 0 && path.getName(0).toString().equals("META-INF", ignoreCase = true)) continue
//            project.logger.warn("Checking $path")
                    if (!path.isDirectory() && path.extension != "class") {
//                project.logger.warn("Copying $path")
                        val target = extra.getPath(path.toString())
                        target.parent.createDirectories()
                        path.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("[Unimined/Forge] transforming minecraft jar for FG3")
        project.logger.info("minecraft: $minecraft")

        val forgeUniversal = parent.forge.dependencies.last()

        val outFolder = minecraft.path.parent.resolve(providerName).resolve(forgeUniversal.version).createDirectories()

        val inputMC = if (minecraft.envType != EnvType.COMBINED) {
            // if userdev cfg says notch
            if (userdevCfg["notchObf"]?.asBoolean == true) {
                throw IllegalStateException("Forge userdev3 (legacy fg3, aka 1.12.2) is not supported for non-combined environments currently.")
            }
            // run mcp_config to rename

            val output = MinecraftJar(
                minecraft,
                parentPath = provider.minecraftData.mcVersionFolder
                    .resolve(providerName),
                mappingNamespace = provider.mappings.getNamespace("searge"),
                fallbackNamespace = provider.mappings.OFFICIAL,
                patches = minecraft.patches + "mcp_config"
            )
            if (minecraft.envType == EnvType.CLIENT) {
                createClientExtra(minecraft, null, output.path)
            }
            if (!output.path.exists() || project.unimined.forceReload) {
                executeMcp("rename", output.path, EnvType.CLIENT)
            }
            output
        } else {
            minecraft
        }

        val patchedMC = MinecraftJar(
            inputMC,
            name = "forge",
            parentPath = outFolder
        )


        //  extract binpatches
        val binPatchFile = if (patchedMC.envType == EnvType.COMBINED) {
            forgeUd.toPath().readZipInputStreamFor(userdevCfg["binpatches"].asString) {
                outFolder.resolve("binpatches-joined.lzma").apply {
                    writeBytes(
                        it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            }
        } else {
            // get from forge installer
            project.configurations.detachedConfiguration(project.dependencies.create(
                "${forgeUniversal.group}:${forgeUniversal.name}:${forgeUniversal.version}:installer"
            )).resolve().first { it.extension == "jar" }.toPath().readZipInputStreamFor("data/${patchedMC.envType.classifier}.lzma") {
                outFolder.resolve("binpatches-${patchedMC.envType.classifier}.lzma").apply {
                    writeBytes(
                        it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            }
        }


        if (!patchedMC.path.exists() || project.unimined.forceReload) {
            patchedMC.path.deleteIfExists()
            val args = (userdevCfg["binpatcher"].asJsonObject["args"].asJsonArray.map {
                when (it.asString) {
                    "{clean}" -> inputMC.path.toString()
                    "{patch}" -> binPatchFile.toString()
                    "{output}" -> patchedMC.path.toString()
                    else -> it.asString
                }
            } + listOf("--data", "--unpatched")).toTypedArray()
            val stoutLevel = project.gradle.startParameter.logLevel
            val stdout = System.out
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(PrintStream(NullOutputStream.NULL_OUTPUT_STREAM))
            }
            project.logger.info("Running binpatcher with args: ${args.joinToString(" ")}")
            try {
                ConsoleTool.main(args)
            } catch (e: Throwable) {
                e.printStackTrace()
                patchedMC.path.deleteIfExists()
                throw e
            }
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(stdout)
            }
        }
        //   shade in forge jar
        val shadedForge = super.transform(patchedMC)
        return if (userdevCfg["notchObf"]?.asBoolean == true) {
            provider.minecraftRemapper.provide(shadedForge, provider.mappings.getNamespace("searge"), provider.mappings.OFFICIAL)
        } else {
            shadedForge
        }
    }

    val legacyClasspath = provider.localCache.createDirectories().resolve("legacy_classpath.txt")

    private fun getArgValue(config: RunConfig, arg: String): String {
        if (arg.startsWith("{")) {
            return when (arg) {
                "{minecraft_classpath_file}" -> {
                    legacyClasspath.toString()
                }

                "{modules}" -> {
                    val libs = mapOf(*provider.minecraftLibraries.dependencies.map { it.group + ":" + it.name + ":" + it.version to it }
                        .toTypedArray())
                    userdevCfg.get("modules").asJsonArray.joinToString(File.pathSeparator) {
                        val dep = libs[it.asString]
                            ?: throw IllegalStateException("Module ${it.asString} not found in mc libraries")
                        provider.minecraftLibraries.getFile(dep).toString()
                    }
                }

                "{assets_root}" -> {
                    val assetsDir = provider.minecraftData.metadata.assetIndex?.let {
                        AssetsDownloader.assetsDir(project)
                    }
                    (assetsDir ?: config.workingDir.resolve("assets").toPath()).toString()
                }

                "{asset_index}" -> provider.minecraftData.metadata.assetIndex?.id ?: ""
                "{source_roots}" -> {
                    (detectProjectSourceSets().flatMap {
                        listOf(
                            it.output.resourcesDir
                        ) + it.output.classesDirs
                    }).joinToString(
                        File.pathSeparator
                    ) { "mod%%$it" }
                }

                "{mcp_mappings}" -> "unimined.stub"
                "{natives}" -> {
                    val nativesDir = config.workingDir.resolve("natives").toPath()
                    nativesDir.createDirectories()
                    nativesDir.toString()
                }

                else -> throw IllegalArgumentException("Unknown arg $arg")
            }
        } else {
            return arg
        }
    }

    private fun createLegacyClasspath() {
//        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
//        val source = sourceSets.findByName("client") ?: sourceSets.getByName("main")

        legacyClasspath.writeText(
            (provider.minecraftLibraries.files + provider.minecraftFileDev + clientExtra.resolve()).joinToString(
                "\n"
            ) { it.toString() }, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    override fun applyClientRunTransform(config: RunConfig) {
        super.applyClientRunTransform(config)
        createLegacyClasspath()
        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString
            parent.tweakClassClient = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[FG3] Using legacydev launchwrapper")
                config.mainClass = "net.minecraft.launchwrapper.Launch"
                config.jvmArgs += "-Dfml.deobfuscatedEnvironment=true"
                config.jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
                config.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                config.args += listOf("--tweakClass",
                    parent.tweakClassClient ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"
                )
            } else {
                project.logger.info("[FG3] Using new client run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                config.mainClass = mainClass
                config.args.clear()
                config.args += args.map { getArgValue(config, it) }
                config.jvmArgs += jvmArgs.map { getArgValue(config, it) }
                config.jvmArgs += props.map { "-D${it.key}=${getArgValue(config, it.value)}" }
                config.env += mapOf("FORGE_SPEC" to userdevCfg.get("spec").asNumber.toString())
                config.env += env.map { it.key to getArgValue(config, it.value) }
            }
        }

    }

    override fun applyServerRunTransform(config: RunConfig) {
        super.applyServerRunTransform(config)
        userdevCfg.get("runs").asJsonObject.get("server").asJsonObject.apply {
            val mainClass = get("main").asString
            parent.tweakClassServer = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[FG3] Using legacydev launchwrapper")
                config.mainClass = "net.minecraft.launchwrapper.Launch"
                config.jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
                config.jvmArgs += "-Dfml.deobfuscatedEnvironment=true"
                config.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                config.args += listOf("--tweakClass",
                    parent.tweakClassClient ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"
                )
            } else {
                project.logger.info("[FG3] Using new server run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                config.mainClass = mainClass
                config.args.clear()
                config.args += args.map { getArgValue(config, it) }
                config.jvmArgs += jvmArgs.map { getArgValue(config, it) }
                config.jvmArgs += props.map { "-D${it.key}=${getArgValue(config, it.value)}" }
                config.env += mapOf("FORGE_SPEC" to userdevCfg.get("spec").asNumber.toString())
                config.env += env.map { it.key to getArgValue(config, it.value) }
            }
        }
    }

    override fun applyExtraLaunches() {
        super.applyExtraLaunches()
        if (provider.side == EnvType.DATAGEN) {
            TODO("DATAGEN not supported yet")
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        val out = fixForge(baseMinecraft)
        out.path.openZipFileSystem().use { fs ->
            return parent.applyATs(out, listOf(
                fs.getPath("fml_at.cfg"), fs.getPath("forge_at.cfg"), fs.getPath("META-INF/accesstransformer.cfg")
            ).filter { Files.exists(it) })
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
            try {
                target.path.openZipFileSystem(mapOf("mutable" to true)).use { out ->
                    out.getPath("binpatches.pack.lzma").deleteIfExists()
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            return target
        }
        return baseMinecraft
    }
}