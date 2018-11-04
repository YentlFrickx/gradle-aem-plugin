package com.cognifide.gradle.aem.api

import com.cognifide.gradle.aem.base.vlt.VltFilter
import com.cognifide.gradle.aem.instance.Instance
import com.cognifide.gradle.aem.instance.InstancePlugin
import com.cognifide.gradle.aem.instance.InstanceSync
import com.cognifide.gradle.aem.instance.LocalHandle
import com.cognifide.gradle.aem.internal.PropertyParser
import com.cognifide.gradle.aem.pkg.ComposeTask
import com.cognifide.gradle.aem.pkg.PackagePlugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import java.io.File

/**
 * Main place for providing build script DSL capabilities in case of AEM.
 */
open class AemExtension(@Transient private val project: Project) {

    @Internal
    val logger = project.logger

    /**
     * Allows to read project property specified in command line and system property as a fallback.
     */
    @Internal
    val props = PropertyParser(this, project)

    @Nested
    val config = AemConfig(this, project)

    @Nested
    val bundle = AemBundle(project)

    @Internal
    val notifier = AemNotifier.of(project)

    @Internal
    val tasks = AemTaskFactory(project)

    @get:Internal
    val instances: List<Instance>
        get() = Instance.filter(project)

    fun instances(filter: String): List<Instance> {
        return Instance.filter(project, filter)
    }

    fun instances(consumer: (Instance) -> Unit) {
        instances.parallelStream().forEach(consumer)
    }

    fun instances(filter: String, consumer: (Instance) -> Unit) {
        instances(filter).parallelStream().forEach(consumer)
    }

    fun instance(urlOrName: String): Instance {
        return config.parseInstance(urlOrName)
    }

    fun instanceAny() = Instance.any(project)

    fun instanceConcrete(type: String) = Instance.concrete(project, type)

    @get:Internal
    val handles: List<LocalHandle>
        get() = Instance.handles(project)

    fun handles(consumer: LocalHandle.() -> Unit) {
        handles(handles, consumer)
    }

    fun handles(handles: Collection<LocalHandle>, consumer: LocalHandle.() -> Unit) {
        handles.parallelStream().forEach(consumer)
    }

    fun packages(consumer: (File) -> Unit) {
        project.tasks.withType(ComposeTask::class.java)
                .map { it.archivePath }
                .parallelStream()
                .forEach(consumer)
    }

    @get:Internal
    val packages: List<File>
        get() = project.tasks.withType(ComposeTask::class.java)
                .map { it.archivePath }

    fun packages(task: Task): List<File> {
        return task.taskDependencies.getDependencies(task)
                .filterIsInstance(ComposeTask::class.java)
                .map { it.archivePath }
    }

    @get:Internal
    val packageDefault: File
        get() = compose.archivePath

    // TODO remove most of dependencies of that
    @get:Internal
    val compose: ComposeTask
        get() = compose(project)

    fun compose(project: Project) = project.tasks.getByName(ComposeTask.NAME) as ComposeTask

    fun sync(synchronizer: InstanceSync.() -> Unit) = sync(instances, synchronizer)

    fun sync(instances: Collection<Instance>, synchronizer: InstanceSync.() -> Unit) {
        instances.parallelStream().forEach { it.sync(synchronizer) }
    }

    fun syncPackages(synchronizer: InstanceSync.(File) -> Unit) = syncPackages(instances, packages, synchronizer)

    fun syncPackages(instances: Collection<Instance>, packages: Collection<File>, synchronizer: InstanceSync.(File) -> Unit) {
        val pairs = mutableListOf<Pair<Instance, File>>()
        instances.forEach { i -> packages.forEach { p -> Pair(i, p) } }
        pairs.parallelStream().forEach { (i, p) -> synchronizer(InstanceSync(project, i), p) }
    }

    fun config(configurer: AemConfig.() -> Unit) {
        config.apply(configurer)
    }

    fun bundle(configurer: AemBundle.() -> Unit) {
        bundle.apply(configurer)
    }

    fun notifier(configurer: AemNotifier.() -> Unit) {
        notifier.apply(configurer)
    }

    fun tasks(configurer: AemTaskFactory.() -> Unit) {
        tasks.apply(configurer)
    }

    fun retry(configurer: AemRetry.() -> Unit): AemRetry {
        return retry().apply(configurer)
    }

    fun retry(): AemRetry = AemRetry()

    fun filter(): VltFilter = VltFilter.of(project)

    companion object {
        const val NAME = "aem"

        fun of(project: Project): AemExtension {
            return project.extensions.findByType(AemExtension::class.java)
                    ?: throw AemException(project.displayName.capitalize()
                            + " has neither '${PackagePlugin.ID}' nor '${InstancePlugin.ID}' plugin applied.")
        }

        fun available(project: Project): Boolean {
            return project.extensions.findByType(AemExtension::class.java) != null
        }
    }

}