package com.typecho.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typecho.app.data.model.Comment
import com.typecho.app.data.model.CommentThread

@Composable
fun CommentItem(
    comment: Comment,
    modifier: Modifier = Modifier,
    onReply: ((Comment) -> Unit)? = null,
    onDelete: ((Comment) -> Unit)? = null,
    onApprove: ((Comment) -> Unit)? = null,
    showActions: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            FeedAvatar(name = comment.author, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.author,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showActions) {
                        CommentActionMenu(
                            comment = comment,
                            onReply = onReply,
                            onDelete = onDelete,
                            onApprove = onApprove
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.displayDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onReply != null) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "回复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onReply(comment) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    if (comment.isPending && showActions) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "待审核",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = 42.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun CommentThreadItem(
    thread: CommentThread,
    modifier: Modifier = Modifier,
    onReply: ((Comment) -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CommentItem(comment = thread.root, onReply = onReply)

        if (thread.replies.isEmpty()) return@Column

        var expanded by remember { mutableStateOf(false) }
        val shown = if (expanded) thread.replies else thread.replies.take(2)
        val nameById = thread.replies.associate { it.commentId to it.author }

        Column(modifier = Modifier.padding(start = 44.dp)) {
            shown.forEach { reply ->
                val targetName = when {
                    reply.parentId == thread.root.commentId -> null
                    else -> nameById[reply.parentId]
                }
                ReplyRow(comment = reply, replyToName = targetName, onReply = onReply)
            }
            if (thread.replies.size > 2 && !expanded) {
                Text(
                    text = "展开 ${thread.replies.size - 2} 条回复",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = true }
                        .padding(vertical = 6.dp)
                )
            } else if (expanded && thread.replies.size > 2) {
                Text(
                    text = "收起",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = false }
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ReplyRow(
    comment: Comment,
    replyToName: String?,
    onReply: ((Comment) -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        FeedAvatar(name = comment.author, size = 22.dp, fallback = "回")
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comment.author,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (!replyToName.isNullOrBlank()) append("回复 @").append(replyToName).append("：")
                    append(comment.content)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.displayDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onReply != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onReply(comment) }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentActionMenu(
    comment: Comment,
    onReply: ((Comment) -> Unit)?,
    onDelete: ((Comment) -> Unit)?,
    onApprove: ((Comment) -> Unit)?
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "更多操作",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onReply != null) {
                DropdownMenuItem(
                    text = { Text("回复") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    onClick = { expanded = false; onReply(comment) }
                )
            }
            if (onApprove != null && !comment.isApproved) {
                DropdownMenuItem(
                    text = { Text("通过") },
                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { expanded = false; onApprove(comment) }
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { expanded = false; onDelete(comment) }
                )
            }
        }
    }
}
