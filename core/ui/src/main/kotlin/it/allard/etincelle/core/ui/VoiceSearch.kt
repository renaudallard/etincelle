// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Saying a search instead of typing it: the device's own speech recogniser is asked to listen, with
 * its own screen, and hands back what it heard. Nothing is recorded by this app, which needs no
 * permission for it: the recogniser does the listening. On a phone that is Google's speech service;
 * on a Google TV its voice input, which listens through the remote's microphone.
 */
object VoiceSearch {
    /**
     * What asks the recogniser to listen for a search, in French as the service's titles are; null
     * when this device has none (the manifest names the request among its queries, to be let see
     * them). Where several answer, as on a Google TV, the first in the system's order is asked,
     * rather than the viewer which one, each time.
     */
    fun request(context: Context): Intent? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que voulez-vous regarder ?")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        @Suppress("DEPRECATION")
        val first = context.packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo ?: return null
        return intent.setClassName(first.packageName, first.name)
    }

    /** What was heard, from the recogniser's answer; null when nothing was, or it was given up. */
    fun heard(resultCode: Int, data: Intent?): String? =
        data?.takeIf { resultCode == Activity.RESULT_OK }
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
