// 验证公开 namespace 配置的默认值和输入门禁。
package io.lingora.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LingoraNamespacesTest {
    @Test
    fun defaultConfigurationUsesOnlyAppNamespace() {
        val namespaces = LingoraNamespaces()

        assertEquals("app", namespaces.defaultNamespace)
        assertEquals(listOf("app"), namespaces.all)
    }

    @Test
    fun additionalNamespacesHaveStableOrderAndPaths() {
        val namespaces = LingoraNamespaces(additionalNamespaces = setOf("common", "account"))

        assertEquals(listOf("app", "account", "common"), namespaces.all)
        assertEquals("common/ja.json", namespaces.path("common", "ja"))
    }

    @Test
    fun duplicateOrBlankNamespacesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LingoraNamespaces(defaultNamespace = "app", additionalNamespaces = setOf("app"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LingoraNamespaces(additionalNamespaces = setOf(""))
        }
    }
}
