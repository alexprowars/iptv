package home.iptv

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import home.iptv.feature.sources.filePickerIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FilePickerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun register(action: String, pkg: String, exported: Boolean = true) {
        val intent = Intent(action).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        val result = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Picker"
                this.exported = exported
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, result)
    }

    @Test fun televisionPlaceholderIsNotOfferedAsAFilePicker() {
        register(Intent.ACTION_OPEN_DOCUMENT, "com.android.tv.frameworkpackagestubs")
        assertNull(filePickerIntent(context))
    }

    @Test fun persistentDocumentProviderTakesPriorityOverContentPicker() {
        register(Intent.ACTION_OPEN_DOCUMENT, "test.documents")
        register(Intent.ACTION_GET_CONTENT, "test.files")
        val intent = filePickerIntent(context)!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("test.documents", intent.component!!.packageName)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test fun contentPickerWorksWhenOnlyDocumentStubExists() {
        register(Intent.ACTION_OPEN_DOCUMENT, "com.android.tv.frameworkpackagestubs")
        register(Intent.ACTION_GET_CONTENT, "test.files")
        val intent = filePickerIntent(context)!!
        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("test.files", intent.component!!.packageName)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    @Test fun privateActivityCannotBeUsedAsAPicker() {
        register(Intent.ACTION_OPEN_DOCUMENT, "test.private", exported = false)
        assertNull(filePickerIntent(context))
    }
}
