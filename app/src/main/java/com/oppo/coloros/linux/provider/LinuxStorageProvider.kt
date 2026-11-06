package com.oppo.coloros.linux.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.oppo.coloros.linux.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android DocumentsProvider 桥接层
 * 将 Linux 根文件系统中的 /home/oppo 工作区直接暴露给 ColorOS 原生“文件管理”App
 */
class LinuxStorageProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.oppo.coloros.linux.documents"
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
        baseHomeDir = File(ctx.filesDir, "ubuntu-rootfs/home/oppo")
        if (!baseHomeDir.exists()) {
            baseHomeDir.mkdirs()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result

        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                        Root.FLAG_SUPPORTS_IS_CHILD or
                        Root.FLAG_LOCAL_ONLY
            )
            add(Root.COLUMN_TITLE, "Ubuntu Linux")
            add(Root.COLUMN_SUMMARY, "/home/oppo 工作区")
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
        val parent = getFileForDocId(parentDocumentId)
        var newFile = File(parent, displayName)
        var conflictIndex = 1
        while (newFile.exists()) {
            newFile = File(parent, "${displayName}_$conflictIndex")
            conflictIndex++
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
        if (!deleteRecursively(file)) {
            throw FileNotFoundException("无法删除目标文件: ${file.absolutePath}")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val sourceFile = getFileForDocId(documentId)
        val parentFile = sourceFile.parentFile
            ?: throw FileNotFoundException("找不到父级目录")
        val destFile = File(parentFile, displayName)
        if (!sourceFile.renameTo(destFile)) {
            throw FileNotFoundException("重命名失败: ${sourceFile.name} -> $displayName")
        }
        return getDocIdForFile(destFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = getFileForDocId(parentDocumentId).canonicalPath
        val child = getFileForDocId(documentId).canonicalPath
        return child.startsWith(parent)
    }

    private fun getDocIdForFile(file: File): String {
        val rootPath = baseHomeDir.canonicalPath
        val filePath = file.canonicalPath
        return if (filePath == rootPath) {
            ROOT_ID
        } else if (filePath.startsWith(rootPath)) {
            val rel = filePath.substring(rootPath.length).removePrefix("/")
            "$ROOT_ID:$rel"
        } else {
            throw IllegalArgumentException("越权路径访问被拦截: $filePath")
        }
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

        val rootCanonical = baseHomeDir.canonicalPath
        val targetCanonical = target.canonicalPath
        if (!targetCanonical.startsWith(rootCanonical)) {
            throw SecurityException("拒绝访问工作区以外的目录: $targetCanonical")
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

        val displayName = if (targetFile == baseHomeDir) "oppo" else targetFile.name
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

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }
}
