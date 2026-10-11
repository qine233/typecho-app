package noline.zhouzhou.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.interactionDataStore: DataStore<androidx.datastore.preferences.core.Preferences>
    by preferencesDataStore(name = "typecho_interactions")

/**
 * Typecho 的 XML-RPC 没有点赞/收藏接口，这两个动作只落在本机，
 * 因此这里不做假计数，只保证「我赞过、我收过」在重启后仍然成立。
 */
class InteractionRepository(private val context: Context) {

    private object Keys {
        val LIKED = stringSetPreferencesKey("liked_posts")
        val COLLECTED = stringSetPreferencesKey("collected_posts")
    }

    val likedPosts: Flow<Set<String>> = context.interactionDataStore.data
        .map { it[Keys.LIKED] ?: emptySet() }

    val collectedPosts: Flow<Set<String>> = context.interactionDataStore.data
        .map { it[Keys.COLLECTED] ?: emptySet() }

    suspend fun isLiked(postId: String): Boolean =
        (context.interactionDataStore.data.first()[Keys.LIKED] ?: emptySet()).contains(postId)

    suspend fun toggleLike(postId: String): Boolean = toggle(Keys.LIKED, postId)

    suspend fun toggleCollect(postId: String): Boolean = toggle(Keys.COLLECTED, postId)

    private suspend fun toggle(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>, postId: String): Boolean {
        var nowOn = false
        context.interactionDataStore.edit { prefs ->
            val current = (prefs[key] ?: emptySet()).toMutableSet()
            nowOn = if (postId in current) current.remove(postId) else current.add(postId)
            prefs[key] = current
        }
        return nowOn
    }
}
