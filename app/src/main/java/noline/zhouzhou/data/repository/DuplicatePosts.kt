package noline.zhouzhou.data.repository

import noline.zhouzhou.data.model.Post

/**
 * 一组重复的文章：留哪一篇、多出来哪几篇。
 *
 * 「留哪一篇」永远是**最早**的那篇——重复多半是当年 newPost 的小回复被 PHP 警告
 * 吞掉 cid、用户又点了一次发布造成的，先发出去的那篇才是链接已经分发出去的那篇。
 */
data class DuplicateGroup(
    val title: String,
    val kept: Post,
    val extras: List<Post>
) {
    val size: Int get() = extras.size + 1
}

/**
 * 重复扫描结果。两类分开列：
 * 正文一模一样的可以直接勾上删；只是同名的多半是人自己写的续篇，得让他自己看。
 */
data class DuplicateScan(
    val identical: List<DuplicateGroup> = emptyList(),
    val sameTitle: List<DuplicateGroup> = emptyList(),
    val scanned: Int = 0,
    /** 没翻完（到上限了，或者中途取下一页失败），结果只代表前 [scanned] 篇。 */
    val truncated: Boolean = false
) {
    /** 可以安全删掉的那几篇。 */
    val deletable: List<Post> get() = identical.flatMap { it.extras }
    val redundant: Int get() = deletable.size
    val isEmpty: Boolean get() = identical.isEmpty() && sameTitle.isEmpty()
}

/** 标题：Typecho 存的时候转过义，比对前先还原；空白按单空格归一，别让一个空格漏判。 */
fun duplicateTitleKey(post: Post): String =
    post.displayTitle.trim().replace(Regex("\\s+"), " ")

/** 正文：只统一换行和首尾空白。中间的空格对 Markdown 有意义，不敢动。 */
fun duplicateBodyKey(post: Post): String =
    post.content.replace("\r\n", "\n").replace('\r', '\n').trim()

private fun oldestFirst(posts: List<Post>): List<Post> = posts.sortedWith(
    compareBy({ it.dateCreated }, { it.postId.toLongOrNull() ?: Long.MAX_VALUE })
)

private fun group(title: String, members: List<Post>): DuplicateGroup {
    val ordered = oldestFirst(members)
    return DuplicateGroup(title, ordered.first(), ordered.drop(1))
}

fun scanDuplicatePosts(posts: List<Post>): DuplicateScan {
    val real = posts.filter { it.postId.isNotBlank() }

    val identical = real
        .groupBy { duplicateTitleKey(it) to duplicateBodyKey(it) }
        .values
        .filter { it.size > 1 }
        .map { group(duplicateTitleKey(it.first()), it) }
        .sortedWith(compareByDescending<DuplicateGroup> { it.extras.size }
            .thenByDescending { it.size })

    // 同名不同内容：整组正文种类 >1 才算，纯重复那几篇上面已经列过了。
    // 占位标题（「日记」「未命名文档」）不算同名——那是没标题，不是写重了。
    val sameTitle = real
        .groupBy { duplicateTitleKey(it) }
        .filter { (title, members) ->
            title.isNotBlank() && members.size > 1 &&
                members.first().hasMeaningfulTitle &&
                members.map { duplicateBodyKey(it) }.distinct().size > 1
        }
        .map { (title, members) -> group(title, members) }
        .sortedWith(compareByDescending<DuplicateGroup> { it.size }.thenByDescending { it.title })

    return DuplicateScan(
        identical = identical,
        sameTitle = sameTitle,
        scanned = real.size
    )
}
