package com.cognifide.gradle.aem.base.vlt

import com.cognifide.gradle.aem.api.AemDefaultTask
import org.gradle.api.tasks.TaskAction

open class SyncTask : AemDefaultTask() {

    companion object {
        val NAME = "aemSync"
    }

    init {
        description = "Check out then clean JCR content."
    }

    @TaskAction
    fun sync() {
        logger.info("Syncing JCR content with AEM instance ended with success.")
    }
}