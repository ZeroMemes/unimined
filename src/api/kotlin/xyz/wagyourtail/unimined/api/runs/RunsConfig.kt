package xyz.wagyourtail.unimined.api.runs

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.util.FinalizeOnRead

abstract class RunsConfig {
    /**
     * just a flag to disable all.
     */
    var off: Boolean by FinalizeOnRead(false)

    fun config(
        config: String,
        @DelegatesTo(
            value = RunConfig::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        config(config) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    abstract fun config(
        config: String,
        action: RunConfig.() -> Unit
    )

    @Deprecated("use setConfig instead", ReplaceWith("setConfig(\"client\", action)"))
    fun setClient(
        @DelegatesTo(
            value = RunConfig::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        config("client", action)
    }

    @Deprecated("use setConfig instead", ReplaceWith("setConfig(\"client\", action)"))
    fun setClient(action: RunConfig.() -> Unit) {
        config("client", action)
    }

    @Deprecated("use setConfig instead", ReplaceWith("setConfig(\"server\", action)"))
    fun setServer(
        @DelegatesTo(
            value = RunConfig::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        config("server", action)
    }

    @Deprecated("use setConfig instead", ReplaceWith("setConfig(\"server\", action)"))
    fun setServer(action: RunConfig.() -> Unit) {
        config("server", action)
    }

    @ApiStatus.Internal
    abstract fun addTarget(config: RunConfig)

    abstract fun configFirst(config: String, action: RunConfig.() -> Unit)
}