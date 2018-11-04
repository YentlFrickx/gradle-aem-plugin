package com.cognifide.gradle.aem.internal.file.resolver

import com.cognifide.gradle.aem.internal.Formats
import com.cognifide.gradle.aem.internal.Patterns
import com.cognifide.gradle.aem.internal.file.FileException
import com.cognifide.gradle.aem.internal.file.downloader.HttpFileDownloader
import com.cognifide.gradle.aem.internal.file.downloader.SftpFileDownloader
import com.cognifide.gradle.aem.internal.file.downloader.SmbFileDownloader
import com.cognifide.gradle.aem.internal.file.downloader.UrlFileDownloader
import com.google.common.hash.HashCode
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.BooleanUtils
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Internal
import org.gradle.util.GFileUtils
import java.io.File

/**
 * File downloader with groups supporting files from multiple sources: local and remote (SFTP, SMB, HTTP).
 */
abstract class Resolver<G : FileGroup>(
        @get:Internal
        val project: Project,

        @get:Internal
        val downloadDir: File
) {

    private val groupDefault = this.createGroup(GROUP_DEFAULT)

    private var groupCurrent = groupDefault

    private val groups = mutableListOf<G>().apply { add(groupDefault) }

    protected open fun resolve(hash: Any, resolver: (FileResolution) -> File) {
        val id = HashCode.fromInt(HashCodeBuilder().append(hash).toHashCode()).toString()

        groupCurrent.resolve(id, resolver)
    }

    fun outputDirs(filter: (String) -> Boolean = { true }): List<File> {
        return filterGroups(filter).flatMap { it.dirs }
    }

    fun allFiles(filter: (String) -> Boolean = { true }): List<File> {
        return filterGroups(filter).flatMap { it.files }
    }

    fun group(name: String): G {
        return groups.find { it.name == name }
                ?: throw FileException("File group '$name' is not defined.")
    }

    fun filterGroups(filter: String): List<G> {
        return filterGroups { Patterns.wildcard(it, filter) }
    }

    fun filterGroups(filter: (String) -> Boolean): List<G> {
        return groups.filter { filter(it.name) }.filter { it.resolutions.isNotEmpty() }
    }

    fun dependency(notation: Any) {
        resolve(notation) {
            val configName = "fileResolver_dependency_${DigestUtils.md5Hex(downloadDir.path + notation)}"
            val configOptions: (Configuration) -> Unit = { it.isTransitive = false }
            val config = project.configurations.create(configName, configOptions)

            project.dependencies.add(config.name, notation)
            config.singleFile
        }
    }

    fun url(url: String) {
        when {
            SftpFileDownloader.handles(url) -> downloadSftpAuth(url)
            SmbFileDownloader.handles(url) -> downloadSmbAuth(url)
            HttpFileDownloader.handles(url) -> downloadHttpAuth(url)
            UrlFileDownloader.handles(url) -> downloadUrl(url)
            else -> local(url)
        }
    }

    fun downloadSftp(url: String) {
        resolve(url) {
            download(url, it.dir) { file ->
                SftpFileDownloader(project).download(url, file)
            }
        }
    }

    private fun download(url: String, targetDir: File, downloader: (File) -> Unit): File {
        GFileUtils.mkdirs(targetDir)

        val file = File(targetDir, FilenameUtils.getName(url))
        val lock = File(targetDir, DOWNLOAD_LOCK)
        if (!lock.exists() && file.exists()) {
            file.delete()
        }

        if (!file.exists()) {
            downloader(file)

            lock.printWriter().use {
                it.print(Formats.toJson(mapOf(
                        "downloaded" to Formats.date()
                )))
            }
        }

        return file
    }

    fun downloadSftpAuth(url: String) {
        downloadSftpAuth(url, null, null)
    }

    fun downloadSftpAuth(url: String, hostChecking: Boolean?) {
        downloadSftpAuth(url, null, null, hostChecking)
    }

    fun downloadSftpAuth(url: String, username: String?, password: String?) {
        downloadSftpAuth(url, username, password, null)
    }

    fun downloadSftpAuth(url: String, username: String?, password: String?, hostChecking: Boolean?) {
        resolve(arrayOf(url, username, password, hostChecking)) {
            download(url, it.dir) { file ->
                val downloader = SftpFileDownloader(project)

                downloader.username = username ?: project.properties["aem.sftp.username"] as String?
                downloader.password = password ?: project.properties["aem.sftp.password"] as String?
                downloader.hostChecking = hostChecking ?: BooleanUtils.toBoolean(project.properties["aem.sftp.hostChecking"] as String?
                        ?: "false")

                downloader.download(url, file)
            }
        }
    }

    fun downloadSmb(url: String) {
        resolve(url) {
            download(url, it.dir) { file ->
                SmbFileDownloader(project).download(url, file)
            }
        }
    }

    fun downloadSmbAuth(url: String) {
        downloadSmbAuth(url, null, null, null)
    }

    fun downloadSmbAuth(url: String, domain: String?, username: String?, password: String?) {
        resolve(arrayOf(url, domain, username, password)) {
            download(url, it.dir) { file ->
                val downloader = SmbFileDownloader(project)

                downloader.domain = domain ?: project.properties["aem.smb.domain"] as String?
                downloader.username = username ?: project.properties["aem.smb.username"] as String?
                downloader.password = password ?: project.properties["aem.smb.password"] as String?

                downloader.download(url, file)
            }
        }
    }

    fun downloadHttp(url: String) {
        resolve(url) {
            download(url, it.dir) { file ->
                HttpFileDownloader(project).download(url, file)
            }
        }
    }

    fun downloadHttpAuth(url: String) {
        downloadHttpAuth(url, null, null)
    }

    fun downloadHttpAuth(url: String, ignoreSSL: Boolean?) {
        downloadHttpAuth(url, null, null, ignoreSSL)
    }

    fun downloadHttpAuth(url: String, user: String?, password: String?) {
        downloadHttpAuth(url, user, password, null)
    }

    fun downloadHttpAuth(url: String, user: String?, password: String?, ignoreSSL: Boolean?) {
        resolve(arrayOf(url, user, password, ignoreSSL)) {
            download(url, it.dir) { file ->
                val downloader = HttpFileDownloader(project)

                downloader.username = user ?: project.properties["aem.http.username"] as String?
                downloader.password = password ?: project.properties["aem.http.password"] as String?
                downloader.ignoreSSLErrors = ignoreSSL ?: BooleanUtils.toBoolean(project.properties["aem.http.ignoreSSL"] as String?
                        ?: "true")

                downloader.download(url, file)
            }
        }
    }

    fun downloadUrl(url: String) {
        resolve(url) {
            download(url, it.dir) { file ->
                UrlFileDownloader(project).download(url, file)
            }
        }
    }

    fun local(path: String) {
        local(project.file(path))
    }

    fun local(sourceFile: File) {
        resolve(sourceFile.absolutePath) { sourceFile }
    }

    fun config(configurer: G.() -> Unit) {
        groupCurrent.apply(configurer)
    }

    @Synchronized
    fun group(name: String, configurer: Resolver<G>.() -> Unit) {
        groupCurrent = groups.find { it.name == name } ?: createGroup(name).apply { groups.add(this) }
        this.apply(configurer)
        groupCurrent = groupDefault
    }

    abstract fun createGroup(name: String): G

    companion object {
        const val GROUP_DEFAULT = "default"

        const val DOWNLOAD_LOCK = "download.lock"
    }

}