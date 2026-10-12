package noline.zhouzhou.data.repository

import noline.zhouzhou.data.remote.Release
import noline.zhouzhou.data.remote.ReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自更新的判定逻辑。下载和安装都要真机，这里只锁住两条容易写错的：
 * 版本号比较不能按字符串比，渠道不匹配的包不能认。
 */
class UpdateRepositoryTest {

    private fun release(tag: String, vararg assets: ReleaseAsset) = Release(
        tagName = tag,
        name = "NLecho $tag",
        htmlUrl = "https://github.com/qine233/typecho-app/releases/tag/$tag",
        publishedAt = "2026-10-12T09:00:00Z",
        assets = assets.toList()
    )

    private fun apk(name: String, size: Long = 19_000_000) = ReleaseAsset(
        name = name,
        size = size,
        downloadUrl = "https://objects.githubusercontent.com/$name"
    )

    @Test
    fun `version compare goes by numeric segments not text`() {
        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
        // 按字符串比会判 1.9 > 1.10，这里必须反过来
        assertTrue(compareVersions("1.9.0", "1.10.0") < 0)
        assertTrue(compareVersions("2.0.0", "10.0.0") < 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(0, compareVersions("v1.0.1", "1.0.1"))
        assertEquals(0, compareVersions("1.0.1-rc1", "1.0.1"))
        assertTrue(compareVersions("", "0.0.1") < 0)
    }

    @Test
    fun `newer tag on the same channel is an available update`() {
        val outcome = resolveUpdate(
            release("v1.0.1", apk("NLecho-1.0.1-debug.apk")),
            currentVersion = "1.0.0",
            wantsDebug = true
        )
        val available = outcome as UpdateOutcome.Available
        // tag 上的 v 不进版本串，否则设置页会写成「发现新版本 vv1.0.1」
        assertEquals("1.0.1", available.update.version)
        assertEquals("NLecho-1.0.1-debug.apk", available.update.assetName)
        assertEquals(19_000_000L, available.update.sizeBytes)
    }

    @Test
    fun `same or older tag is up to date`() {
        val same = resolveUpdate(release("v1.0.0", apk("NLecho-1.0.0-debug.apk")), "1.0.0", true)
        val older = resolveUpdate(release("v0.9.9", apk("NLecho-0.9.9-debug.apk")), "1.0.0", true)
        assertEquals(UpdateOutcome.UpToDate, same)
        assertEquals(UpdateOutcome.UpToDate, older)
    }

    @Test
    fun `release signed apk never matches a debug install`() {
        val outcome = resolveUpdate(
            release("v1.0.1", apk("NLecho-1.0.1.apk")),
            currentVersion = "1.0.0",
            wantsDebug = true
        )
        // 包名和签名都对不上，下下来装不进本机这份，所以宁可不提示
        assertEquals(UpdateOutcome.NoMatchingBuild, outcome)
    }

    @Test
    fun `debug apk never matches a release install`() {
        val outcome = resolveUpdate(
            release("v1.0.1", apk("NLecho-1.0.1-debug.apk")),
            currentVersion = "1.0.0",
            wantsDebug = false
        )
        assertEquals(UpdateOutcome.NoMatchingBuild, outcome)
    }

    @Test
    fun `non apk assets are not update candidates`() {
        val outcome = resolveUpdate(
            release(
                "v1.0.1",
                ReleaseAsset("mapping.zip", 100, "https://x/mapping.zip"),
                ReleaseAsset("release-notes.txt", 20, "https://x/notes.txt")
            ),
            currentVersion = "1.0.0",
            wantsDebug = true
        )
        assertEquals(UpdateOutcome.NoMatchingBuild, outcome)
    }

    @Test
    fun `tag without a version number is treated as no update`() {
        val outcome = resolveUpdate(release("latest", apk("NLecho-debug.apk")), "1.0.0", true)
        assertEquals(UpdateOutcome.UpToDate, outcome)
    }
}
