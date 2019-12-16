package com.cognifide.gradle.aem.common.instance.service.groovy

import com.cognifide.gradle.aem.AemException
import com.cognifide.gradle.aem.common.instance.InstanceService
import com.cognifide.gradle.aem.common.instance.InstanceSync
import com.cognifide.gradle.aem.common.utils.Formats
import java.io.File

/**
 * Allows to execute Groovy code / scripts on AEM instance having Groovy Console CRX package installed.
 *
 * @see <https://github.com/icfnext/aem-groovy-console>
 */
class GroovyConsole(sync: InstanceSync) : InstanceService(sync) {

    /**
     * Controls throwing exception on script execution error.
     */
    var verbose: Boolean = aem.prop.boolean("instance.groovyConsole.verbose") ?: true

    /**
     * Directory to search for scripts to be evaluated.
     */
    var scriptRootDir: File = File(aem.configDir, "groovyScript")

    /**
     * Check if console is installed on instance.
     */
    val available: Boolean get() = sync.osgiFramework.findBundle(SYMBOLIC_NAME) != null

    /**
     * Ensure by throwing exception that console is available on instance.
     */
    fun requireAvailable() {
        if (!available) {
            throw GroovyConsoleException("Groovy console is not available on $instance!")
        }
    }

    /**
     * Evaluate Groovy code snippet on AEM instance.
     */
    fun evalCode(code: String, data: Map<String, Any> = mapOf()): GroovyConsoleResult {
        val result = try {
            aem.logger.info("Executing Groovy Code: $code")
            evalCodeInternal(code, data)
        } catch (e: AemException) {
            throw GroovyConsoleException("Cannot evaluate Groovy code properly on $instance, code:\n$code, cause: ${e.message}", e)
        }

        if (verbose && result.exceptionStackTrace.isNotBlank()) {
            aem.logger.debug(result.toString())
            throw GroovyConsoleException("Execution of Groovy code on $instance ended with exception:\n${result.exceptionStackTrace}")
        }

        return result
    }

    private fun evalCodeInternal(code: String, data: Map<String, Any>): GroovyConsoleResult {
        return sync.http.postMultipart(EVAL_PATH, mapOf(
                "script" to code,
                "data" to Formats.toJson(data)
        )) { asObjectFromJson(it, GroovyConsoleResult::class.java) }
    }

    /**
     * Evaluate any Groovy script on AEM instance.
     */
    fun evalScript(file: File, data: Map<String, Any> = mapOf()): GroovyConsoleResult {
        val result = try {
            aem.logger.info("Executing Groovy script: $file")
            evalCodeInternal(file.bufferedReader().use { it.readText() }, data)
        } catch (e: AemException) {
            throw GroovyConsoleException("Cannot evaluate Groovy script properly on $instance, file: $file, cause: ${e.message}", e)
        }

        if (verbose && result.exceptionStackTrace.isNotBlank()) {
            aem.logger.debug(result.toString())
            throw GroovyConsoleException("Execution of Groovy script $file on $instance ended with exception:\n${result.exceptionStackTrace}")
        }

        return result
    }

    /**
     * Evaluate Groovy script found by its file name on AEM instance.
     */
    fun evalScript(fileName: String, data: Map<String, Any> = mapOf()): GroovyConsoleResult {
        val script = File(scriptRootDir, fileName)
        if (!script.exists()) {
            throw GroovyConsoleException("Groovy script '$fileName' not found in directory: $scriptRootDir")
        }

        return evalScript(script, data)
    }

    /**
     * Find scripts matching file pattern in pre-configured directory.
     */
    fun findScripts(fileNamePattern: String): List<File> = project.fileTree(scriptRootDir)
            .matching { it.include(fileNamePattern) }
            .sortedBy { it.absolutePath }
            .ifEmpty<List<File>, List<File>> {
                throw GroovyConsoleException("No Groovy scripts found in directory: $scriptRootDir")
            }

    /**
     * Evaluate all Groovy scripts found by file name pattern on AEM instance in path-based alphabetical order.
     */
    fun evalScripts(fileNamePattern: String = "**/*.groovy", data: Map<String, Any> = mapOf(), resultConsumer: GroovyConsoleResult.() -> Unit = {}) {
        evalScripts(findScripts(fileNamePattern), data, resultConsumer)
    }

    /**
     * Evaluate any Groovy scripts on AEM instance in specified order.
     */
    fun evalScripts(scripts: Iterable<File>, data: Map<String, Any> = mapOf(), resultConsumer: GroovyConsoleResult.() -> Unit = {}) {
        scripts.forEach { resultConsumer(evalScript(it, data)) }
    }

    companion object {
        const val EVAL_PATH = "/bin/groovyconsole/post.json"

        const val SYMBOLIC_NAME = "aem-groovy-console"
    }
}
