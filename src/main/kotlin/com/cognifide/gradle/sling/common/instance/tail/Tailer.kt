package com.cognifide.gradle.sling.common.instance.tail

import com.cognifide.gradle.sling.SlingVersion
import com.cognifide.gradle.sling.common.instance.Instance
import com.cognifide.gradle.sling.common.instance.InstanceManager
import com.cognifide.gradle.common.utils.Formats
import com.cognifide.gradle.sling.common.instance.tail.io.ConsolePrinter
import com.cognifide.gradle.sling.common.instance.tail.io.FileDestination
import com.cognifide.gradle.sling.common.instance.tail.io.LogFiles
import com.cognifide.gradle.sling.common.instance.tail.io.UrlSource
import com.cognifide.gradle.common.utils.using
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Paths
import kotlin.math.max

class Tailer(val manager: InstanceManager) {

    internal val sling = manager.sling

    private val common = sling.common

    /**
     * Directory where log files will be stored.
     */
    val logStorageDir = sling.obj.dir {
        convention(manager.buildDir.dir("tail/logs"))
        sling.prop.file("instance.tail.logStorageDir")?.let { set(it) }
    }

    /**
     * Determines log file being tracked on Sling instance.
     */
    var logFilePath = sling.obj.string {
        convention("/logs/error.log")
        sling.prop.string("instance.tail.logFilePath")?.let { set(it) }
    }

    /**
     * Path to file holding wildcard rules that will effectively deactivate notifications for desired exception.
     *
     * Changes in that file are automatically considered (tailer restart is not required).
     */
    val incidentFilter = sling.obj.file {
        convention(manager.configDir.file("tail/incidentFilter.txt"))
        sling.prop.file("instance.tail.incidentFilter")?.let { set(it) }
    }

    /**
     * Indicates if tailer will print all logs to console.
     */
    val console = sling.obj.boolean {
        convention(true)
        sling.prop.boolean("instance.tail.console")?.let { set(it) }
    }

    /**
     * Time window in which exceptions will be aggregated and reported as single incident.
     */
    val incidentDelay = sling.obj.long {
        convention(5000L)
        sling.prop.long("instance.tail.incidentDelay")?.let { set(it) }
    }

    /**
     * Determines how often logs will be polled from Sling instance.
     */
    val fetchInterval = sling.obj.long {
        convention(500L)
        sling.prop.long("instance.tail.fetchInterval")?.let { set(it) }
    }

    val lockInterval = sling.obj.long {
        convention(fetchInterval.map { max(1000L + it, 2000L) })
        sling.prop.long("instance.tail.lockInterval")?.let { set(it) }
    }

    val linesChunkSize = sling.obj.long {
        convention(400L)
        sling.prop.long("instance.tail.linesChunkSize")?.let { set(it) }
    }

    /**
     * Hook for tracking all log entries on each Sling instance.
     *
     * Useful for integrating external services like chats etc.
     */
    fun logListener(callback: Log.(Instance) -> Unit) {
        this.logListener = callback
    }

    internal var logListener: Log.(Instance) -> Unit = {}

    /**
     * Log filter responsible for filtering incidents.
     */
    val logFilter = LogFilter(sling.project).apply {
        excludeFile(incidentFilter)
    }

    fun logFilter(options: LogFilter.() -> Unit) = logFilter.using(options)

    /**
     * Determines which log entries are considered as incidents.
     */
    fun incidentChecker(predicate: Log.(Instance) -> Boolean) {
        this.incidentChecker = predicate
    }

    fun tail(instance: Instance) = tail(listOf(instance))

    /**
     * Run tailer daemons (tracking instance logs).
     */
    fun tail(instances: Collection<Instance>) {
        checkStartLock()
        initIncidentFilter()

        runBlocking {
            startAll(instances).forEach { tailer ->
                launch {
                    while (isActive) {
                        logFiles.lock()
                        tailer.tail()
                        delay(fetchInterval.get())
                    }
                }
            }
        }
    }

    internal var incidentChecker: Log.(Instance) -> Boolean = { instance ->
        val levels = Formats.toList(instance.property("instance.tail.incidentLevels"))
                ?: sling.prop.list("instance.tail.incidentLevels")
                ?: INCIDENT_LEVELS_DEFAULT
        val oldMillis = instance.property("instance.tail.incidentOld")?.toLong()
                ?: sling.prop.long("instance.tail.incidentOld")
                ?: INCIDENT_OLD_DEFAULT

        isLevel(levels) && !isOlderThan(oldMillis) && !logFilter.isExcluded(this)
    }

    @Suppress("unused_parameter")
    fun errorLogEndpoint(instance: Instance): String {
        return "$ENDPOINT_PATH?tail=$linesChunkSize&name=${logFilePath.get()}"
    }

    val logFile: String get() = Paths.get(logFilePath.get()).fileName.toString()

    private val logFiles = LogFiles(this)

    private fun checkStartLock() {
        if (logFiles.isLocked()) {
            throw TailerException("Another instance of log tailer is running for this project.")
        }
        logFiles.lock()
    }

    private fun initIncidentFilter() = incidentFilter.get().asFile.run {
        parentFile.mkdirs()
        createNewFile()
    }

    private fun startAll(instances: Collection<Instance>): List<LogTailer> {
        val notificationChannel = Channel<LogChunk>(Channel.UNLIMITED)
        val logNotifier = LogNotifier(notificationChannel, common.notifier, logFiles)
        logNotifier.listenTailed()

        return instances.map { start(it, notificationChannel) }
    }

    private fun start(instance: Instance, notificationChannel: Channel<LogChunk>): LogTailer {
        val source = UrlSource(this, instance)
        val destination = FileDestination(instance.name, logFiles)
        val logAnalyzerChannel = Channel<Log>(Channel.UNLIMITED)

        val logAnalyzer = InstanceAnalyzer(this, instance, logAnalyzerChannel, notificationChannel)
        logAnalyzer.listenTailed()

        val logFile = logFiles.main(instance.name)
        sling.logger.lifecycle("Tailing logs to file: $logFile")

        return LogTailer(source, destination, InstanceLogInfo(instance), logAnalyzerChannel, consolePrinter(instance))
    }

    private fun consolePrinter(instance: Instance) = when {
        console.get() -> ConsolePrinter(InstanceLogInfo(instance), { sling.logger.lifecycle(it) })
        else -> ConsolePrinter.none()
    }

    companion object {

        const val ENDPOINT_PATH = "/system/console/slinglog/tailer.txt"

        const val ENDPOINT_PATH_OLD = "/bin/crxde/logs"

        val INCIDENT_LEVELS_DEFAULT = listOf("ERROR")

        const val INCIDENT_OLD_DEFAULT = 1000L * 10
    }
}
