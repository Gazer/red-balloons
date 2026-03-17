package com.redballoons.plugin.extensions

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.redballoons.plugin.model.AutoCompleteSuggestion
import java.io.File

class FilesProvider(private val project: Project) : CompletionProvider {
    override val trigger = '@'
    override val name = "files"

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
        val result = mutableListOf<AutoCompleteSuggestion>()
        val basePath = project.basePath ?: ""

        FilenameIndex.processAllFileNames({ fileName ->
            if (fileName.contains(prefix, ignoreCase = true)) {
                val virtualFiles = FilenameIndex.getVirtualFilesByName(fileName, scope)
                for (file in virtualFiles) {
                    if (result.size < 20) {
                        result.add(AutoCompleteSuggestion(fileName, getRelativePath(file, basePath), file.fileType.icon))
                    } else {
                        return@processAllFileNames false
                    }
                }
            }
            true
        }, scope, null)
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
        // TODO : we need to decide how we will decide which files are valid. git? project? both?
        // local files = M.get_files()
        //  for _, file in ipairs(files) do
        //    if file.path == path or file.name == path then
        //      return true
        //    end
        //  end
        //  return false
        return true
    }

    override fun resolve(token: String): String? {
        // TODO: Validate that token is part of the valid files
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
}