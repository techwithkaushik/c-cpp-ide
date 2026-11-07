package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File

/** Runtime-owned compatibility repair for Android supplementary group names in Ubuntu. */
object LinuxGuestCompatibility {
    fun ensureSupplementaryGroupNames(rootfs: File) {
        val root = runCatching { rootfs.canonicalFile }.getOrNull() ?: return
        val groupFile = File(root, "etc/group")
        val group = runCatching { groupFile.canonicalFile }.getOrNull() ?: return
        if (!groupFile.isFile || !groupFile.canWrite()) return
        if (!group.path.startsWith(root.path + File.separator)) return
        val androidGroups = linkedMapOf(
            3003 to "android_inet", 9997 to "android_everybody", 20490 to "android_shared_legacy",
            20491 to "android_shared", 20494 to "android_shared_legacy_extra", 50490 to "android_user_legacy",
            50491 to "android_user", 50494 to "android_user_legacy_extra"
        )
        val lines = runCatching { group.readLines(Charsets.UTF_8) }.getOrNull() ?: return
        val existingGids = lines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
        val missing = androidGroups.filterKeys { it !in existingGids }
        if (missing.isEmpty()) return
        runCatching {
            group.appendText(missing.entries.joinToString(separator = "", prefix = if (lines.isEmpty()) "" else "\n") {
                (gid, name) -> "$name:x:$gid:\n"
            }, Charsets.UTF_8)
        }.onFailure { DeveloperLog.failure("UbuntuRuntime", "supplementary_group_repair_failed", it) }
    }
}
