package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaIdentityTest {
    @Test fun sameNumericIdOnDifferentVolumesDoesNotCollide() {
        val primary = MediaIdentity.key("external_primary", "/storage/emulated/0/DCIM/a.jpg", false, 42L)
        val sd = MediaIdentity.key("ABCD-1234", "/storage/ABCD-1234/DCIM/a.jpg", false, 42L)
        assertNotEquals(primary, sd)
    }

    @Test fun imageAndVideoTablesDoNotCollide() {
        val image = MediaIdentity.key("external_primary", "", false, 42L)
        val video = MediaIdentity.key("external_primary", "", true, 42L)
        assertNotEquals(image, video)
    }

    @Test fun legacyPathCanDerivePrimaryAndRemovableVolume() {
        assertEquals("external_primary:image:42", MediaIdentity.key("", "/storage/emulated/0/DCIM/a.jpg", false, 42L))
        assertEquals("ABCD-1234:video:42", MediaIdentity.key("", "/storage/ABCD-1234/DCIM/a.mp4", true, 42L))
    }
}
