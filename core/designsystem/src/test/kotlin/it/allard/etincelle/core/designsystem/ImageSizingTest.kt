// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSizingTest {
    @Test
    fun `artwork is asked for as WebP at its width rounded up`() {
        assertEquals("https://imgs.fubo.tv/a/b.jpg?fm=webp&w=480", sizedArtworkUrl("https://imgs.fubo.tv/a/b.jpg", 390))
        assertEquals("https://imgs.fubo.tv/a/b.jpg?fm=webp&w=160", sizedArtworkUrl("https://imgs.fubo.tv/a/b.jpg", 160))
    }

    @Test
    fun `a width past the largest artwork is capped`() {
        assertEquals("https://images-eu.fubo.tv/x?fm=webp&w=1920", sizedArtworkUrl("https://images-eu.fubo.tv/x", 2400))
    }

    @Test
    fun `an unknown width still asks for WebP`() {
        assertEquals("https://molotov-imgx.fubo.tv/x.jpg?fm=webp", sizedArtworkUrl("https://molotov-imgx.fubo.tv/x.jpg", null))
    }

    @Test
    fun `an address with a query keeps it`() {
        assertEquals("https://imgs.fubo.tv/x.jpg?v=2&fm=webp&w=320", sizedArtworkUrl("https://imgs.fubo.tv/x.jpg?v=2", 300))
    }

    @Test
    fun `other hosts and addresses that already say a size are left alone`() {
        assertNull(sizedArtworkUrl("https://example.com/x.jpg", 300))
        assertNull(sizedArtworkUrl("https://imgs.fubo.tv/x.jpg?w=200", 300))
        assertNull(sizedArtworkUrl("https://imgs.fubo.tv/x.jpg?fm=png", 300))
    }
}
