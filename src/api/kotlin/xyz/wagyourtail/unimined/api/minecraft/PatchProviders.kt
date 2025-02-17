package xyz.wagyourtail.unimined.api.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.*

/**
 * usage:
 *
 *
 * @since 0.4.10
 */
interface PatchProviders {

    /**
     * enables the fabric patcher.
     * @param action the action to configure the patcher.
     * @see FabricLikePatcher
     * @since 0.1.0
     */
    fun fabric(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the fabric patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    fun fabric(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        fabric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher.
     * @since 0.1.0
     */
    fun fabric() {
        fabric {}
    }

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @param action the action to perform on the patcher.
     * @see FabricLikePatcher
     * @since 0.4.2
     */
    fun legacyFabric(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @param action the action to perform on the patcher.
     * @since 0.4.2
     */
    fun legacyFabric(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        legacyFabric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @since 0.4.2
     */
    fun legacyFabric() {
        legacyFabric {}
    }

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @param action the action to perform on the patcher.
     * @since 1.0.0
     */
    fun babric(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @param action the action to perform on the patcher.
     * @since 1.0.0
     */
    fun babric(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        babric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @since 1.0.0
     */
    fun babric() {
        babric {}
    }

    /**
     * enables the quilt patcher.
     * @param action the action to configure the patcher.
     * @see FabricLikePatcher
     * @since 0.3.4
     */
    fun quilt(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the quilt patcher.
     * @param action the action to perform on the patcher.
     * @since 0.3.4
     */
    fun quilt(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        quilt {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the quilt patcher.
     * @since 0.3.4
     * @since 0.3.4
     */
    fun quilt() {
        quilt {}
    }

    /**
     * enables the forge patcher.
     * @param action the action to configure the patcher.
     * @see ForgeLikePatcher
     * @since 0.1.0
     */
    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge(action)"
        )
    )
    fun forge(action: ForgeLikePatcher.() -> Unit)

    /**
     * enables the forge patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge(action)"
        )
    )
    fun forge(
        @DelegatesTo(
            value = ForgeLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        @Suppress("DEPRECATION")
        forge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the forge patcher.
     * @since 0.1.0
     */

    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge()"
        )
    )
    fun forge() {
        @Suppress("DEPRECATION")
        forge {}
    }

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge(action: MinecraftForgePatcher.() -> Unit)

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge(
        @DelegatesTo(
            value = MinecraftForgePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        minecraftForge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge() {
        minecraftForge {}
    }

    /**
     * enables the NeoForged patcher.
     * @since 1.0.0
     */
    fun neoForged(action: NeoForgedPatcher.() -> Unit)

    /**
     * enables the NeoForged patcher.
     * @since 1.0.0
     */

    fun neoForged(
        @DelegatesTo(
            value = NeoForgedPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        neoForged {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the NeoForged patcher.
     * @since 1.0.0
     */
    fun neoForged() {
        neoForged {}
    }

    /**
     * enables the jar mod patcher.
     * @param action the action to configure the patcher.
     * @see JarModAgentPatcher
     * @since 0.1.0
     */
    fun jarMod(action: JarModAgentPatcher.() -> Unit)

    /**
     * enables the jar mod patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    fun jarMod(
        @DelegatesTo(
            value = JarModAgentPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        jarMod {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the jar mod patcher.
     * @since 0.1.0
     */
    fun jarMod() {
        jarMod {}
    }

    @ApiStatus.Experimental
    fun <T: MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit)
}