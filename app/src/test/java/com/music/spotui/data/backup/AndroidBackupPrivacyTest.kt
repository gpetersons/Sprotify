package com.music.spotui.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AndroidBackupPrivacyTest {
    @Test fun androidAutomaticBackupIsExplicitlyDisabled() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val rules = File("src/main/res/xml/backup_rules.xml").readText()
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("domain=\"root\" path=\".\""))
        assertTrue(extraction.contains("domain=\"root\" path=\".\""))
        assertTrue(extraction.contains("<device-transfer>"))
    }
    @Test fun manualBackupNeverExportsLoginSecrets() {
        listOf("sp_dc", "youtube_cookie", "session_secret", "password", "arl").forEach { secret ->
            assertFalse(BackupPreferencePolicy.allowed("test", secret))
        }
    }
}
