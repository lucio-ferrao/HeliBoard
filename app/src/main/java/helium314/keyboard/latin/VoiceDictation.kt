// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.utils.Log

/**
 * Dictation inside the keyboard using the system speech recognizer, so we don't need to switch
 * to a voice input method (some apps hide the keyboard while the input method is switched).
 * Partial results are shown as composing text, each final result is committed.
 * The recognizer ends an utterance on a pause, so we start listening again until the user stops
 * or nothing was said for [MAX_SILENCE] ms.
 */
class VoiceDictation(private val ime: LatinIME) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var stopRequested = false
    private var lastSpeech = 0L
    private var prefix = ""
    private var capitalize = false
    private var lastText = ""
    // the next ERROR_CLIENT comes from our own cancel when restarting a round
    private var ignoreClientError = false
    private var failures = 0
    private val handler = Handler(Looper.getMainLooper())

    val isListening get() = recognizer != null

    /** @return whether listening was started */
    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(ime)) return false
        cancel()
        stopRequested = false
        lastSpeech = SystemClock.elapsedRealtime()
        failures = 0
        val r = SpeechRecognizer.createSpeechRecognizer(ime)
        r.setRecognitionListener(this)
        recognizer = r
        ime.onVoiceDictationStarted()
        listen()
        return true
    }

    /** Stop listening, the text spoken until now is still recognized and committed. */
    fun stop() {
        stopRequested = true
        recognizer?.stopListening()
    }

    /** Stop immediately, keeping what was already shown. */
    fun cancel() {
        val r = recognizer ?: return
        recognizer = null
        handler.removeCallbacksAndMessages(null)
        r.cancel()
        r.destroy()
        commit()
        ime.onVoiceDictationFinished()
    }

    /** Delete the last word and keep listening; what was being said is dropped. */
    fun deleteLastWord() {
        val r = recognizer ?: return
        lastSpeech = SystemClock.elapsedRealtime()
        ignoreClientError = true
        r.cancel()
        commit()
        lastText = ""
        val connection = ime.mInputLogic.mConnection
        val before = connection.getTextBeforeCursor(MAX_WORD_LENGTH, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd()
        val wordStart = trimmed.indexOfLast { it.isWhitespace() } + 1
        if (before.length > wordStart)
            connection.deleteTextBeforeCursor(before.length - wordStart)
        listenLater()
    }

    private fun listen() {
        val r = recognizer ?: return
        Log.i(TAG, "listening, offline ${!offlineUnavailable}")
        ime.mInputLogic.finishInput()
        val before = ime.mInputLogic.mConnection.getTextBeforeCursor(1, 0)
        prefix = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        capitalize = ime.currentAutoCapsState != 0
        lastText = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, RichInputMethodManager.getInstance().currentSubtypeLocale.toLanguageTag())
            // keep the audio on the phone if the recognizer has the language downloaded for offline use
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !offlineUnavailable)
        // ask for automatic punctuation, e.g. a question mark at the end of a question
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        r.startListening(intent)
    }

    private fun setText(recognized: String) {
        if (recognized.isEmpty()) return
        val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
        // spoken punctuation becomes the sign, attached to the previous word, without doubling a sign added by the recognizer
        var text = recognized
        for ((words, sign) in SPOKEN_PUNCTUATION)
            text = text.replace(words, sign)
        text = text.replace(REPEATED_PUNCTUATION, "$1")
            .replace(AFTER_SENTENCE_END) { it.value.uppercase(locale) }
        lastText = text
        val shown = if (capitalize) text.replaceFirstChar { it.uppercase(locale) } else text
        // a sign said on its own goes right after the previous word
        ime.mInputLogic.mConnection.setComposingText((if (shown.first() in ",.?") "" else prefix) + shown, 1)
    }

    private fun commit() {
        if (lastText.isNotEmpty())
            ime.mInputLogic.mConnection.finishComposingText()
    }

    /** End of an utterance: commit it, and listen again unless the user stopped or nobody is talking. */
    private fun endOfRound() {
        val spoke = lastText.isNotEmpty()
        commit()
        lastText = ""
        if (spoke) lastSpeech = SystemClock.elapsedRealtime()
        if (stopRequested || SystemClock.elapsedRealtime() - lastSpeech >= MAX_SILENCE) end()
        else listenLater()
    }

    private fun end() {
        Log.i(TAG, "dictation finished")
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        ime.onVoiceDictationFinished()
    }

    /** Starting again from inside a recognizer callback fails with client or audio errors, so wait a bit. */
    private fun listenLater() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (isListening) listen() }, RESTART_DELAY)
    }

    private fun Bundle?.firstResult() =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onPartialResults(partialResults: Bundle?) {
        if (isListening) setText(partialResults.firstResult())
    }

    override fun onResults(results: Bundle?) {
        if (!isListening) return
        val result = results.firstResult()
        if (result.trim().trimEnd('.', '!', '?').equals(SEND_COMMAND, ignoreCase = true)) {
            // "enviar" said on its own: remove it, stop and press enter, which sends in apps where enter sends
            if (lastText.isNotEmpty()) ime.mInputLogic.mConnection.setComposingText("", 1)
            ime.mInputLogic.mConnection.finishComposingText()
            lastText = ""
            end()
            ime.onVoiceDictationSend()
            return
        }
        setText(result)
        failures = 0
        endOfRound()
    }

    override fun onError(error: Int) {
        if (!isListening) return
        Log.i(TAG, "speech recognition error $error")
        if (error == SpeechRecognizer.ERROR_CLIENT && ignoreClientError) {
            ignoreClientError = false
            return
        }
        if (!offlineUnavailable && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            // the language is not downloaded for offline use, so use the network
            offlineUnavailable = true
            listenLater()
            return
        }
        when (error) {
            // nothing (more) said
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> endOfRound()
            // transient, usually from restarting too quickly: try again a few times
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                commit()
                lastText = ""
                if (++failures <= MAX_FAILURES) listenLater()
                else {
                    end()
                    ime.showVoiceDictationError(ime.getString(R.string.voice_in_keyboard_error, error))
                }
            }
            else -> {
                commit()
                end()
                ime.showVoiceDictationError(ime.getString(
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) R.string.voice_in_keyboard_no_permission
                    else R.string.voice_in_keyboard_error, error))
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private const val TAG = "VoiceDictation"
        private const val MAX_SILENCE = 30_000L
        private const val SEND_COMMAND = "enviar"
        private const val MAX_WORD_LENGTH = 64
        private const val MAX_FAILURES = 3
        private const val RESTART_DELAY = 150L
        // offline recognition failed for the language, don't ask for it again until the keyboard is restarted
        private var offlineUnavailable = false
        private fun spoken(words: String) = Regex("""\s*(?<!\p{L})$words(?!\p{L})""", RegexOption.IGNORE_CASE)
        private val SPOKEN_PUNCTUATION = listOf(
            spoken("ponto de interrogação") to "?",
            spoken("ponto final") to ".",
            spoken("vírgula") to ",",
        )
        private val REPEATED_PUNCTUATION = Regex("""([.?,])[.?,]+""")
        // first letter after a full stop or question mark
        private val AFTER_SENTENCE_END = Regex("""(?<=[.?]\s)\p{Ll}""")
    }
}
