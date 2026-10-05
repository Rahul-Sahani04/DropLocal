package com.droplocal.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.droplocal.app.transfer.TransferSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** URI grants go only to the user-selected Android handler, never to raw file:// paths. */
suspend fun openOrShareFile(context: Context, session: TransferSession, share: Boolean): String? {
    val uri = session.outputUri?.let(Uri::parse) ?: return "This transfer has no saved file."
    if (uri.scheme != "content") return "This file is not available for opening or sharing."
    return try {
        withContext(Dispatchers.IO) {
            checkNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) { "File is no longer available" }.use { }
        }
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = session.mime
            putExtra(Intent.EXTRA_STREAM, uri)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, session.mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("Received file", uri)
        context.startActivity(Intent.createChooser(intent, if (share) "Share file" else "Open file"))
        null
    } catch (e: CancellationException) { throw e }
    catch (_: android.content.ActivityNotFoundException) { "No app can handle this file type. Install a suitable viewer or share it." }
    catch (_: Exception) { "The file is unavailable or access was removed. Receive it again." }
}

fun shareText(context: Context, text: String): String? = try {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "Share received text"))
    null
} catch (_: Exception) { "No app is available to share text." }
