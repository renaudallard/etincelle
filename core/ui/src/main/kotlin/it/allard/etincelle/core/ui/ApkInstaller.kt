// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the update [viewModel] proposes: downloads it, then hands it to the system installer, which
 * asks the user to confirm. When this app may not install apps yet, opens the system page that allows
 * it instead, and the prompt says to come back.
 */
fun Activity.installUpdate(viewModel: MainViewModel) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        val opened = runCatching { startActivity(page) }.isSuccess
        viewModel.setUpdateError(
            if (opened) "Autorisez etincelle à installer des applications, puis appuyez à nouveau sur Installer."
            else "Autorisez etincelle à installer des applications dans les paramètres Android, puis réessayez.",
        )
        return
    }
    val app = applicationContext
    viewModel.installUpdate(File(cacheDir, "update.apk")) { installApk(app, it) }
}

/** Writes [apk] into a PackageInstaller session and commits it; [InstallResultReceiver] gets the outcome. */
private suspend fun installApk(context: Context, apk: File) = withContext(Dispatchers.IO) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(context.packageName)
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // The installer fills in the outcome, so the intent must stay mutable; it is explicit.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(result.intentSender)
        }
    } catch (e: Exception) {
        installer.abandonSession(id)
        throw e
    }
}

/** Shows the system confirmation for an update install, and says so when the install fails. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm == null || runCatching { context.startActivity(confirm) }.isFailure) failed(context)
            }
            // Installed (this process is replaced), or the user declined: nothing to say.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> failed(context)
        }
    }

    private fun failed(context: Context) =
        Toast.makeText(context, "Installation de la mise à jour impossible", Toast.LENGTH_LONG).show()
}
