package com.openandroidintelligence.mobile

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InputPlatformRegressionTest {
    @Test fun mainWindowResizesInsteadOfPanningTheFocusedComposer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test fun voiceInputLaunchesEvenWhenThePackageQueryCannotSeeARecognizer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNull(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(app.packageManager))
        var launched: Intent? = null
        launchVoiceInput({ launched = it }, { fail("An empty package query must not block launch") })
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, launched?.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, launched?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
    }

    @Test fun aMissingRecognitionActivityProducesTheUnavailableState() {
        var unavailable = 0
        launchVoiceInput({ throw ActivityNotFoundException() }, { unavailable++ })
        assertEquals(1, unavailable)
    }
}
