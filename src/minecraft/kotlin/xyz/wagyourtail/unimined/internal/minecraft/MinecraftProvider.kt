package xyz.wagyourtail.unimined.internal.minecraft

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.jvm.tasks.Jar
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.mapping.MappingsConfig
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.*
import xyz.wagyourtail.unimined.api.runs.RunConfig
import xyz.wagyourtail.unimined.api.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.mapping.MappingsProvider
import xyz.wagyourtail.unimined.internal.mapping.task.ExportMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.NoTransformMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.BabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.LegacyFabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.OfficialFabricMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.fabric.QuiltMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.MinecraftForgeMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.NeoForgedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.merged.MergedMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.AssetsDownloader
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Extract
import xyz.wagyourtail.unimined.internal.minecraft.resolver.Library
import xyz.wagyourtail.unimined.internal.minecraft.resolver.MinecraftDownloader
import xyz.wagyourtail.unimined.internal.minecraft.task.GenSourcesTaskImpl
import xyz.wagyourtail.unimined.internal.mods.ModsProvider
import xyz.wagyourtail.unimined.internal.mods.task.RemapJarTaskImpl
import xyz.wagyourtail.unimined.internal.runs.RunsProvider
import xyz.wagyourtail.unimined.util.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

class MinecraftProvider(project: Project, sourceSet: SourceSet) : MinecraftConfig(project, sourceSet) {
    override val minecraftData = MinecraftDownloader(project, this)

    override val mappings = MappingsProvider(project, this)

    override var mcPatcher: MinecraftPatcher by FinalizeOnRead(FinalizeOnWrite(NoTransformMinecraftTransformer(project, this)))

    override val mods = ModsProvider(project, this)

    override val runs = RunsProvider(project, this)

    override val minecraftRemapper = MinecraftRemapper(project, this)

    private val patcherActions = ArrayDeque<() -> Unit>()
    private var lateActionsRunning by FinalizeOnWrite(false)

    var applied: Boolean by FinalizeOnWrite(false)
        private set

    val minecraft: Configuration = project.configurations.maybeCreate("minecraft".withSourceSet(sourceSet)).also {
        sourceSet.compileClasspath += it
        sourceSet.runtimeClasspath += it
    }

    override val minecraftLibraries: Configuration = project.configurations.maybeCreate("minecraftLibraries".withSourceSet(sourceSet)).also {
        minecraft.extendsFrom(it)
        it.setTransitive(false)
    }

    override fun remap(task: Task, name: String, action: RemapJarTask.() -> Unit) {
        val remapTask = project.tasks.register(name, RemapJarTaskImpl::class.java, this)
        remapTask.configure {
            if (task is Jar) {
                it.inputFile.value(task.archiveFile)
            }
            it.action()
            it.dependsOn(task)
        }
    }

    override val mergedOfficialMinecraftFile: File? by lazy {
        val client = minecraftData.minecraftClient
        val server = minecraftData.minecraftServer
        val noTransform = NoTransformMinecraftTransformer(project, this)
        if (noTransform.canCombine) {
            noTransform.merge(client, server).path.toFile()
        } else {
            null
        }
    }

    private val minecraftFiles: Map<Pair<MappingNamespaceTree.Namespace, MappingNamespaceTree.Namespace>, Path> = defaultedMapOf {
        project.logger.info("[Unimined/Minecraft] Providing minecraft files for $it")
        val mc = if (side == EnvType.COMBINED) {
            val client = minecraftData.minecraftClient
            val server = minecraftData.minecraftServer
            (mcPatcher as AbstractMinecraftTransformer).merge(client, server)
        } else {
            minecraftData.getMinecraft(side)
        }
        val path = (mcPatcher as AbstractMinecraftTransformer).afterRemap(
            minecraftRemapper.provide((mcPatcher as AbstractMinecraftTransformer).transform(mc), it.first, it.second)
        ).path
        if (!path.exists()) throw IOException("minecraft path $path does not exist")
        path
    }

    override fun getMinecraft(namespace: MappingNamespaceTree.Namespace, fallbackNamespace: MappingNamespaceTree.Namespace): Path {
        return minecraftFiles[namespace to fallbackNamespace] ?: error("minecraft file not found for $namespace")
    }

    override fun mappings(action: MappingsConfig.() -> Unit) {
        if (lateActionsRunning) {
            mappings.action()
        } else {
            patcherActions.addLast {
                mappings.action()
            }
        }
    }

    override fun merged(action: MergedPatcher.() -> Unit) {
        mcPatcher = MergedMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun fabric(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = OfficialFabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun legacyFabric(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = LegacyFabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun babric(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = BabricMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun quilt(action: FabricLikePatcher.() -> Unit) {
        mcPatcher = QuiltMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    @Deprecated("Please specify which forge.", replaceWith = ReplaceWith("minecraftForge(action)"))
    override fun forge(action: ForgeLikePatcher.() -> Unit) {
        minecraftForge(action)
    }

    override fun minecraftForge(action: MinecraftForgePatcher.() -> Unit) {
        mcPatcher = MinecraftForgeMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun neoForged(action: NeoForgedPatcher.() -> Unit) {
        mcPatcher = NeoForgedMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    override fun jarMod(action: JarModAgentPatcher.() -> Unit) {
        mcPatcher = JarModAgentMinecraftTransformer(project, this).also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    @ApiStatus.Experimental
    override fun <T: MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit) {
        this.mcPatcher = mcPatcher.also {
            patcherActions.addFirst {
                action(it)
            }
        }
    }

    val minecraftDepName: String = project.path.replace(":", "_").let { projectPath ->
        "minecraft${if (projectPath == "_") "" else projectPath}${if (sourceSet.name == "main") "" else "+"+sourceSet.name}"
    }

    override val minecraftDependency: ModuleDependency by lazy {
        project.dependencies.create("net.minecraft:$minecraftDepName:$version" + if (side != EnvType.COMBINED) ":${side.classifier}" else "") as ModuleDependency
    }

    private val extractDependencies: MutableMap<Dependency, Extract> = mutableMapOf()

    fun addLibraries(libraries: List<Library>) {
        for (library in libraries) {
            if (library.rules.all { it.testRule() }) {
                project.logger.info("[Unimined/Minecraft] Added dependency ${library.name}")
                if (!(mcPatcher as AbstractMinecraftTransformer).libraryFilter(library)) {
                    project.logger.info("[Unimined/Minecraft] Excluding dependency ${library.name} as it is filtered by the patcher")
                    continue
                }
                val native = library.natives[OSUtils.oSId]
                if (library.url != null || library.downloads?.artifact != null || native == null) {
                    val dep = project.dependencies.create(library.name)
                    minecraftLibraries.dependencies.add(dep)
                    library.extract?.let { extractDependencies[dep] = it }
                }
                if (native != null) {
                    project.logger.info("[Unimined/Minecraft] Added native dependency ${library.name}:$native")
                    val nativeDep = project.dependencies.create("${library.name}:$native")
                    minecraftLibraries.dependencies.add(nativeDep)
                    library.extract?.let { extractDependencies[nativeDep] = it }
                }
            }
        }
    }

    private fun clientRun() {
        project.logger.info("[Unimined/Minecraft] client config")
        runs.addTarget(provideVanillaRunClientTask("client", project.file("run/client")))
        runs.configFirst("client", (mcPatcher as AbstractMinecraftTransformer)::applyClientRunTransform)
    }

    private fun serverRun() {
        project.logger.info("[Unimined/Minecraft] server config")
        runs.addTarget(provideVanillaRunServerTask("server", project.file("run/server")))
        runs.configFirst("server", (mcPatcher as AbstractMinecraftTransformer)::applyServerRunTransform)
    }

    fun applyRunConfigs() {
        project.logger.lifecycle("[Unimined/Minecraft] Applying run configs")
        when (side) {
            EnvType.CLIENT -> {
                clientRun()
            }
            EnvType.SERVER -> {
                serverRun()
            }
            EnvType.COMBINED -> {
                clientRun()
                serverRun()
            }
            else -> {
            }
        }
    }

    fun apply() {
        if (applied) return
        applied = true
        project.logger.lifecycle("[Unimined/Minecraft] Applying minecraft config for $sourceSet")

        lateActionsRunning = true

        while (patcherActions.isNotEmpty()) {
            patcherActions.removeFirst().invoke()
        }

        project.logger.info("[Unimined/MappingProvider] before mappings $sourceSet")
        (mcPatcher as AbstractMinecraftTransformer).beforeMappingsResolve()

        // finalize mapping deps
        project.logger.info("[Unimined/MappingProvider] $sourceSet mappings: ${mappings.getNamespaces()}")

        // late actions done

        // ensure minecraft deps are clear
        if (minecraft.dependencies.isNotEmpty()) {
            project.logger.warn("[Unimined/Minecraft] $minecraft dependencies are not empty! clearing...")
            minecraft.dependencies.clear()
        }
        if (minecraftLibraries.dependencies.isNotEmpty()) {
            project.logger.warn("[Unimined/Minecraft] $minecraftLibraries dependencies are not empty! clearing...")
            minecraftLibraries.dependencies.clear()
        }

        if (minecraftRemapper.replaceJSRWithJetbrains) {
            // inject jetbrains annotations into minecraftLibraries
            minecraftLibraries.dependencies.add(project.dependencies.create("org.jetbrains:annotations:24.0.1"))
        } else {
            // findbugs
            minecraftLibraries.dependencies.add(project.dependencies.create("com.google.code.findbugs:jsr305:3.0.2"))
        }

        // add minecraft dep
        minecraft.dependencies.add(minecraftDependency)

        //DEBUG: add minecraft dev dep as file
//        minecraft.dependencies.add(project.dependencies.create(project.files(minecraftFileDev)))

        // add minecraft libraries
        addLibraries(minecraftData.metadata.libraries)

        // create remapjar task
        if (defaultRemapJar) {
            val task = project.tasks.findByName("jar".withSourceSet(sourceSet))
            if (task != null && task is Jar) {
                val classifier: String= task.archiveClassifier.getOrElse("")
                task.apply {
                    if (classifier.isNotEmpty()) {
                        archiveClassifier.set(archiveClassifier.get() + "-dev")
                    } else {
                        archiveClassifier.set("dev")
                    }
                }
                remap(task) {
                    group = "unimined"
                    description = "Remaps $task's output jar"
                    archiveClassifier.set(classifier)
                }
                project.tasks.getByName("build").dependsOn("remap" + "jar".withSourceSet(sourceSet).capitalized())
            } else {
                project.logger.warn(
                    "[Unimined/Minecraft] Could not find default jar task for $sourceSet. named: ${
                        "jar".withSourceSet(
                            sourceSet
                        )
                    }."
                )
                project.logger.warn("[Unimined/Minecraft] add manually with `remap(task)` in the minecraft block for $sourceSet")
            }
        }

        // apply minecraft patcher changes
        project.logger.lifecycle("[Unimined/Minecraft] Applying ${mcPatcher.name()}")
        (mcPatcher as AbstractMinecraftTransformer).apply()

        // create run configs
        applyRunConfigs()
        (mcPatcher as AbstractMinecraftTransformer).applyExtraLaunches()

        // finalize run configs
        runs.apply()

        // add gen sources task
        project.tasks.register("genSources".withSourceSet(sourceSet), GenSourcesTaskImpl::class.java, this).configure(consumerApply {
            group = "unimined"
            description = "Generates sources for $sourceSet's minecraft jar"
        })

        // add export mappings task
        project.tasks.create("exportMappings".withSourceSet(sourceSet), ExportMappingsTaskImpl::class.java, this.mappings).apply {
            group = "unimined"
            description = "Exports mappings for $sourceSet's minecraft jar"
        }
    }

    fun afterEvaluate() {
        if (!applied) throw IllegalStateException("minecraft config never applied for $sourceSet")

        project.logger.info("[Unimined/MinecraftProvider] minecraft file: $minecraftFileDev")

        // remap mods
        mods.afterEvaluate()

        // run patcher after evaluate
        (mcPatcher as AbstractMinecraftTransformer).afterEvaluate()
    }

    override val minecraftFileDev: File by lazy {
        project.logger.info("[Unimined/Minecraft] Providing minecraft dev file to $sourceSet")
        getMinecraft(mappings.devNamespace, mappings.devFallbackNamespace).toFile().also {
            project.logger.info("[Unimined/Minecraft] Provided minecraft dev file $it")
        }
    }

//    val minecraftFilePom: File by lazy {
//        project.logger.info("[Unimined/Minecraft] Providing minecraft pom file to $sourceSet")
//        // create pom default stub
//        val xml = XMLBuilder("project")
//            .append(
//                XMLBuilder("modelVersion", true, true).append("4.0.0"),
//                XMLBuilder("groupId", true, true).append("net.minecraft"),
//                XMLBuilder("artifactId", true, true).append(minecraftDepName),
//                XMLBuilder("version", true, true).append(version),
//                XMLBuilder("packaging", true, true).append("jar"),
//            )
//        // write pom file
//        val pomFile = localCache.resolve("poms").resolve("$minecraftDepName-$version.pom")
//        pomFile.parent.createDirectories()
//        pomFile.writeText(xml.toString())
//        pomFile.toFile()
//    }

    override fun isMinecraftJar(path: Path) =
        minecraftFiles.values.any { it == path } ||
            when (side) {
                EnvType.COMBINED -> {
                    path == minecraftData.minecraftClientFile.toPath() ||
                    path == minecraftData.minecraftServerFile.toPath() ||
                    path == mergedOfficialMinecraftFile?.toPath()
                }
                EnvType.CLIENT -> {
                    path == minecraftData.minecraftClientFile.toPath()
                }
                EnvType.SERVER, EnvType.DATAGEN -> {
                    path == minecraftData.minecraftServerFile.toPath()
                }
            }



    @ApiStatus.Internal
    fun provideVanillaRunClientTask(name: String, workingDirectory: File): RunConfig {
        val nativeDir = workingDirectory.resolve("natives")

        val preRunClient = project.tasks.create("preRun${name.capitalized()}".withSourceSet(sourceSet), consumerApply {
            group = "unimined_internal"
            description = "Prepares the run configuration for $name by extracting natives and downloading assets"
            doLast {
                if (nativeDir.exists()) {
                    nativeDir.deleteRecursively()
                }
                nativeDir.mkdirs()
                extractDependencies.forEach { (dep, extract) ->
                    minecraftData.extract(dep, extract, nativeDir.toPath())
                }
                minecraftData.metadata.assetIndex?.let {
                    AssetsDownloader.downloadAssets(project, it)
                }
            }
        })

        val infoFile = minecraftData.mcVersionFolder
            .resolve("${version}.info")
        if (!infoFile.exists()) {
            if (!project.gradle.startParameter.isOffline) {
                //test if betacraft has our version on file
                val url = URI.create(
                    "http://files.betacraft.uk/launcher/assets/jsons/${
                        URLEncoder.encode(
                            minecraftData.metadata.id,
                            StandardCharsets.UTF_8.name()
                        )
                    }.info"
                )
                    .toURL()
                    .openConnection() as HttpURLConnection
                url.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)")
                url.requestMethod = "GET"
                url.connect()
                if (url.responseCode == 200) {
                    infoFile.writeBytes(
                        url.inputStream.readBytes(),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE
                    )
                }
            }
        }

        val properties = Properties()
        val betacraftArgs = if (infoFile.exists()) {
            infoFile.inputStream().use { properties.load(it) }
            properties.getProperty("proxy-args")?.split(" ") ?: listOf()
        } else {
            listOf()
        }

        val assetsDir = AssetsDownloader.assetsDir(project)

        return RunConfig(
            project,
            minecraftData.metadata.javaVersion,
            name,
            "run${name.capitalized()}",
            "Minecraft Client",
            sourceSet,
            minecraftData.metadata.mainClass,
            minecraftData.metadata.getGameArgs(
                "Dev",
                workingDirectory.toPath(),
                assetsDir
            ),
            (minecraftData.metadata.getJVMArgs(
                workingDirectory.resolve("libraries").toPath(),
                nativeDir.toPath()
            ) + betacraftArgs).toMutableList(),
            workingDirectory,
            mutableMapOf(),
            mutableListOf(preRunClient)
        )
    }

    @ApiStatus.Internal
    fun provideVanillaRunServerTask(name: String, workingDirectory: File): RunConfig {
        var mainClass: String? = null
        minecraftData.minecraftServer.path.openZipFileSystem().use {
            val properties = Properties()
            val metainf = it.getPath("META-INF/MANIFEST.MF")
            if (metainf.exists()) {
                metainf.inputStream().use { properties.load(it) }
                mainClass = properties.getProperty("Main-Class")
            }
        }

        return RunConfig(
            project,
            minecraftData.metadata.javaVersion,
            name,
            "run${name.capitalized()}",
            "Minecraft Server",
            sourceSet,
            mainClass ?: throw IllegalStateException("Could not find main class for server"),
            mutableListOf("nogui"),
            mutableListOf(),
            workingDirectory,
            mutableMapOf()
        )
    }
}