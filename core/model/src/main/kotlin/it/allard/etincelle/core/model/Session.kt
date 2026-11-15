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

/** A viewer profile of the account; [avatarId] picks one of [Profiles.avatars]. */
data class Profile(val id: String, val name: String, val avatarId: Int?, val avatarUrl: String?)

data class Avatar(val id: Int, val url: String)

/** The account's profiles, the avatars a profile can take, and how many profiles it may have. */
data class Profiles(val profiles: List<Profile>, val avatars: List<Avatar>, val maxAllowed: Int)

/** Domain-level failures surfaced to the UI, carrying user-facing French messages. */
sealed class AppError(message: String) : Exception(message) {
    data object Unauthorized : AppError("Session expirée, reconnectez-vous")
    // A 403 on a still-valid session: forbidden for geo or entitlement reasons, not an expired session.
    data object Forbidden : AppError("Contenu non disponible avec votre abonnement ou dans votre région")
    data object GeoBlocked : AppError("Non disponible dans votre région")
    data object NotEntitled : AppError("Réservé aux abonnés")
    data class Network(val reason: String) : AppError(reason)
    data class Unknown(val reason: String) : AppError(reason)
}
