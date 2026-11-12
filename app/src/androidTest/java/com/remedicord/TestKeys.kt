package com.remedicord

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Test-only cleanup: removes a test database key (its key file and its Android Keystore entry).
 * Lives with the tests, so the app itself contains no code for deleting keys.
 * Tests create their keys as DbKeyManager(context, alias, "$alias.bin").
 */
fun deleteTestKey(context: Context, alias: String) {
    File(context.noBackupFilesDir, "$alias.bin").delete()
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
}
