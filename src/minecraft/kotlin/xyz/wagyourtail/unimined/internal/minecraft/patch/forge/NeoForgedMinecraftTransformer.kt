package xyz.wagyourtail.unimined.internal.minecraft.patch.forge

import com.google.gson.JsonObject
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.api.minecraft.patch.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3.FG3MinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.internal.minecraft.resolver.parseAllLibraries
import xyz.wagyourtail.unimined.util.FinalizeOnWrite
import xyz.wagyourtail.unimined.util.MustSet

class NeoForgedMinecraftTransformer(project: Project, provider: MinecraftProvider) : ForgeLikeMinecraftTransformer(project, provider, "NeoForged"), NeoForgedPatcher {

    override var forgeTransformer: JarModMinecraftTransformer by FinalizeOnWrite(MustSet())

    override fun addMavens() {
        project.unimined.neoForgedMaven()
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        forge.dependencies.add(if (dep is String && !dep.contains(":")) {
            project.dependencies.create("net.neoforged:forge:${provider.version}-$dep:universal")
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

        if (forgeDep.group != "net.neoforged" || forgeDep.name != "forge") {
            throw IllegalStateException("Invalid forge dependency found, if you are using multiple dependencies in the forge configuration, make sure the last one is the forge dependency!")
        }

        // only fg3 so far. no custom one yet
        forgeTransformer = FG3MinecraftTransformer(project, this)
    }

    override fun parseVersionJson(json: JsonObject) {
        val libraries = parseAllLibraries(json.getAsJsonArray("libraries"))
        mainClass = json.get("mainClass").asString
        val args = json.get("minecraftArguments").asString
        provider.addLibraries(libraries.filter { !it.name.startsWith("net.neoforged:forge:") })
        tweakClassClient = args.split("--tweakClass")[1].trim()
    }

}
