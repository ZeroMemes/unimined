package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.patch.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg1.FG1MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg2.FG2MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseAllLibraries
import xyz.wagyourtail.unimined.util.FinalizeOnWrite
import xyz.wagyourtail.unimined.util.MustSet
import xyz.wagyourtail.unimined.util.forEachInZip
import java.io.File

class MinecraftForgeMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "MinecraftForge"), MinecraftForgePatcher {

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnWrite(MustSet())

    override fun addMavens() {
        project.unimined.minecraftForgeMaven()
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        forge.dependencies.add(if (dep is String && !dep.contains(":")) {
            if (!canCombine) {
                if (provider.side == EnvType.COMBINED) throw IllegalStateException("Cannot use forge dependency pre 1.3 without specifying non-combined side")
                project.dependencies.create("net.minecraftforge:forge:${provider.version}-${dep}:${provider.side.classifier}@zip")
            } else {
                val zip = provider.minecraftData.mcVersionCompare(provider.version, "1.6") < 0
                project.dependencies.create("net.minecraftforge:forge:${provider.version}-${dep}:universal@${if (zip) "zip" else "jar"}")
            }
        } else {
            project.dependencies.create(dep)
        }.apply(action))

        if (forge.dependencies.isEmpty()) {
            throw IllegalStateException("No forge dependency found!")
        }

        if (forge.dependencies.size > 1) {
            throw IllegalStateException("Multiple forge dependencies found, make sure you only have one forge dependency!")
        }

        val forgeDep = forge.dependencies.first()

        if (forgeDep.group != "net.minecraftforge" || !(forgeDep.name == "minecraftforge" || forgeDep.name == "forge")) {
            throw IllegalStateException("Invalid forge dependency found, if you are using multiple dependencies in the forge configuration, make sure the last one is the forge dependency!")
        }

        forgeTransformer = if (provider.minecraftData.mcVersionCompare(provider.version, "1.3") < 0) {
            FG1MinecraftTransformer(project, this)
        } else {
            val jar = forge.files(forgeDep).first { it.extension == "zip" || it.extension == "jar" }
            determineForgeProviderFromUniversal(jar)
        }
    }

    private fun determineForgeProviderFromUniversal(universal: File): JarModMinecraftTransformer {
        val files = mutableSetOf<ForgeFiles>()
        universal.toPath().forEachInZip { path, _ ->
            if (ForgeFiles.ffMap.contains(path)) {
                files.add(ForgeFiles.ffMap[path]!!)
            }
        }

        var forgeTransformer: JarModMinecraftTransformer? = null
        for (vers in ForgeVersion.values()) {
            if (files.containsAll(vers.accept) && files.none { it in vers.deny }) {
                project.logger.debug("[Unimined/ForgeTransformer] Files $files")
                forgeTransformer = when (vers) {
                    ForgeVersion.FG1 -> {
                        project.logger.lifecycle("[Unimined/ForgeTransformer] Selected FG1")
                        FG1MinecraftTransformer(project, this)
                    }

                    ForgeVersion.FG2 -> {
                        project.logger.lifecycle("[Unimined/ForgeTransformer] Selected FG2")
                        FG2MinecraftTransformer(project, this)
                    }

                    ForgeVersion.FG3 -> {
                        project.logger.lifecycle("[Unimined/ForgeTransformer] Selected FG3")
                        FG3MinecraftTransformer(project, this)
                    }
                }
                break
            }
        }

        if (forgeTransformer == null) {
            throw IllegalStateException("Unable to determine forge version from universal jar!")
        }
        // TODO apply some additional properties at this time

        return forgeTransformer
    }

    override fun parseVersionJson(json: JsonObject) {
        val libraries = parseAllLibraries(json.getAsJsonArray("libraries"))
        mainClass = json.get("mainClass").asString
        val args = json.get("minecraftArguments").asString
        provider.addLibraries(libraries.filter {
            !it.name.startsWith("net.minecraftforge:minecraftforge:") && !it.name.startsWith(
                "net.minecraftforge:forge:"
            )
        })
        tweakClassClient = args.split("--tweakClass")[1].trim()
    }

}
