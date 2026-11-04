package com.voiceprompter.prompter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceprompter.match.MatchMode
import com.voiceprompter.match.Normalizer
import com.voiceprompter.match.PhraseMatcher
import com.voiceprompter.match.ScriptSegmenter
import com.voiceprompter.speech.ModelManager
import com.voiceprompter.speech.TtsEngine
import com.voiceprompter.speech.VoskRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The half-duplex prompter loop.
 *
 * v1 deliberately never listens while the app is speaking. The microphone is
 * paused for the duration of each TTS utterance and resumed afterwards. That
 * sidesteps the feedback problem entirely: without it, the recogniser hears
 * the app's own synthesised voice, scores it as a perfect match, and the
 * script races to the end on its own.
 *
 * The cost is that true mid-sentence ad-lib detection does not work in this
 * mode — the app only notices an ad-lib once it is listening. Full-duplex
 * (speaker preaching live, TTS silent or routed to an earpiece) is the v2
 * mode, and [PhraseMatcher.resync] is already written for it.
 */
class PrompterViewModel(application: Application) : AndroidViewModel(application) {

    private val tts = TtsEngine(application)
    private val modelManager = ModelManager(application)

    private var recognizer: VoskRecognizer? = null
    private var recognitionJob: Job? = null
    private var speakJob: Job? = null

    private val _state = MutableStateFlow(PrompterState())
    val state: StateFlow<PrompterState> = _state.asStateFlow()

    /** Consecutive non-matching final results before we call it an ad-lib. */
    private var misses = 0

    fun loadScript(body: String) {
        val phrases = ScriptSegmenter.segment(body)
        _state.update {
            it.copy(
                phrases = phrases,
                currentIndex = 0,
                phraseProgress = 0f,
                lastHeard = "",
                lastScore = 0.0,
                status = PrompterStatus.IDLE,
                error = null,
            )
        }
    }

    fun setMatchMode(mode: MatchMode) = _state.update { it.copy(matchMode = mode) }

    fun setSpeechRate(rate: Float) = _state.update { it.copy(speechRate = rate) }

    fun setPitch(pitch: Float) = _state.update { it.copy(pitch = pitch) }

    /**
     * Call only after RECORD_AUDIO has been granted.
     */
    fun start() {
        if (_state.value.phrases.isEmpty()) return
        if (_state.value.isRunning) return

        viewModelScope.launch {
            _state.update { it.copy(status = PrompterStatus.PREPARING, error = null) }

            if (!tts.isReady && !tts.init()) {
                fail("Text-to-speech is not available on this device.")
                return@launch
            }

            val modelDir = modelManager.ensureModel(
                onProgress = { p -> _state.update { it.copy(modelProgress = p) } },
            ).getOrElse { cause ->
                fail("Could not prepare the speech model: ${cause.message}")
                return@launch
            }

            val voskRecognizer = VoskRecognizer(modelDir)
            recognizer = voskRecognizer

            recognitionJob = launch {
                voskRecognizer.listen().collect { event ->
                    when (event) {
                        is VoskRecognizer.Event.Partial -> onPartial(event.text)
                        is VoskRecognizer.Event.Final -> onFinal(event.text)
                        is VoskRecognizer.Event.SilenceTimeout -> Unit
                        is VoskRecognizer.Event.Failed ->
                            fail("Microphone error: ${event.cause.message}")
                    }
                }
            }

            speakCurrentPhrase()
        }
    }

    fun pause() {
        speakJob?.cancel()
        tts.stop()
        recognizer?.pause(true)
        _state.update { it.copy(status = PrompterStatus.PAUSED) }
    }

    fun resume() {
        if (_state.value.status != PrompterStatus.PAUSED) return
        speakCurrentPhrase()
    }

    fun stop() {
        speakJob?.cancel()
        recognitionJob?.cancel()
        tts.stop()
        recognizer?.release()
        recognizer = null
        _state.update { it.copy(status = PrompterStatus.IDLE, phraseProgress = 0f) }
    }

    /** Manual navigation — the volume keys and on-screen controls route here. */
    fun jumpTo(index: Int) {
        val clamped = index.coerceIn(0, (_state.value.phrases.size - 1).coerceAtLeast(0))
        misses = 0
        _state.update { it.copy(currentIndex = clamped, phraseProgress = 0f, lastHeard = "") }
        if (_state.value.isRunning || _state.value.status == PrompterStatus.PAUSED) {
            speakCurrentPhrase()
        }
    }

    fun next() = jumpTo(_state.value.currentIndex + 1)

    fun previous() = jumpTo(_state.value.currentIndex - 1)

    // --- loop internals ---------------------------------------------------

    private fun speakCurrentPhrase() {
        val snapshot = _state.value
        val phrase = snapshot.currentPhrase
        if (phrase == null) {
            _state.update { it.copy(status = PrompterStatus.FINISHED) }
            return
        }

        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            // Mic off while we speak. This is the whole half-duplex trick.
            recognizer?.pause(true)
            _state.update { it.copy(status = PrompterStatus.SPEAKING, phraseProgress = 0f) }

            tts.speak(phrase.text, rate = snapshot.speechRate, pitch = snapshot.pitch)

            misses = 0
            _state.update { it.copy(status = PrompterStatus.LISTENING, lastHeard = "") }
            recognizer?.pause(false)
        }
    }

    private fun onPartial(text: String) {
        val snapshot = _state.value
        if (snapshot.status != PrompterStatus.LISTENING && snapshot.status != PrompterStatus.AD_LIB) {
            return
        }
        val phrase = snapshot.currentPhrase ?: return

        val heard = Normalizer.tokenize(text)
        val progress = PhraseMatcher.progress(phrase.tokens, heard)
        _state.update { it.copy(phraseProgress = progress.toFloat(), lastHeard = text) }

        // Advance on a strong partial rather than waiting for the recogniser to
        // settle, so the next phrase starts while the speaker is still finishing
        // the last word. Without this the app feels a beat behind.
        val result = PhraseMatcher.score(phrase.tokens, heard)
        if (result.advances(snapshot.matchMode)) {
            acceptAndAdvance(result.score)
        }
    }

    private fun onFinal(text: String) {
        val snapshot = _state.value
        if (snapshot.status != PrompterStatus.LISTENING && snapshot.status != PrompterStatus.AD_LIB) {
            return
        }
        val phrase = snapshot.currentPhrase ?: return

        val heard = Normalizer.tokenize(text)
        if (heard.isEmpty()) return

        val result = PhraseMatcher.score(phrase.tokens, heard)
        _state.update { it.copy(lastHeard = text, lastScore = result.score) }

        if (result.advances(snapshot.matchMode)) {
            acceptAndAdvance(result.score)
            return
        }

        // Not the current phrase. Maybe the speaker jumped elsewhere.
        val resync = PhraseMatcher.resync(
            phrases = snapshot.phrases.map { it.tokens },
            heard = heard,
            currentIndex = snapshot.currentIndex,
            mode = snapshot.matchMode,
        )

        if (resync != null && resync.index != snapshot.currentIndex) {
            misses = 0
            _state.update {
                it.copy(
                    currentIndex = resync.index,
                    phraseProgress = 1f,
                    lastScore = resync.result.score,
                    status = PrompterStatus.LISTENING,
                )
            }
            advanceFrom(resync.index)
            return
        }

        // Genuinely off-script. Hold position and keep listening.
        misses++
        if (misses >= 2 && snapshot.status != PrompterStatus.AD_LIB) {
            _state.update { it.copy(status = PrompterStatus.AD_LIB) }
        }
    }

    private fun acceptAndAdvance(score: Double) {
        val snapshot = _state.value
        _state.update { it.copy(phraseProgress = 1f, lastScore = score) }
        advanceFrom(snapshot.currentIndex)
    }

    private fun advanceFrom(index: Int) {
        misses = 0
        val nextIndex = index + 1
        if (nextIndex >= _state.value.phrases.size) {
            recognizer?.pause(true)
            _state.update { it.copy(status = PrompterStatus.FINISHED, phraseProgress = 1f) }
            return
        }
        _state.update { it.copy(currentIndex = nextIndex) }
        speakCurrentPhrase()
    }

    private fun fail(message: String) {
        _state.update { it.copy(status = PrompterStatus.ERROR, error = message) }
    }

    override fun onCleared() {
        stop()
        tts.shutdown()
        super.onCleared()
    }
}
