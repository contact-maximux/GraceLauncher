package com.galaxyrio.gracelauncher

import com.galaxyrio.gracelauncher.data.ListAppearance
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteLabelShadowTest {
    @Test fun defaultsMatchTheRequestedFavoriteLabelShadow() {
        val settings = ListAppearance()
        assertEquals(95, settings.favoriteLabelShadowOpacity)
        assertEquals(2, settings.favoriteLabelShadowBlur)
        assertEquals(0, settings.favoriteLabelShadowX)
        assertEquals(1, settings.favoriteLabelShadowY)
    }

    @Test fun shadowSettingsSurviveSerializationAndOldSettingsUseDefaults() {
        val settings = ListAppearance(favoriteLabelShadowOpacity = 72, favoriteLabelShadowBlur = 7,
            favoriteLabelShadowX = -3, favoriteLabelShadowY = 5)
        assertEquals(settings, ListAppearance.decode(settings.encode()))
        assertEquals(settings.copy(favoriteLabelShadowOpacity = 95, favoriteLabelShadowBlur = 2,
            favoriteLabelShadowX = 0, favoriteLabelShadowY = 1), ListAppearance.decode("{}"))
    }

    @Test fun shadowSettingsAreClampedToTheirSupportedRanges() {
        val settings = ListAppearance(favoriteLabelShadowOpacity = 120, favoriteLabelShadowBlur = -1,
            favoriteLabelShadowX = -20, favoriteLabelShadowY = 20).normalized()
        assertEquals(100, settings.favoriteLabelShadowOpacity)
        assertEquals(0, settings.favoriteLabelShadowBlur)
        assertEquals(-16, settings.favoriteLabelShadowX)
        assertEquals(16, settings.favoriteLabelShadowY)
    }
}
