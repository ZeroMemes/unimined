# Unimined

unified minecraft modding environment.

## TODO for initial release
* ~~remap fg2+ era at's back to notch (fixing mc 1.7+)~~
* ~~remap user at's to notch~~
* ~~auto disable combined on <=1.2.5~~
* ~~figure out, why modloader not launching in dev due to classpath path instead of jar path~~

## TODO
* Refactor, refactor, refactor
* FG3+ support (>1.12.2)
* test user AT support
* fix fg3 versions of 1.12.2
* fabric aw support
* combined jar support
* figure out how to get forge to recognise resources as part of the dev mod
* split fg2+ out of the mc jar
* figure out how to do automated testing
  * figure out how to determine the correctness of remap output
  * aka automate the verification that versions work
    * list of versions to verify
      * 1.12.2
      * 1.7.10
      * 1.6.4
      * 1.5.2
      * 1.2.5
      * 1.7.3
      * b1.3_01
  * maybe by hash check?
* figure out what versions need `-Djava.util.Arrays.useLegacyMergeSort=true` to not randomly crash, this should really be part of the legacy mc version.json, or at least betacraft's, but it's not
* make myself a maven to host this on

## Example Usage
```groovy
plugins {
    id 'java'
    id 'xyz.wagyourtail.unimined' // I'm using it from buildSrc, so I don't need a version, you probably do
}

group 'com.example'
version '1.0-SNAPSHOT'

repositories {
    mavenCentral()
}

unimined {
    // debug, puts some things in build/unimined instead of ~/.gradle/caches/unimined
    // I reccomend you leave this on until unimined is stable
    useGlobalCache = false
}

minecraft {
    // current available options are: forge, jarMod, fabric
    // if you don't include this, it will default to no mod loader transforms
    forge {
        // required for 1.7+
        it.mcpVersion = '39-1.12'
        it.mcpChannel = 'stable'
        
        // untested
        it.accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')
    }
    // required when using mcp mappings
    mcRemapper.fallbackTarget = "searge"

    mcRemapper.tinyRemapperConf = {
        // most mcp mappings (except older format) dont include field desc
        it.ignoreFieldDesc(true)
        // this also fixes some issues with them, as it tells tiny remapper to try harder to resolve conflicts
        it.ignoreConflicts(true)
    }
}

mappings {
    // ability to add custom mappings
  // available targets are "CLIENT", "SERVER", "COMBINED"
    getStub("CLIENT").withMappings(["searge", "named"]) {
      c("ModLoader", "ModLoader", "modloader/ModLoader")
      c("BaseMod", "BaseMod", "modloader/BaseMod")
    }
}

sourceSets {
    // enable the client configuration when not using combined (or mc <= 1.2.5)
    client
}

dependencies {
    minecraft 'net.minecraft:minecraft:1.12.2'
    
    // this version is actually broken btw, on the post initial release roadmap to fix
    // if you need up to forge 1.12.2, use the latest FG2 version (2847 iirc)
    forge 'net.minecraftforge:forge:1.12.2-14.23.5.2860'
    
    // mappings "mappinggroup:mappingname:version"
}
```

## B1.3_01 example
```groovy
plugins {
    id 'java'
    id 'xyz.wagyourtail.unimined'
}

group 'xyz.wagyourtail'
version '1.0-SNAPSHOT'

repositories {
    mavenCentral()
    flatDir {
        dirs 'libs'
    }
}

unimined {
    useGlobalCache = false
}

minecraft {
    jarMod()
    mcRemapper.fallbackTarget = "searge"
    mcRemapper.tinyRemapperConf = {
        it.ignoreFieldDesc(true)
        it.ignoreConflicts(true)
    }
}

mappings {
    getStub("CLIENT").withMappings(["searge", "named"]) {
        c("ModLoader", "ModLoader", "modloader/ModLoader")
        c("BaseMod", "BaseMod", "modloader/BaseMod")
    }
}

sourceSets {
    client
}

dependencies {
    minecraft 'net.minecraft:minecraft:b1.3_01'

    jarMod 'local_mod:ModLoader:B1.3_01v5@zip'
    mappings 'local_mod:mcp:29a@zip'

    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.8.1'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.8.1'
}

test {
    useJUnitPlatform()
}
```