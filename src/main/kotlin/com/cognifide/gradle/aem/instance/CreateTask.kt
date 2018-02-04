package com.cognifide.gradle.aem.instance

import com.cognifide.gradle.aem.api.AemTask
import com.cognifide.gradle.aem.internal.file.resolver.FileResolver
import com.cognifide.gradle.aem.pkg.deploy.SyncTask
import groovy.lang.Closure
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ConfigureUtil

open class CreateTask : SyncTask() {

    companion object {
        val NAME = "aemCreate"

        val DOWNLOAD_DIR = "download"

        val LICENSE_URL_PROP = "aem.instance.local.jarUrl"

        val JAR_URL_PROP = "aem.instance.local.licenseUrl"
    }

    @Internal
    val instanceFileResolver = FileResolver(project, AemTask.temporaryDir(project, NAME, DOWNLOAD_DIR))

    init {
        description = "Creates local AEM instance(s)."

        instanceFileResolver.attach(this)
        instanceFileFromProperties()

        project.afterEvaluate {
            synchronizeLocalInstances { outputs.file(it.lock) }
        }
    }

    private fun instanceFileFromProperties() {
        val jarUrl = propertyParser.string(JAR_URL_PROP)
        if (!jarUrl.isNullOrBlank()) {
            instanceFileResolver.url(jarUrl!!)
        }

        val licenseUrl = propertyParser.string(LICENSE_URL_PROP)
        if (!licenseUrl.isNullOrBlank()) {
            instanceFileResolver.url(licenseUrl!!)
        }
    }

    fun instanceFiles(closure: Closure<*>) {
        ConfigureUtil.configure(closure, instanceFileResolver)
    }

    @TaskAction
    fun create() {
        logger.info("Creating instances")
        synchronizeLocalInstances({ it.create(instanceFileResolver) })
    }

}