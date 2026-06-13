// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** Authenticated session: tokens plus the identity needed for content/playback headers. */
data class UserSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String,
    val profileId: String,
)

/** Domain-level failures surfaced to the UI, carrying user-facing French messages. */
sealed class AppError(message: String) : Exception(message) {
    data object Unauthorized : AppError("Session expirée, reconnectez-vous")
    data object GeoBlocked : AppError("Non disponible dans votre région")
    data object NotEntitled : AppError("Réservé aux abonnés")
    data class Network(val reason: String) : AppError(reason)
    data class Unknown(val reason: String) : AppError(reason)
}
