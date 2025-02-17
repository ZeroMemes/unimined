package xyz.wagyourtail.unimined.internal.minecraft.patch.fabric

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider

class OfficialFabricMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider
) : FabricMinecraftTransformer(project, provider) {

    override fun addIntermediaryMappings() {
        provider.mappings {
            intermediary()
        }
    }

    override fun loader(dep: Any, action: Dependency.() -> Unit) {
        fabric.dependencies.add(
            (if (dep is String && !dep.contains(":")) {
                project.dependencies.create("net.fabricmc:fabric-loader:$dep")
            } else project.dependencies.create(dep)).apply(action)
        )
    }
}