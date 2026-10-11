package dev.aigw.windows

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jna.platform.win32.Crypt32Util
import dev.aigw.core.store.KeyValueStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** DPAPI binds every stored value to the current Windows user. Keys never become paths. */
class WindowsKeyValueStore(private val root: Path) : KeyValueStore {
    private val values = LinkedHashMap<String, String>()

    init {
        require(System.getProperty("os.name").startsWith("Windows")) { "需要 Windows 系统" }
        Files.createDirectories(root)
        Files.list(root).use { files ->
            files.filter { it.fileName.toString().endsWith(".bin") }.forEach { path ->
                try {
                    val raw = Crypt32Util.cryptUnprotectData(Files.readAllBytes(path)).toString(Charsets.UTF_8)
                    val obj = JsonParser.parseString(raw).asJsonObject
                    val key = obj.get("key").asString
                    require(path.fileName == pathFor(key).fileName)
                    values[key] = obj.get("value").asString
                } catch (e: Exception) {
                    throw IllegalStateException("无法解密数据文件 ${path.fileName}。请使用创建这些数据的 Windows 用户。", e)
                }
            }
        }
    }

    @Synchronized override fun read(key: String): String? = values[key]

    @Synchronized override fun write(key: String, value: String) {
        val bytes = JsonObject().apply { addProperty("key", key); addProperty("value", value) }
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = Crypt32Util.cryptProtectData(bytes)
        val temporary = Files.createTempFile(root, "write-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            // Replace atomically, keeping the old token intact if the new write fails.
            Files.move(temporary, pathFor(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            values[key] = value
        } finally { Files.deleteIfExists(temporary) }
    }

    @Synchronized override fun delete(key: String) { Files.deleteIfExists(pathFor(key)); values.remove(key) }
    @Synchronized override fun keys(prefix: String): List<String> = values.keys.filter { it.startsWith(prefix) }

    private fun pathFor(key: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return root.resolve("$hash.bin")
    }
}
