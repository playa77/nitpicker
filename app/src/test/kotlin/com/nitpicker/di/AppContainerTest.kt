package com.nitpicker.di
import com.nitpicker.CompanionApp
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    @Test fun applicationConstructsInfrastructure() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val c = (RuntimeEnvironment.getApplication() as CompanionApp).container
        assertNotNull(c.settings); assertNotNull(c.repo); assertNotNull(c.asr)
        assertNotNull(c.segmentation); assertNotNull(c.analysis); assertNotNull(c.exporter)
        assertNotNull(c.transport)
    }
}
