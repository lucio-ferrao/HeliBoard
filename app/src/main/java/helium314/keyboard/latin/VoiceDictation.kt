// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.utils.Log

/**
 * Dictation inside the keyboard using the system speech recognizer, so we don't need to switch
 * to a voice input method (some apps hide the keyboard while the input method is switched).
 * Partial results are shown as composing text, each final result is committed.
 * The recognizer ends an utterance on a pause, so we start listening again until the user stops
 * or nothing was said for [MAX_EMPTY_ROUNDS] rounds.
 */
class VoiceDictation(private val ime: LatinIME) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var stopRequested = false
    private var emptyRounds = 0
    private var prefix = ""
    private var capitalize = false
    private var lastText = ""

    val isListening get() = recognizer != null

    /** @return whether listening was started */
    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(ime)) return false
        cancel()
        stopRequested = false
        emptyRounds = 0
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
        r.cancel()
        r.destroy()
        commit()
        ime.onVoiceDictationFinished()
    }

    private fun listen() {
        val r = recognizer ?: return
        ime.mInputLogic.finishInput()
        val before = ime.mInputLogic.mConnection.getTextBeforeCursor(1, 0)
        prefix = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        capitalize = ime.currentAutoCapsState != 0
        lastText = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, RichInputMethodManager.getInstance().currentSubtypeLocale.toLanguageTag())
        r.startListening(intent)
    }

    private fun setText(text: String) {
        if (text.isEmpty()) return
        lastText = text
        val locale = RichInputMethodManager.getInstance().currentSubtypeLocale
        val shown = if (capitalize) text.replaceFirstChar { it.uppercase(locale) } else text
        ime.mInputLogic.mConnection.setComposingText(prefix + shown, 1)
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
        emptyRounds = if (spoke) 0 else emptyRounds + 1
        if (stopRequested || emptyRounds >= MAX_EMPTY_ROUNDS) {
            recognizer?.destroy()
            recognizer = null
            ime.onVoiceDictationFinished()
        } else {
            listen()
        }
    }

    private fun Bundle?.firstResult() =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onPartialResults(partialResults: Bundle?) {
        if (isListening) setText(partialResults.firstResult())
    }

    override fun onResults(results: Bundle?) {
        if (!isListening) return
        setText(results.firstResult())
        endOfRound()
    }

    override fun onError(error: Int) {
        if (!isListening) return
        Log.i(TAG, "speech recognition error $error")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> endOfRound()
            else -> {
                stopRequested = true
                endOfRound()
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    ime.showVoiceDictationError(ime.getString(R.string.voice_in_keyboard_no_permission))
                else if (error != SpeechRecognizer.ERROR_CLIENT)
                    ime.showVoiceDictationError(ime.getString(R.string.voice_in_keyboard_error, error))
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
        private const val MAX_EMPTY_ROUNDS = 2
    }
}
