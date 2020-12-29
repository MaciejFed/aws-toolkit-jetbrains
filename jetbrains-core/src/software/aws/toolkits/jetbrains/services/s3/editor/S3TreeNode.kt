// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.s3.editor

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.ui.treeStructure.SimpleNode
import kotlinx.coroutines.runBlocking
import software.aws.toolkits.resources.message
import java.time.Instant

sealed class S3TreeNode(val bucketName: String, val parent: S3TreeDirectoryNode?, val key: String) : SimpleNode() {
    open val isDirectory = false
    override fun getChildren(): Array<S3TreeNode> = arrayOf()
    override fun getName(): String = key.substringAfterLast('/')
}

fun S3TreeNode.getDirectoryKey() = if (isDirectory) {
    key
} else {
    parent?.key ?: throw IllegalStateException("$key claimed it was not a directory but has no parent!")
}

class S3TreeDirectoryNode(val bucket: S3VirtualBucket, parent: S3TreeDirectoryNode?, key: String) : S3TreeNode(bucket.name, parent, key) {
    override val isDirectory = true
    private val childrenLock = Object()
    private val loadedPages = mutableSetOf<String>()
    private var cachedList: List<S3TreeNode> = listOf()

    override fun getName(): String = key.dropLast(1).substringAfterLast('/') + '/'
    override fun getChildren(): Array<S3TreeNode> {
        synchronized(childrenLock) {
            if (cachedList.isEmpty()) {
                cachedList = loadObjects()
            }
        }
        return cachedList.toTypedArray()
    }

    @Synchronized
    fun loadMore(continuationToken: String) {
        // dedupe calls
        if (loadedPages.contains(continuationToken)) {
            return
        }
        cachedList = children.dropLastWhile { it is S3TreeContinuationNode } + loadObjects(continuationToken)
        loadedPages.add(continuationToken)
    }

    private fun loadObjects(continuationToken: String? = null): List<S3TreeNode> {
        val response = runBlocking {
            bucket.listObjects(key, continuationToken)
        }

        val continuation = listOfNotNull(
            response.nextContinuationToken()?.let {
                S3TreeContinuationNode(bucketName, this, "${this.key}/${message("s3.load_more")}", it)
            }
        )

        val folders = response.commonPrefixes()?.map { S3TreeDirectoryNode(bucket, this, it.prefix()) } ?: emptyList()

        val s3Objects = response
            .contents()
            ?.filterNotNull()
            ?.filterNot { it.key() == key }
            ?.map { S3TreeObjectNode(bucket, this, it.key(), it.size(), it.lastModified()) as S3TreeNode }
            ?: emptyList()

        return (folders + s3Objects).sortedBy { it.key } + continuation
    }

    fun removeAllChildren() {
        cachedList = listOf()
    }
}

private val fileTypeRegistry = FileTypeRegistry.getInstance()

open class S3TreeObjectNode(val bucket: S3VirtualBucket, parent: S3TreeDirectoryNode?, key: String, val size: Long, val lastModified: Instant) :
    S3TreeNode(bucket.name, parent, key) {

    var showHistory: Boolean = false
    private val fileType = fileTypeRegistry.getFileTypeByFileName(name)

    init {
        fileType.takeIf { it !is UnknownFileType }?.icon.let { icon = it }
    }

    override fun getChildren(): Array<S3TreeNode> {
        if (showHistory) {
            val response = runBlocking {
                bucket.listVersionObjects(key)
            }
            return (
                response
                    .versions()
                    ?.map { S3TreeObjectVersionNode(bucket, parent, key, it.size(), it.lastModified(), it.versionId()) as S3TreeNode }
                    ?: emptyList()
                )
                .toTypedArray()
        }
        return emptyArray()
    }
}

class S3TreeObjectVersionNode(bucket: S3VirtualBucket, parent: S3TreeDirectoryNode?, key: String, size: Long, lastModified: Instant, val versionId: String) :
    S3TreeObjectNode(bucket, parent, key, size, lastModified) {
    override fun getName(): String = "/$versionId"
    private val fileType = fileTypeRegistry.getFileTypeByFileName(key.substringAfterLast('/'))

    init {
        fileType.takeIf { it !is UnknownFileType }?.icon.let { icon = it }
    }
}

class S3TreeContinuationNode(bucketName: String, parent: S3TreeDirectoryNode?, key: String, val token: String) : S3TreeNode(bucketName, parent, key)
