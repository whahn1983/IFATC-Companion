package com.h3consultingpartners.ifatccompanion.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import java.util.Locale

/**
 * Push-to-talk speech recognition — the Android counterpart of iOS's `SFSpeechRecognizer`
 * key-down/key-up capture.
 *
 * It is deliberately **on-device only**: `EXTRA_PREFER_OFFLINE` keeps a pilot's radio calls
 * from being sent to a speech service, which matches what the privacy policy says the app
 * does with audio (nothing leaves the device) and is also the only thing that works on a
 * hotel Wi-Fi with no working uplink. Where no offline model is installed, recognition
 * simply fails and the pilot uses the buttons — the app never quietly falls back to a
 * network recognizer.
 *
 * The recognizer must be created and driven on the main thread; that is enforced by the
 * caller (the ViewModel runs on the main dispatcher), not re-checked here.
 */
class PushToTalkRecognizer(
    private val context: Context,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
) {

    private var recognizer: SpeechRecognizer? = null

    /** Whether this device has a recognition service at all. */
    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isAvailable) {
            onError(NO_RECOGNIZER)
            diagnostics.log(
                DiagnosticCategory.AUDIO,
                level = DiagnosticLevel.WARNING,
                message = "Push-to-talk unavailable — no speech recognition service installed",
            )
            return
        }

        // Tear the previous session down completely, not just stopListening() — otherwise
        // it is never destroyed and its callbacks keep arriving.
        release()

        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        engine.setRecognitionListener(
            Session(engine, onPartial, onFinal, onError),
        )
        engine.startListening(intent())
    }

    /**
     * Release the key. `stopListening` asks for a final result from what was captured,
     * which is the push-to-talk contract — the pilot finished speaking, so transcribe what
     * they said rather than discarding it.
     */
    fun stop() {
        val engine = recognizer ?: return
        runCatching { engine.stopListening() }
    }

    /** Tear the recognizer down entirely — on cancel, or when the screen goes away. */
    fun release() {
        val engine = recognizer ?: return
        recognizer = null
        runCatching { engine.cancel() }
        runCatching { engine.destroy() }
    }

    /**
     * One press-and-hold, bound to the recognizer that serves it.
     *
     * Holding the engine here is what makes a late callback harmless: a superseded session
     * can see that it is no longer the current recognizer and do nothing, instead of
     * reporting its result and destroying whichever recognizer happens to be live.
     */
    private inner class Session(
        private val engine: SpeechRecognizer,
        private val onPartial: (String) -> Unit,
        private val onFinal: (String) -> Unit,
        private val onError: (String) -> Unit,
    ) : RecognitionListener {

        private val isCurrent: Boolean get() = recognizer === engine

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent) return
            firstResult(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent) return
            val text = firstResult(results)
            release()
            if (text.isNullOrBlank()) onError(NOTHING_HEARD) else onFinal(text)
        }

        override fun onError(error: Int) {
            if (!isCurrent) return
            release()
            val message = describe(error)
            diagnostics.log(
                DiagnosticCategory.AUDIO,
                level = DiagnosticLevel.WARNING,
                message = "Push-to-talk: $message",
            )
            onError(message)
        }

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // On-device only: a pilot's transmissions are not sent anywhere.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /**
     * Plain-language reasons, because these reach the pilot. "Error 7" would tell them
     * nothing; "Didn't catch that" tells them to press and speak again.
     */
    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone unavailable."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition stopped unexpectedly."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed to speak."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "No on-device speech model — install one in system settings to use push-to-talk."
        SpeechRecognizer.ERROR_NO_MATCH -> NOTHING_HEARD
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still finishing the last transmission."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "On-device recognition is unavailable right now."
        else -> "Speech recognition failed."
    }

    companion object {
        const val NOTHING_HEARD = "Didn't catch that — press and hold, then speak."
        const val NO_RECOGNIZER = "This device has no speech recognition installed."
    }
}
