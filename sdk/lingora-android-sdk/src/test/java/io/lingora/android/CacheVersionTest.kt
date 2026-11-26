// 验证缓存回退只拒绝可证明被当前内存版本取代的旧副本。
package io.lingora.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheVersionTest {
    // 写盘失败的旧缓存保留内存；后续提交、不同正文和普通 304 都接受磁盘。
    @Test fun comparesBothContentAndCommit() {
        val stale = CacheVersion("A", 1)
        val fallback = LoadedResource("A", LoadedSource.CACHE, "503", cacheVersion = stale)
        assertTrue(fallback.isSupersededBy(stale))
        assertFalse(fallback.isSupersededBy(null))
        assertFalse(fallback.copy(cacheVersion = CacheVersion("B", 1)).isSupersededBy(stale))
        assertFalse(fallback.copy(cacheVersion = CacheVersion("A", 2)).isSupersededBy(stale))
        assertFalse(fallback.copy(warning = null).isSupersededBy(stale))
        assertFalse(fallback.copy(source = LoadedSource.NETWORK).isSupersededBy(stale))
    }
}
