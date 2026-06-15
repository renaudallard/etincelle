// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.annotation.DrawableRes

/** The brand-pack icon for a category or genre title (drawn on a tile that has no artwork); null if none. */
@DrawableRes
fun categoryIconRes(title: String?): Int? = when (title?.trim()?.lowercase()) {
    "films", "cinéma", "cinema" -> R.drawable.ic_films
    "séries", "series" -> R.drawable.ic_series
    "divertissement" -> R.drawable.ic_divertissement
    "sport", "sports" -> R.drawable.ic_sport
    "informations", "information", "info", "actualités", "actualites", "actu" -> R.drawable.ic_informations
    "documentaires", "documentaire", "découverte", "decouverte", "docs" -> R.drawable.ic_documentaires
    "enfants", "enfant", "jeunesse" -> R.drawable.ic_enfants
    "culture" -> R.drawable.ic_culture
    "toutes les chaînes", "toutes les chaines", "chaînes", "chaines" -> R.drawable.ic_chaines
    "favoris", "favori", "mes favoris" -> R.drawable.ic_favoris
    "à la une", "a la une" -> R.drawable.ic_alaune
    "populaire", "populaires", "populaire sur molotov", "populaires sur molotov" -> R.drawable.ic_populaires
    "action", "action & aventure" -> R.drawable.ic_action
    "aventure" -> R.drawable.ic_aventure
    "animation" -> R.drawable.ic_animation
    "comédie", "comedie", "comédies", "comedies" -> R.drawable.ic_comedie
    "drame", "drames" -> R.drawable.ic_drame
    "policier", "policiers", "polar" -> R.drawable.ic_policier
    "thriller", "thrillers" -> R.drawable.ic_thriller
    "horreur", "épouvante", "epouvante", "horreur & épouvante" -> R.drawable.ic_horreur
    "western", "westerns" -> R.drawable.ic_western
    "guerre" -> R.drawable.ic_guerre
    "historique", "histoire" -> R.drawable.ic_historique
    "fantastique", "science-fiction", "science fiction", "sci-fi" -> R.drawable.ic_fantastique
    "famille", "familial" -> R.drawable.ic_famille
    "biopic" -> R.drawable.ic_biopic
    "espionnage" -> R.drawable.ic_espionnage
    "catastrophe" -> R.drawable.ic_catastrophe
    "opéra", "opera" -> R.drawable.ic_opera
    "sentimental", "romance", "romantique" -> R.drawable.ic_sentimental
    "érotique", "erotique" -> R.drawable.ic_erotique
    else -> null
}
