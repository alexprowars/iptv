package home.iptv.feature.sources

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Android TV may resolve document intents to a placeholder that immediately cancels. */
fun filePickerIntent(context: Context): Intent? {
    for (action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
        val request = Intent(action).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (action == Intent.ACTION_OPEN_DOCUMENT) request.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val choices = context.packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs" }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
            .map { Intent(request).setComponent(ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
        if (choices.size == 1) return choices.single()
        if (choices.isNotEmpty()) return Intent.createChooser(choices.first(), "Выбрать файл")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
    }
    return null
}
