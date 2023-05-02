package xyz.wagyourtail.unimined.api.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Project
import org.gradle.api.Task
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mappings.MappingsConfig
import xyz.wagyourtail.unimined.api.tasks.RemapJarTask

/**
 * @since 1.0.0
 */
abstract class MinecraftConfig(val project: Project) : PatchProviders {

    @get:ApiStatus.Internal
    @set:ApiStatus.Internal
    var side = EnvType.COMBINED

    fun side(sideConf: String) {
        side = EnvType.valueOf(sideConf.uppercase())
    }

    abstract val mappings: MappingsConfig

    fun mappings(action: MappingsConfig.() -> Unit) {
        mappings.action()
    }

    fun mappings(
        @DelegatesTo(value = MappingsConfig::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        mappings {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun remap(task: Task)

    abstract fun remap(task: Task, action: RemapJarTask.() -> Unit)

    fun remap(
        task: Task,
        @DelegatesTo(value = RemapJarTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(task) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun remap(task: Task, name: String)

    abstract fun remap(task: Task, name: String, action: RemapJarTask.() -> Unit)

    fun remap(
        task: Task,
        name: String,
        @DelegatesTo(value = RemapJarTask::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        remap(task, name) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }
}