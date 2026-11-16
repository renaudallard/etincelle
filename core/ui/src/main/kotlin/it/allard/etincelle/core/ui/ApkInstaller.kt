// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
        // The closest page the device has: this app's own switch, the list of apps that may install
        // apps, then the security or main settings. Some TVs have none of the first two, and asking
        // for a page nothing handles only gets Android's "no app can do this".
        val opened = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        ).firstOrNull { page ->
            packageManager.queryIntentActivities(page, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() &&
                runCatching { startActivity(page) }.isSuccess
        }
        viewModel.setUpdateError(
            when (opened?.action) {
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES -> "Autorisez etincelle à installer des applications, puis appuyez à nouveau sur Installer."
                null -> "Autorisez etincelle dans Paramètres > Applications > Sécurité et restrictions > Sources inconnues, puis réessayez."
                else -> "Dans Applications > Sécurité et restrictions > Sources inconnues, autorisez etincelle, puis appuyez à nouveau sur Installer."
            },
        )
        return
    }
    val app = applicationContext
    viewModel.installUpdate(File(cacheDir, "update.apk")) { installApk(app, it) }
}

/**
 * Installs the update downloaded for an automatic install, if there is one: called as the app is left
 * with nothing playing, since Android closes the app to install it. Nothing is shown or asked.
 */
fun Activity.installFetchedUpdate(viewModel: MainViewModel) {
    val app = applicationContext
    viewModel.installFetchedUpdate { apk, version -> installApk(app, apk, version) }
}

/**
 * Writes [apk] into a PackageInstaller session and commits it; [InstallResultReceiver] gets the outcome.
 * With [quietVersion], the version of an automatic install, Android is asked not to ask the user.
 */
private suspend fun installApk(context: Context, apk: File, quietVersion: String? = null) = withContext(Dispatchers.IO) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(context.packageName)
    if (quietVersion != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
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
            val outcome = Intent(context, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.QUIET_VERSION, quietVersion)
            val result = PendingIntent.getBroadcast(context, id, outcome, flags)
            session.commit(result.intentSender)
        }
    } catch (e: Exception) {
        installer.abandonSession(id)
        throw e
    }
}

/**
 * Shows the system confirmation for an update install, and says so when the install fails. An automatic
 * install shows nothing: when it needs someone or fails, its version is noted to be proposed instead.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val quietVersion = intent.getStringExtra(QUIET_VERSION)
        if (quietVersion != null) {
            if (status == PackageInstaller.STATUS_SUCCESS) return
            // Android wants a confirmation after all: nobody is there to give it, so drop this attempt.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                runCatching { context.packageManager.packageInstaller.abandonSession(session) }
            }
            AutoUpdate(context).byHand = quietVersion
            return
        }
        when (status) {
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

    companion object {
        /** Intent extra: the version of an automatic install. */
        const val QUIET_VERSION = "quiet_version"
    }
}
