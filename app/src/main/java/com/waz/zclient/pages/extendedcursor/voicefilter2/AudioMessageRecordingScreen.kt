package com.waz.zclient.pages.extendedcursor.voicefilter2

import android.content.Context
import android.media.AudioTrack
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.widget.ViewAnimator
import com.waz.api.AudioEffect
import com.waz.zclient.R
import com.waz.zclient.audio.AudioService
import com.waz.zclient.audio.AudioServiceImpl
import com.waz.zclient.ui.animation.interpolators.penner.Expo
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.audio_message_recording_screen.view.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.fixedRateTimer

class AudioMessageRecordingScreen @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewAnimator(context, attrs), View.OnClickListener {

    companion object {
        var GLYPH_PLACEHOLDER = "_GLYPH_"

        enum class CenterButton {
            RECORD_START, RECORD_STOP, CONFIRM
        }

        interface Listener {
            fun onCenterButtonPressed(button: CenterButton)
            fun onLeftButtonPressed()
            fun onRightButtonPressed()
        }
    }


    private val audioService: AudioService = AudioServiceImpl(context)
    private val recordFile: File = File(context.cacheDir, "record_temp.pcm")
    private val recordWithEffectFile: File = File(context.cacheDir, "record_with_effect_temp.pcm")
    private val normalizedRecordLevels: MutableList<Float> = mutableListOf()
    private var audioTrack: AudioTrack? = null

    private lateinit var currentCenterButton: CenterButton
    private var listener: Listener? = null

    private var recordingDisposable: Disposable? = null

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.audio_message_recording_screen, this, true)


        center_button.setOnClickListener(this)
        left_button.setOnClickListener(this)
        right_button.setOnClickListener(this)

        voice_filter_none.setOnClickListener(this)
        voice_filter_balloon.setOnClickListener(this)
        voice_filter_jelly_fish.setOnClickListener(this)
        voice_filter_rabbit.setOnClickListener(this)
        voice_filter_church.setOnClickListener(this)
        voice_filter_alien.setOnClickListener(this)
        voice_filter_robot.setOnClickListener(this)
        voice_filter_rollercoaster.setOnClickListener(this)

        val original = resources.getString(R.string.audio_message__recording__tap_to_record)

        val indexWrap = original.indexOf('\n')
        val indexGlyph = original.indexOf(GLYPH_PLACEHOLDER)
        val indexGlyphEnd = indexGlyph + GLYPH_PLACEHOLDER.length

        ttv__voice_filter__tap_to_record_1st_line.text = original.substring(0, indexWrap)
        ttv__voice_filter__tap_to_record_2nd_line_begin.text = original.substring(indexWrap + 1, indexGlyph)
        ttv__voice_filter__tap_to_record_2nd_line_end.text = original.substring(indexGlyphEnd)

        showAudioRecordingHint()
    }


    private fun showAudioRecordingHint() {
        audio_recording_container.visibility = View.VISIBLE
        audio_recording_hint_container.visibility = View.VISIBLE
        wave_graph_view.visibility = View.GONE
        audio_filters_container.visibility = View.GONE

        setCenterButton(Companion.CenterButton.RECORD_START)
        left_button.visibility = View.GONE
        right_button.visibility = View.GONE
    }

    private fun showAudioRecordingInProgress() {
        wave_graph_view.visibility = View.VISIBLE
        audio_recording_hint_container.visibility = View.GONE
        audio_filters_container.visibility = View.GONE

        setCenterButton(Companion.CenterButton.RECORD_STOP)
        left_button.visibility = View.GONE
        right_button.visibility = View.GONE
    }

    private fun showAudioFilters() {
        audio_recording_container.visibility = View.GONE
        audio_filters_container.visibility = View.VISIBLE

        setCenterButton(Companion.CenterButton.CONFIRM)
        left_button.visibility = View.VISIBLE
        right_button.visibility = View.VISIBLE
    }

    override fun setInAnimation(inAnimation: Animation) {
        inAnimation.startOffset = resources.getInteger(R.integer.camera__control__ainmation__in_delay).toLong()
        inAnimation.interpolator = Expo.EaseOut()
        inAnimation.duration = context.resources.getInteger(R.integer.calling_animation_duration_medium).toLong()
        super.setInAnimation(inAnimation)
    }

    override fun setOutAnimation(outAnimation: Animation) {
        outAnimation.interpolator = Expo.EaseIn()
        outAnimation.duration = context.resources.getInteger(R.integer.calling_animation_duration_medium).toLong()
        super.setOutAnimation(outAnimation)
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setAccentColor(color: Int) {
        wave_graph_view.setAccentColor(color)
        wave_bin_view.setAccentColor(color)
    }

    private fun setCenterButton(button: CenterButton) {
        when (button) {
            Companion.CenterButton.RECORD_START -> center_button.setText(R.string.glyph__record)
            Companion.CenterButton.RECORD_STOP -> center_button.setText(R.string.glyph__stop)
            Companion.CenterButton.CONFIRM -> center_button.setText(R.string.glyph__check)
        }
        currentCenterButton = button
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.left_button ->
                showAudioRecordingHint()
            R.id.right_button ->
                listener?.onRightButtonPressed()
            R.id.center_button -> when (currentCenterButton) {
                Companion.CenterButton.RECORD_START -> startRecording()
                Companion.CenterButton.RECORD_STOP -> stopRecording()
                Companion.CenterButton.CONFIRM -> sendRecording()
            }

            R.id.voice_filter_none ->
                applyAudioEffectAndPlay(AudioEffect.NONE)
            R.id.voice_filter_balloon ->
                applyAudioEffectAndPlay(AudioEffect.PITCH_UP_INSANE)
            R.id.voice_filter_jelly_fish ->
                applyAudioEffectAndPlay(AudioEffect.PITCH_DOWN_INSANE)
            R.id.voice_filter_rabbit ->
                applyAudioEffectAndPlay(AudioEffect.PACE_UP_MED)
            R.id.voice_filter_church ->
                applyAudioEffectAndPlay(AudioEffect.REVERB_MAX)
            R.id.voice_filter_alien ->
                applyAudioEffectAndPlay(AudioEffect.CHORUS_MAX)
            R.id.voice_filter_robot ->
                applyAudioEffectAndPlay(AudioEffect.VOCODER_MED)
            R.id.voice_filter_rollercoaster ->
                applyAudioEffectAndPlay(AudioEffect.PITCH_UP_DOWN_MAX)

            else -> {}
        }
    }

    fun startRecording() {
        showAudioRecordingInProgress()
        recordFile.delete()
        normalizedRecordLevels.clear()
        wave_graph_view.keepScreenOn = true

        recordingDisposable = audioService.withAudioFocus()
            .flatMap { audioService.recordPcmAudio(recordFile) }
            .subscribe({ progress ->
                val normalizedAudioLevel = normalizeAudioLoudness(progress.maxAmplitude)
                normalizedRecordLevels.add(normalizedAudioLevel)
                wave_graph_view.setMaxAmplitude(normalizedAudioLevel)
            }, { error ->
                println("Error while recording $error")
                wave_graph_view.keepScreenOn = false
            })
    }

    fun stopRecording() {
        wave_graph_view.keepScreenOn = false
        showAudioFilters()
        recordingDisposable?.dispose()
    }

    fun sendRecording() {

    }

    fun applyAudioEffectAndPlay(effect: AudioEffect) {
        val avsEffects = com.waz.audioeffect.AudioEffect()
        try {
            val res = avsEffects.applyEffectPCM(
                recordFile.absolutePath,
                recordWithEffectFile.absolutePath,
                AudioService.Companion.Pcm.sampleRate,
                effect.avsOrdinal,
                true)

            if (res < 0) throw RuntimeException("applyEffectWav returned error code: $res")
            playAudio()
        } catch (ex: Exception) {
            println("Exception while applying audio effect. $ex")
        } finally {
            avsEffects.destroy()
        }
    }

    fun playAudio() {
        audio_filters_hint.visibility = View.GONE
        audioTrack?.stop()

        val preparedAudioTrack = audioService.preparePcmAudioTrack(recordWithEffectFile)
        audioTrack = preparedAudioTrack
        preparedAudioTrack.play()

        val audioDuration = AudioService.Companion.Pcm
            .durationInMillisFromByteCount(recordWithEffectFile.length())

        wave_bin_view.setAudioLevels(prepareAudioLevels(normalizedRecordLevels.toFloatArray(), 56))

        fixedRateTimer(
            "displaying_pcm_progress_$recordWithEffectFile",
            false,
            0L,
            50
        ) {
            val currentDuration = AudioService.Companion.Pcm
                .durationFromInMillisFromSampleCount(preparedAudioTrack.playbackHeadPosition.toLong())

            time_label.post {
                wave_bin_view.setAudioPlayingProgress(currentDuration, audioDuration)
                time_label.text = TimeUnit.MILLISECONDS.toSeconds(currentDuration).toString()
            }

            if (preparedAudioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) cancel()
        }
    }

    private fun normalizeAudioLoudness(level: Int): Float {
        val n = Math.min(Math.max(Short.MIN_VALUE.toInt(), level), Short.MAX_VALUE.toInt())
        val doubleValue = if (n < 0) n.toDouble() / -32768 else n.toDouble() / 32767
        val dbfsSquare = 20 * Math.log10(Math.abs(doubleValue))

        return Math.pow(2.0, Math.min(dbfsSquare, 0.0) / 10.0).toFloat()
    }

    private fun prepareAudioLevels(levels: FloatArray, levelsCount: Int): FloatArray = when {
        levels.size <= 1 -> {
            FloatArray(levelsCount)
        }
        levels.size < levelsCount -> {
            val interpolation = LinearInterpolation(levels, levelsCount)
            FloatArray(levelsCount) { i -> interpolation.interpolate(i) }
        }
        else -> {
            val dx = levels.size.toFloat() / levelsCount
            FloatArray(levelsCount) { i ->
                (i * dx).toInt().rangeTo(((i + 1f) * dx).toInt())
                    .map { levels.getOrNull(it) ?: 0.0F }
                    .max()!!
            }
        }
    }

}