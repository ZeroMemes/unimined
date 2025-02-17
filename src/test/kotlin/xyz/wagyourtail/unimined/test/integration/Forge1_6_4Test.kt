package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.util.runTestProject

class Forge1_6_4Test {
    @Test
    fun test_forge_1_6_4() {
        val result = runTestProject("1.6.4-Forge")
        result.task(":build")?.outcome?.let {
            if (it != TaskOutcome.SUCCESS) throw Exception("build failed")
        } ?: throw Exception("build failed")
    }
}