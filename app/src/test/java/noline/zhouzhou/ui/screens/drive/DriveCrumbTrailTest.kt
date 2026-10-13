package noline.zhouzhou.ui.screens.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** 面包屑是从 uri 反推的，根目录那一层最容易推错。 */
class DriveCrumbTrailTest {

    @Test
    fun `the root is a single crumb`() {
        assertEquals(listOf("我的文件"), driveCrumbTrail(DriveRootUri).map { it.label })
    }

    @Test
    fun `each segment links back to the directory it names`() {
        val trail = driveCrumbTrail("cloudreve://my/blog/2026/10")

        assertEquals(listOf("我的文件", "blog", "2026", "10"), trail.map { it.label })
        assertEquals(
            listOf("cloudreve://my", "cloudreve://my/blog", "cloudreve://my/blog/2026", "cloudreve://my/blog/2026/10"),
            trail.map { it.uri }
        )
    }
}
