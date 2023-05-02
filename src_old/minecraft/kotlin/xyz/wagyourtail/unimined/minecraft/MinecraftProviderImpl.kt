package xyz.wagyourtail.unimined.minecraft

import net.fabricmc.mappingio.format.ZipReader
import net.minecraftforge.artifactural.api.artifact.Artifact
import net.minecraftforge.artifactural.api.artifact.ArtifactIdentifier
import net.minecraftforge.artifactural.api.artifact.ArtifactType
import net.minecraftforge.artifactural.api.repository.ArtifactProvider
import net.minecraftforge.artifactural.api.repository.Repository
import net.minecraftforge.artifactural.base.artifact.StreamableArtifact
import net.minecraftforge.artifactural.base.repository.ArtifactProviderBuilder
import net.minecraftforge.artifactural.base.repository.SimpleRepository
import net.minecraftforge.artifactural.gradle.GradleRepositoryAdapter
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.Constants
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.launch.LaunchConfig
import xyz.wagyourtail.unimined.api.launch.LauncherProvider
import xyz.wagyourtail.unimined.api.mappings.MappingNamespace
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.ForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.MergedPatcher
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.JarModPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.JarModAgentPatcher
import xyz.wagyourtail.unimined.launch.LauncherProvierImpl
import xyz.wagyourtail.unimined.minecraft.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.MergedMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.minecraft.patch.NoTransformMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.fabric.FabricLikeMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.fabric.LegacyFabricMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.fabric.OfficialFabricMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.fabric.QuiltMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.forge.ForgeMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.jarmod.JarModAgentMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.remap.MinecraftRemapperImpl
import xyz.wagyourtail.unimined.minecraft.resolve.AssetsDownloader
import xyz.wagyourtail.unimined.minecraft.resolve.Extract
import xyz.wagyourtail.unimined.minecraft.resolve.Library
import xyz.wagyourtail.unimined.minecraft.resolve.MinecraftDownloader
import xyz.wagyourtail.unimined.util.ChangeOnce
import xyz.wagyourtail.unimined.util.OSUtils
import xyz.wagyourtail.unimined.util.consumerApply
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

@Suppress("LeakingThis")
abstract class MinecraftProviderImpl(
    project: Project,
    unimined: UniminedExtension
): MinecraftProvider<MinecraftRemapperImpl, AbstractMinecraftTransformer>(project),
        ArtifactProvider<ArtifactIdentifier> {

    @ApiStatus.Internal
    val combined: Configuration = project.configurations.maybeCreate(Constants.MINECRAFT_COMBINED_PROVIDER)

    @ApiStatus.Internal
    val client: Configuration = project.configurations.maybeCreate(Constants.MINECRAFT_CLIENT_PROVIDER)

    @ApiStatus.Internal
    val server: Configuration = project.configurations.maybeCreate(Constants.MINECRAFT_SERVER_PROVIDER)

    @ApiStatus.Internal
    override val mcLibraries: Configuration = project.configurations.maybeCreate(Constants.MINECRAFT_LIBRARIES_PROVIDER)
        .apply {
            isTransitive = true
        }

    @ApiStatus.Internal
    override val minecraft: MinecraftDownloader = MinecraftDownloader(project, this)

    @ApiStatus.Internal
    val assetsDownloader: AssetsDownloader = AssetsDownloader(
        project
    )

    override val launcher: LauncherProvider = LauncherProvierImpl(project, unimined)

    @ApiStatus.Internal
    private val repo: Repository = SimpleRepository.of(
        ArtifactProviderBuilder.begin(ArtifactIdentifier::class.java)
            .filter(
                ArtifactIdentifier.groupEquals(Constants.MINECRAFT_GROUP)
            )
            .provide(this)
    )

    override val mcRemapper = MinecraftRemapperImpl(project, this)

    /**
     * patcher for the minecraft jar.
     * please use [jarMod], [forge], or [fabric] instead.
     */
    @get:ApiStatus.Internal
    override var mcPatcher: AbstractMinecraftTransformer by ChangeOnce(NoTransformMinecraftTransformer(project, this))

    init {
        project.repositories.maven {
            it.url = URI.create(Constants.MINECRAFT_MAVEN)
        }

        GradleRepositoryAdapter.add(
            project.repositories,
            "minecraft-transformer",
            unimined.getLocalCache().toFile(),
            repo
        )
        unimined.events.register(::afterEvaluate)
        unimined.events.register(::sourceSets)
    }


    private fun getMinecraftDepName(): String {
        val projectPath = project.path.replace(":", "_")
        return "minecraft${if (projectPath == "_") "" else projectPath}"
    }

    private fun afterEvaluate() {
        val dep = minecraft.dependency
        val urlEncodeVersion = URLEncoder.encode(dep.version, StandardCharsets.UTF_8.name())

        combined.dependencies.clear()
        val newDep = project.dependencies.create(
            "net.minecraft:${getMinecraftDepName()}:${urlEncodeVersion}"
        )
        combined.dependencies.add(newDep)
        minecraft.dependency = newDep

        minecraft.afterEvaluate()
        addMcLibraries()
        mcPatcher.afterEvaluate()
        project.unimined.events.register(mcPatcher::sourceSets)
        mcPatcher.applyLaunches()

        // resolve mc libraries early.
        mcLibraries.resolve()

        if (minecraft.client) {
            launcher.addTarget(provideVanillaRunClientTask())
        }
        if (minecraft.server) {
            launcher.addTarget(provideVanillaRunServerTask())
        }
    }

    override fun fabric(action: (FabricLikePatcher) -> Unit) {
        if (mcPatcher !is OfficialFabricMinecraftTransformer) {
            mcPatcher = OfficialFabricMinecraftTransformer(project, this)
        }
        action(mcPatcher as FabricLikeMinecraftTransformer)
    }

    override fun legacyFabric(action: (FabricLikePatcher) -> Unit) {
        if (mcPatcher !is LegacyFabricMinecraftTransformer) {
            mcPatcher = LegacyFabricMinecraftTransformer(project, this)
        }
        action(mcPatcher as FabricLikeMinecraftTransformer)
    }

    override fun quilt(action: (FabricLikePatcher) -> Unit) {
        if (mcPatcher !is QuiltMinecraftTransformer) {
            mcPatcher = QuiltMinecraftTransformer(project, this)
        }
        action(mcPatcher as FabricLikeMinecraftTransformer)
    }

    override fun jarMod(action: (JarModAgentPatcher) -> Unit) {
        if (mcPatcher !is JarModAgentMinecraftTransformer) {
            mcPatcher = JarModAgentMinecraftTransformer(project, this)
        }
        action(mcPatcher as JarModAgentMinecraftTransformer)
    }

    override fun forge(action: (ForgePatcher) -> Unit) {
        if (mcPatcher !is ForgeMinecraftTransformer) {
            mcPatcher = ForgeMinecraftTransformer(project, this)
        }
        action(mcPatcher as ForgeMinecraftTransformer)
    }

    override fun merged(action: (MergedPatcher) -> Unit) {
        mcPatcher = MergedMinecraftTransformer(project, this)
        action(mcPatcher as MergedMinecraftTransformer)
        if ((mcPatcher as MergedMinecraftTransformer).patchers.isEmpty()) {
            throw IllegalStateException("MergedPatcher must have at least one patcher")
        }
    }

    private fun sourceSets(@Suppress("UNUSED_PARAMETER") sourceSets: SourceSetContainer) {

        for (sourceSet in clientSourceSets) {
            sourceSet.compileClasspath += client + mcLibraries
            sourceSet.runtimeClasspath += client + mcLibraries
            sourceSet.runtimeClasspath += project.files(sourceSet.output.resourcesDir)
        }

        for (sourceSet in serverSourceSets) {
            sourceSet.compileClasspath += server + mcLibraries
            sourceSet.runtimeClasspath += server + mcLibraries
            sourceSet.runtimeClasspath += project.files(sourceSet.output.resourcesDir)
        }

        for (sourceSet in combinedSourceSets) {
            sourceSet.compileClasspath += combined + mcLibraries
            sourceSet.runtimeClasspath += combined + mcLibraries
            sourceSet.runtimeClasspath += project.files(sourceSet.output.resourcesDir)
        }

        assert(clientSourceSets.none { serverSourceSets.contains(it) } &&
                combinedSourceSets.none { serverSourceSets.contains(it) } &&
                combinedSourceSets.none { clientSourceSets.contains(it) }
        ) {
            """
            |Can only provide one version of minecraft to each sourceSet.
            |client: $clientSourceSets
            |server: $serverSourceSets
            |combined: $combinedSourceSets
            """.trimMargin()
        }

    }

    private var extractDependencies: MutableMap<Dependency, Extract> = mutableMapOf()

    private fun addMcLibraries() {
        addMcLibraries(minecraft.metadata.libraries)

        // add jetbrains annotations
        mcLibraries.dependencies.add(
            project.dependencies.create(
                "org.jetbrains:annotations:24.0.0"
            )
        )
    }

    @ApiStatus.Internal
    fun addMcLibraries(libs: List<Library>) {
        for (library in libs) {
            if (library.rules.all { it.testRule() }) {
                project.logger.info("Added dependency ${library.name}")
                val native = library.natives[OSUtils.oSId]
                if (library.url != null || library.downloads?.artifact != null || native == null) {
                    val dep = project.dependencies.create(library.name)
                    mcLibraries.dependencies.add(dep)
                    library.extract?.let { extractDependencies[dep] = it }
                }
                if (native != null) {
                    project.logger.debug("Added native dependency ${library.name}:$native")
                    val nativeDep = project.dependencies.create("${library.name}:$native")
                    mcLibraries.dependencies.add(nativeDep)
                    library.extract?.let { extractDependencies[nativeDep] = it }
                }
            }
        }
    }

    private val minecraftMapped = mutableMapOf<EnvType, MutableMap<Pair<MappingNamespace, MappingNamespace>, Path>>()

    override fun isMinecraftJar(path: Path): Boolean {
        return minecraftMapped.values.any { it.values.contains(path) }
    }

    @ApiStatus.Internal
    override fun getMinecraftWithMapping(
        envType: EnvType,
        namespace: MappingNamespace,
        fallbackNamespace: MappingNamespace
    ): Path {
        project.logger.lifecycle("Getting minecraft with mapping $envType:$namespace")
        return minecraftMapped.computeIfAbsent(envType) {
            mutableMapOf()
        }.computeIfAbsent(namespace to fallbackNamespace) {
            val mc = if (envType == EnvType.COMBINED) {
                val client = MinecraftJar(
                    minecraft.mcVersionFolder(minecraft.version),
                    "minecraft",
                    EnvType.CLIENT,
                    minecraft.version,
                    listOf(),
                    MappingNamespace.OFFICIAL,
                    MappingNamespace.OFFICIAL,
                    null,
                    "jar",
                    minecraft.getMinecraft(EnvType.CLIENT)
                )
                val server = MinecraftJar(
                    minecraft.mcVersionFolder(minecraft.version),
                    "minecraft",
                    EnvType.SERVER,
                    minecraft.version,
                    listOf(),
                    MappingNamespace.OFFICIAL,
                    MappingNamespace.OFFICIAL,
                    null,
                    "jar",
                    minecraft.getMinecraft(EnvType.SERVER)
                )
                mcPatcher.merge(
                    mcRemapper.provide(client, mergeNamespace, mergeNamespace),
                    mcRemapper.provide(server, mergeNamespace, mergeNamespace)
                )
            } else {
                MinecraftJar(
                    minecraft.mcVersionFolder(minecraft.version),
                    "minecraft",
                    envType,
                    minecraft.version,
                    listOf(),
                    MappingNamespace.OFFICIAL,
                    MappingNamespace.OFFICIAL,
                    null,
                    "jar",
                    minecraft.getMinecraft(envType)
                )
            }
            mcPatcher.afterRemap(
                mcRemapper.provide(mcPatcher.transform(mc), namespace, fallbackNamespace)
            ).path
        }
    }

    fun getMinecraftConfig(env: EnvType): Configuration = when (env) {
        EnvType.CLIENT -> {
            client
        }

        EnvType.SERVER -> {
            server
        }

        EnvType.COMBINED -> {
            combined
        }
    }

    @ApiStatus.Internal
    override fun getArtifact(info: ArtifactIdentifier): Artifact {

        if (info.group != Constants.MINECRAFT_GROUP || info.group == Constants.MINECRAFT_FORGE_GROUP) {
            return Artifact.none()
        }

        if (info.name != "minecraft" && info.name != getMinecraftDepName()) {
            return Artifact.none()
        }

        try {
            return if (info.extension != "jar") {
                Artifact.none()
            } else {
                when (info.classifier) {
                    "client" -> {
                        val mc = getMinecraftWithMapping(
                            EnvType.CLIENT,
                            mcPatcher.devNamespace,
                            mcPatcher.devFallbackNamespace
                        )
                        project.logger.info("providing client minecraft jar at $mc")
                        StreamableArtifact.ofFile(
                            info,
                            ArtifactType.BINARY,
                            mc.toFile()
                        )
                    }

                    "server" -> {
                        val mc = getMinecraftWithMapping(
                            EnvType.SERVER,
                            mcPatcher.devNamespace,
                            mcPatcher.devFallbackNamespace
                        )
                        project.logger.info("providing server minecraft jar at $mc")
                        StreamableArtifact.ofFile(
                            info,
                            ArtifactType.BINARY,
                            mc.toFile()
                        )
                    }

                    "client-mappings" -> StreamableArtifact.ofFile(
                        info,
                        ArtifactType.BINARY,
                        minecraft.getMappings(EnvType.CLIENT).toFile()
                    )

                    "server-mappings" -> StreamableArtifact.ofFile(
                        info,
                        ArtifactType.BINARY,
                        minecraft.getMappings(EnvType.SERVER).toFile()
                    )

                    null -> {
                        if (disableCombined.get()) {
                            Artifact.none()
                        } else {
                            val mc = getMinecraftWithMapping(
                                EnvType.COMBINED,
                                mcPatcher.devNamespace,
                                mcPatcher.devFallbackNamespace
                            )
                            project.logger.info("providing combined minecraft jar at $mc")
                            StreamableArtifact.ofFile(
                                info,
                                ArtifactType.BINARY,
                                mc.toFile()
                            )
                        }
                    }

                    "sources" -> {
                        Artifact.none()
                    }

                    else -> throw IllegalArgumentException("Unknown classifier ${info.classifier}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    @ApiStatus.Internal
    fun provideVanillaRunClientTask(): LaunchConfig {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)

        val nativeDir = clientWorkingDirectory.get().resolve("natives")

        val preRunClient = project.tasks.create("preRunClient", consumerApply {
            doLast {
                if (nativeDir.exists()) {
                    nativeDir.deleteRecursively()
                }
                nativeDir.mkdirs()
                extractDependencies.forEach { (dep, extract) ->
                    minecraft.extract(dep, extract, nativeDir.toPath())
                }
                minecraft.metadata.assetIndex?.let {
                    assetsDownloader.downloadAssets(project, it)
                }
            }
        })

        val infoFile = minecraft.mcVersionFolder(minecraft.version)
            .resolve("${minecraft.version}.info")
        if (!infoFile.exists()) {
            if (!project.gradle.startParameter.isOffline) {
                //test if betacraft has our version on file
                val url = URI.create(
                    "http://files.betacraft.uk/launcher/assets/jsons/${
                        URLEncoder.encode(
                            minecraft.metadata.id,
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

        val assetsDir = assetsDownloader.assetsDir()

        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        return LaunchConfig(
            project,
            "client",
            "runClient",
            "Minecraft Client",
            clientSourceSets.firstOrNull() ?: combinedSourceSets.firstOrNull() ?: sourceSets.getByName("main"),
            minecraft.metadata.mainClass,
            minecraft.metadata.getGameArgs(
                "Dev",
                clientWorkingDirectory.get().toPath(),
                assetsDir
            ),
            (minecraft.metadata.getJVMArgs(
                clientWorkingDirectory.get().resolve("libraries").toPath(),
                nativeDir.toPath()
            ) + betacraftArgs).toMutableList(),
            clientWorkingDirectory.get(),
            mutableMapOf(),
            assetsDir,
            mutableListOf(preRunClient)
        )
    }

    @ApiStatus.Internal
    fun provideVanillaRunServerTask(): LaunchConfig {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)

        var mainClass: String? = null
        ZipReader.openZipFileSystem(minecraft.getMinecraft(EnvType.SERVER)).use {
            val properties = Properties()
            val metainf = it.getPath("META-INF/MANIFEST.MF");
            if (metainf.exists()) {
                metainf.inputStream().use { properties.load(it) }
                mainClass = properties.getProperty("Main-Class")
            }
        }

        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        return LaunchConfig(
            project,
            "server",
            "runServer",
            "Minecraft Server",
            serverSourceSets.firstOrNull() ?: combinedSourceSets.firstOrNull() ?: sourceSets.getByName("main"),
            mainClass ?: "unknown.main.class", // TODO: get from meta-inf, this is wrong
            mutableListOf("nogui"),
            mutableListOf(),
            project.projectDir.resolve("run").resolve("server"),
            mutableMapOf(),
            project.projectDir.resolve("run").resolve("server").toPath()
        )
    }

}