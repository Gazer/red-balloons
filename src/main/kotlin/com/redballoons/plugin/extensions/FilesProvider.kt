package com.redballoons.plugin.extensions

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.redballoons.plugin.model.AutoCompleteSuggestion
import com.redballoons.plugin.settings.RedBalloonsSettings
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths

class FilesProvider(private val project: Project) : CompletionProvider {
    override val trigger = '@'
    override val name = "files"

    @Volatile
    private var cachedFiles: List<String>? = null

    override fun getFiles(prefix: String): List<AutoCompleteSuggestion> {
        return try {
            ReadAction.compute<List<AutoCompleteSuggestion>, Exception> {
                searchProjectFiles(prefix)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchProjectFiles(prefix: String): List<AutoCompleteSuggestion> {
        val scope = GlobalSearchScope.projectScope(project)
        val basePath = project.basePath ?: ""
        val query = prefix.lowercase()

        val allFiles = getFiles()
        val result = mutableListOf<AutoCompleteSuggestion>()

        for (fileName in allFiles) {
            if (result.size >= 20) break

            val virtualFiles = FilenameIndex.getVirtualFilesByName(fileName, scope)
            for (file in virtualFiles) {
                if (result.size >= 20) break

                val relativePath = getRelativePath(file, basePath)
                val searchable = "$fileName $relativePath".lowercase()

                // Fuzzy match: each character in query must appear in order
                var matchPos = 0
                var matched = true
                for (char in query) {
                    val found = searchable.indexOf(char, matchPos)
                    if (found == -1) {
                        matched = false
                        break
                    }
                    matchPos = found + 1
                }

                if (matched) {
                    result.add(
                        AutoCompleteSuggestion(
                            fileName,
                            relativePath,
                            file.fileType.icon
                        )
                    )
                }
            }
        }
        return result.sortedBy { it.fileName.lowercase() }
    }

    private fun getRelativePath(file: VirtualFile, basePath: String): String {
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/")
        } else {
            filePath
        }
    }

    override fun isValid(token: String): Boolean {
        val files = getFiles()
        val basePath = project.basePath ?: ""

        for (fileName in files) {
            // Check if token matches the file name
            if (fileName == token) {
                return true
            }
            // Check if token matches a relative path in the project
            val scope = GlobalSearchScope.projectScope(project)
            val virtualFiles = FilenameIndex.getVirtualFilesByName(fileName, scope)
            for (file in virtualFiles) {
                val relativePath = getRelativePath(file, basePath)
                if (relativePath == token) {
                    return true
                }
            }
        }
        return false
    }

    override fun resolve(token: String): String? {
        if (!isValid(token)) {
            return null
        }

        val file = File("${project.basePath}/$token")
        val content = readFileContent(file) ?: return null
        val ext = file.extension
        return "```$ext\n-- ${file.path}\n$content\n```"
    }

    private fun readFileContent(file: File): String? {
        return try {
            file.takeIf { it.exists() && it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }

    init {
        // Subscribe to file system changes to invalidate cache
        project.messageBus.connect(project as Disposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    cachedFiles = null
                }
            }
        )
    }

    private fun getFiles(): List<String> {
        cachedFiles?.let { return it }

        return try {
            ReadAction.compute<List<String>, Exception> {
                searchProjectFiles()
            }.also { cachedFiles = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchProjectFiles(): List<String> {
        val result = mutableListOf<String>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val settings = RedBalloonsSettings.getInstance()
        val excludePatterns = settings.extensionFilesProviderExcludePatterns
        val maxFiles = settings.extensionFilesProviderMaxFiles
        val basePath = project.basePath ?: ""

        val matchers = excludePatterns.map { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        }

        fileIndex.iterateContent { file ->
            if (result.size >= maxFiles) {
                return@iterateContent false
            }

            val relativePath = if (file.path.startsWith(basePath)) {
                file.path.removePrefix(basePath).removePrefix("/")
            } else {
                file.path
            }

            val shouldExclude = matchers.any { matcher ->
                val path = Paths.get(relativePath)
                // Check if any part of the path matches the pattern
                (0 until path.nameCount).any { i ->
                    matcher.matches(path.getName(i)) || matcher.matches(path.subpath(0, i + 1))
                }
            }

            if (!shouldExclude) {
                result.add(file.name)
            }
            true
        }
        return result.sortedBy { it }
    }
}