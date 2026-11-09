package nodomain.freeyourgadget.gadgetbridge.util.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

class SharedPreferencesDataSourceTest : TestBase() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var dataSource: SharedPreferencesDataSource

    @Before
    fun setUpDataSource() {
        sharedPreferences = app.getSharedPreferences("data_source_test", Context.MODE_PRIVATE)
        sharedPreferences.edit(commit = true) { clear() }
        dataSource = SharedPreferencesDataSource(sharedPreferences)
    }

    @Test
    fun missingOrEmptyStringReturnsTheDefault() {
        assertEquals("fallback", dataSource.getString("key", "fallback"))
        assertNull(dataSource.getString("key", null))

        sharedPreferences.edit(commit = true) { putString("key", "") }
        assertEquals("fallback", dataSource.getString("key", "fallback"))

        sharedPreferences.edit(commit = true) { putString("key", "value") }
        assertEquals("value", dataSource.getString("key", "fallback"))
    }

    @Test
    fun booleanStoredAsStringIsParsed() {
        assertTrue(dataSource.getBoolean("key", true))

        sharedPreferences.edit(commit = true) { putString("key", "false") }
        assertFalse(dataSource.getBoolean("key", true))

        sharedPreferences.edit(commit = true) { putBoolean("key", true) }
        assertTrue(dataSource.getBoolean("key", false))
    }

    @Test
    fun editWritesToTheFile() {
        dataSource.edit(commit = true) { putString("key", "value") }
        assertEquals("value", sharedPreferences.getString("key", null))

        dataSource.edit { remove("key") }
        assertFalse(sharedPreferences.contains("key"))
    }

    @Test
    fun changedKeysOnlyEmitsWhileCollected() {
        val changedKeys = mutableListOf<String?>()
        dataSource.edit(commit = true) { putString("before", "0") }

        val collection = dataSource.changedKeys()
            .onEach { changedKeys.add(it) }
            .launchIn(CoroutineScope(Dispatchers.Main.immediate))
        dataSource.edit(commit = true) { putString("first", "1") }
        dataSource.edit { putString("second", "2") }
        collection.cancel()
        dataSource.edit(commit = true) { putString("third", "3") }

        assertEquals(listOf<String?>("first", "second"), changedKeys)
    }

    @Test
    @Config(sdk = [35])
    fun changedKeysEmitsNullWhenTheFileIsCleared() {
        val changedKeys = mutableListOf<String?>()
        val collection = dataSource.changedKeys()
            .onEach { changedKeys.add(it) }
            .launchIn(CoroutineScope(Dispatchers.Main.immediate))

        dataSource.edit(commit = true) { clear() }
        collection.cancel()

        assertEquals(listOf<String?>(null), changedKeys)
    }
}
