package xyz.wagyourtail.unimined.internal.mapping.extension.mixin

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import xyz.wagyourtail.unimined.util.forEachInZip
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import kotlin.io.path.writeText

class OfficialMixinMetaData(parent: MixinRemapExtension) : MixinRemapExtension.MixinMetadata(parent) {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val classesToRefmap = mutableMapOf<String, String>()
    private val refmaps = mutableMapOf<String, JsonObject>()
    private val existingRefmaps = mutableMapOf<String, JsonObject>()
    private val mixinJsons = mutableMapOf<String, JsonObject>()

    private fun mixinCheck(relativePath: String): Boolean =
        relativePath.contains("mixins") && relativePath.endsWith(".json")

    private fun refmapCheck(relativePath: String): Boolean =
        relativePath.contains("refmap") && relativePath.endsWith(".json")


    private fun refmapNameCalculator(relativePath: String): String {
        val split = relativePath.split("/")
        val name = split[split.size - 1].substringBefore(".json")
        return name + "-refmap.json"
    }


    override fun readInput(vararg input: Path): CompletableFuture<*> {
        val futures = mutableListOf<CompletableFuture<*>>()
        for (i in input) {
            futures.add(readSingleInput(i))
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    private fun readSingleInput(input: Path): CompletableFuture<*> {
        return CompletableFuture.runAsync {
            input.forEachInZip { file, stream ->
                if (refmapCheck(file)) {
                    try {
                        parent.logger.info("[PreRead] Found refmap: $file")
                        val json = JsonParser.parseReader(stream.reader()).asJsonObject
                        existingRefmaps[file] = json
                    } catch (e: Exception) {
                        parent.logger.error("[PreRead] Failed to parse refmap $file: ${e.message}")
                    }
                } else if (mixinCheck(file)) {
                    try {
                        parent.logger.info("[PreRead] Found mixin config: $file")
                        val json = JsonParser.parseReader(stream.reader()).asJsonObject
                        val refmap = json["refmap"]?.asString ?: refmapNameCalculator(file)
                        val pkg = json.get("package")?.asString
                        refmaps.computeIfAbsent(refmap) { JsonObject() }
                        val mixins = (json["mixins"]?.asJsonArray ?: listOf()) +
                                (json["client"]?.asJsonArray ?: listOf()) +
                                (json["server"]?.asJsonArray ?: listOf())

                        parent.logger.info("[PreRead] Found mixins: ${mixins.size}")
                        for (mixin in mixins) {
                            val mixinName = mixin.asString
                            if (classesToRefmap.containsKey("$pkg.$mixinName") && classesToRefmap["$pkg.$mixinName"] != refmap) {
                                parent.logger.warn("[PreRead] $pkg.$mixinName already has a refmap entry!")
                                parent.logger.warn("[PreRead] ${classesToRefmap["$pkg.$mixinName"]} != $refmap")
                                parent.logger.warn("[PreRead] Will only read/write to ${classesToRefmap["$pkg.$mixinName"]} for $pkg.$mixinName")
                                continue
                            }
                            classesToRefmap["$pkg.$mixinName"] = refmap
                            parent.logger.info("[PreRead] Added $pkg.$mixinName to $refmap")
                        }
                        json.addProperty("refmap", refmap)
                        mixinJsons[file] = json
                    } catch (e: Exception) {
                        parent.logger.error("[PreRead] Failed to parse mixin config $file: ${e.message}")
                    }
                }
            }
        }
    }

    override fun contains(className: String): Boolean {
        return classesToRefmap.containsKey(className)
    }

    override fun getRefmapFor(className: String): JsonObject {
        return refmaps[classesToRefmap[className]]!!
    }

    override fun getExistingRefmapFor(className: String): JsonObject? {
        return existingRefmaps[classesToRefmap[className]]
    }

    override fun writeExtra(fs: FileSystem) {
        for ((name, json) in refmaps) {
            parent.logger.info("[Unimined/MixinMetaData] Writing refmap $name")
            fs.getPath(name).writeText(GSON.toJson(json), Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
        for ((name, json) in mixinJsons) {
            parent.logger.info("[Unimined/MixinMetaData] Writing mixin config $name")
            fs.getPath(name).writeText(GSON.toJson(json), Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

}