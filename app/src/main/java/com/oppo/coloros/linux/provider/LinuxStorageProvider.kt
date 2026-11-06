package com.oppo.coloros.linux.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.oppo.coloros.linux.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android DocumentsProvider 桥接层 (工业级高安全版)
 * 严格防御 Path Traversal (越权路径穿越) 与软链接逃逸删除漏洞
 */
class LinuxStorageProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "org.roland.coloroslinux.documents"
        private const val ROOT_ID = "coloros_linux_home"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }

    private lateinit var baseHomeDir: File

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val rootDir = File(ctx.filesDir, "ubuntu-rootfs/root")
        val oppoHomeDir = File(ctx.filesDir, "ubuntu-rootfs/home/oppo")
        
        baseHomeDir = if (rootDir.exists() || !oppoHomeDir.exists()) {
            rootDir
        } else {
            oppoHomeDir
        }
        if (!baseHomeDir.exists()) {
            baseHomeDir.mkdirs()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        context ?: return result

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                        Root.FLAG_SUPPORTS_IS_CHILD or
                        Root.FLAG_LOCAL_ONLY
            )
            add(Root.COLUMN_TITLE, "Ubuntu Linux")
            add(Root.COLUMN_SUMMARY, "${baseHomeDir.name} 工作区")
            add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(baseHomeDir))
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, baseHomeDir.usableSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, null)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        val children = parent.listFiles() ?: emptyArray()
        for (file in children) {
            includeFile(result, null, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val safeName = sanitizeFileName(displayName)
        val parent = getFileForDocId(parentDocumentId)
        var newFile = File(parent, safeName)
        var conflictIndex = 1
        while (newFile.exists()) {
            newFile = File(parent, "${safeName}_$conflictIndex")
            conflictIndex++
        }

        if (!isStrictChild(baseHomeDir, newFile)) {
            throw SecurityException("越权创建文件被拦截")
        }

        try {
            val success = if (Document.MIME_TYPE_DIR == mimeType) {
                newFile.mkdir()
            } else {
                newFile.createNewFile()
            }
            if (!success) {
                throw FileNotFoundException("无法创建文件或目录: ${newFile.absolutePath}")
            }
        } catch (e: IOException) {
            throw FileNotFoundException("创建文件异常: ${e.message}")
        }

        return getDocIdForFile(newFile)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file == baseHomeDir) {
            throw SecurityException("禁止删除根工作区目录")
        }
        if (!deleteRecursively(file)) {
            throw FileNotFoundException("无法删除目标文件: ${file.absolutePath}")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val safeName = sanitizeFileName(displayName)
        val sourceFile = getFileForDocId(documentId)
        if (sourceFile == baseHomeDir) {
            throw SecurityException("禁止重命名根工作区目录")
        }
        val parentFile = sourceFile.parentFile
            ?: throw FileNotFoundException("找不到父级目录")
        val destFile = File(parentFile, safeName)
        if (!isStrictChild(baseHomeDir, destFile)) {
            throw SecurityException("越权移动/重命名被拦截")
        }
        if (!sourceFile.renameTo(destFile)) {
            throw FileNotFoundException("重命名失败: ${sourceFile.name} -> $safeName")
        }
        return getDocIdForFile(destFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = getFileForDocId(parentDocumentId)
            val child = getFileForDocId(documentId)
            isStrictChild(parent, child)
        } catch (e: Exception) {
            false
        }
    }

    private fun isStrictChild(parentDir: File, targetFile: File): Boolean {
        val parentCanonical = parentDir.canonicalPath
        val targetCanonical = targetFile.canonicalPath
        if (parentCanonical == targetCanonical) return true
        val parentPrefix = if (parentCanonical.endsWith(File.separator)) {
            parentCanonical
        } else {
            parentCanonical + File.separator
        }
        return targetCanonical.startsWith(parentPrefix)
    }

    private fun sanitizeFileName(displayName: String): String {
        val cleaned = displayName.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." ||
            cleaned.contains('/') || cleaned.contains('\\') || cleaned.contains('\u0000')
        ) {
            throw SecurityException("非法的文档名称: $displayName")
        }
        return cleaned
    }

    private fun getDocIdForFile(file: File): String {
        val rootCanonical = baseHomeDir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (fileCanonical == rootCanonical) {
            return ROOT_ID
        }
        if (isStrictChild(baseHomeDir, file)) {
            val rel = fileCanonical.substring(rootCanonical.length).removePrefix(File.separator)
            return "$ROOT_ID:$rel"
        }
        throw SecurityException("越权路径访问被拦截: $fileCanonical")
    }

    @Throws(FileNotFoundException::class)
    private fun getFileForDocId(docId: String): File {
        if (!baseHomeDir.exists()) {
            baseHomeDir.mkdirs()
        }
        if (docId == ROOT_ID) {
            return baseHomeDir
        }
        val splitIndex = docId.indexOf(':')
        if (splitIndex == -1) {
            throw FileNotFoundException("无效的 Document ID: $docId")
        }
        val relPath = docId.substring(splitIndex + 1)
        val target = File(baseHomeDir, relPath)

        if (!isStrictChild(baseHomeDir, target)) {
            throw SecurityException("拒绝访问工作区以外的目录: ${target.canonicalPath}")
        }
        if (!target.exists()) {
            throw FileNotFoundException("文件不存在: $docId")
        }
        return target
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File?) {
        var targetDocId = docId
        var targetFile = file

        if (targetDocId == null) {
            targetDocId = getDocIdForFile(targetFile!!)
        } else {
            targetFile = getFileForDocId(targetDocId)
        }

        var flags = 0
        if (targetFile.isDirectory) {
            if (targetFile.canWrite()) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (targetFile.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        if (targetFile != baseHomeDir && targetFile.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }

        val displayName = if (targetFile == baseHomeDir) baseHomeDir.name else targetFile.name
        val mimeType = getMimeType(targetFile)

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, targetDocId)
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_SIZE, targetFile.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, targetFile.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) {
            return Document.MIME_TYPE_DIR
        }
        val name = file.name
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val stat = Os.lstat(file.absolutePath)
            OsConstants.S_ISLNK(stat.st_mode)
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        // 关键安全防御：若为软链接，直接删除链接本身，绝不递归遍历其指向的宿主或容器外目录
        if (file.isDirectory && !isSymlink(file)) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }
}
