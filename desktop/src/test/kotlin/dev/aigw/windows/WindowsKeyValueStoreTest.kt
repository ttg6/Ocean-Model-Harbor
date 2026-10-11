package dev.aigw.windows

import java.nio.file.Files
import kotlin.test.*

class WindowsKeyValueStoreTest {
    @Test fun `DPAPI storage survives restart and supports Windows illegal path characters`() {
        val dir = Files.createTempDirectory("aigw-dpapi-")
        try {
            val key = "account/custom:test/a:b?*.json"
            val secret = "fake-test-credential-中文"
            val store = WindowsKeyValueStore(dir)
            store.write(key, secret)
            assertEquals(secret, store.read(key))
            val bytes = Files.list(dir).use { it.findFirst().get().let(Files::readAllBytes) }
            assertFalse(bytes.toString(Charsets.UTF_8).contains(secret))
            val reloaded = WindowsKeyValueStore(dir)
            assertEquals(listOf(key), reloaded.keys("account/"))
            assertEquals(secret, reloaded.read(key))
            reloaded.write(key, "rotated-token")
            assertEquals("rotated-token", WindowsKeyValueStore(dir).read(key))
            reloaded.delete(key)
            assertNull(WindowsKeyValueStore(dir).read(key))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `corrupt encrypted data is reported instead of silently losing accounts`() {
        val dir = Files.createTempDirectory("aigw-corrupt-")
        try {
            WindowsKeyValueStore(dir).write("account/test", "fake")
            Files.list(dir).use { Files.write(it.findFirst().get(), byteArrayOf(1, 2, 3)) }
            assertFailsWith<IllegalStateException> { WindowsKeyValueStore(dir) }
        } finally { dir.toFile().deleteRecursively() }
    }
}
