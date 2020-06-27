package com.github.ashutoshgngwr.noice.sound.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.mediarouter.media.MediaRouter
import com.github.ashutoshgngwr.noice.R
import com.github.ashutoshgngwr.noice.cast.CastAPIWrapper
import com.github.ashutoshgngwr.noice.sound.Preset
import com.github.ashutoshgngwr.noice.sound.Sound
import com.github.ashutoshgngwr.noice.sound.player.strategy.LocalPlaybackStrategyFactory
import com.github.ashutoshgngwr.noice.sound.player.strategy.PlaybackStrategyFactory

/**
 * [PlayerManager] is responsible for managing [Player]s end-to-end for all sounds.
 * It manages Android's audio focus implicitly. It also manages Playback routing to
 * cast enabled devices on-demand.
 */
class PlayerManager(private val context: Context) :
  AudioManager.OnAudioFocusChangeListener {

  enum class State {
    PLAYING, PAUSED, STOPPED
  }

  companion object {
    private val TAG = PlayerManager::javaClass.name
  }

  var state = State.STOPPED
    private set

  private var hasAudioFocus = false
  private var playbackDelayed = false
  private var resumeOnFocusGain = false
  private var onPlayerUpdateListener = { }

  val players = HashMap<String, Player>(Sound.LIBRARY.size)
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val audioAttributes = AudioAttributesCompat.Builder()
    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MOVIE)
    .setUsage(AudioAttributesCompat.USAGE_GAME)
    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
    .build()

  private val audioFocusRequest = AudioFocusRequestCompat
    .Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    .setAudioAttributes(audioAttributes)
    .setOnAudioFocusChangeListener(this, Handler())
    .setWillPauseWhenDucked(false)
    .build()

  private var playbackStrategyFactory: PlaybackStrategyFactory =
    LocalPlaybackStrategyFactory(context, audioAttributes)

  private val playbackStateBuilder = PlaybackStateCompat.Builder()
    .setActions(
      PlaybackStateCompat.ACTION_PLAY_PAUSE
        and PlaybackStateCompat.ACTION_PAUSE
        and PlaybackStateCompat.ACTION_STOP
    )

  private val mediaSession = MediaSessionCompat(context, context.packageName).also {
    it.setMetadata(
      MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, context.getString(R.string.app_name))
        .build()
    )

    it.setCallback(object : MediaSessionCompat.Callback() {
      override fun onPlay() = resume()
      override fun onStop() = stop()
      override fun onPause() = pause()
    })

    it.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    it.setPlaybackState(playbackStateBuilder.build())
    it.isActive = true
  }

  private val castAPIWrapper = CastAPIWrapper.from(context, true).apply {
    onSessionBegin {
      Log.d(TAG, "starting cast session")
      playbackStrategyFactory = newCastPlaybackStrategyFactory()
      updatePlaybackStrategies()
      mediaSession.setPlaybackToRemote(newCastVolumeProvider())
    }

    onSessionEnd {
      // onSessionEnded gets called when restarting the activity. So need to ensure that we're not
      // recreating the LocalPlaybackStrategyFactory again because it will cause [PlaybackStrategy]s to be
      // recreated resulting glitches in playback.
      if (playbackStrategyFactory !is LocalPlaybackStrategyFactory) {
        Log.d(TAG, "ending cast session")
        playbackStrategyFactory = LocalPlaybackStrategyFactory(context, audioAttributes)
        mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        updatePlaybackStrategies()
      }
    }
  }

  init {
    MediaRouter.getInstance(context).setMediaSessionCompat(mediaSession)
  }

  // implements audio focus change listener
  override fun onAudioFocusChange(focusChange: Int) {
    when (focusChange) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        Log.d(TAG, "Gained audio focus...")
        hasAudioFocus = true
        if (playbackDelayed || resumeOnFocusGain) {
          Log.d(TAG, "Resume playback after audio focus gain...")
          playbackDelayed = false
          resumeOnFocusGain = false
          resume()
        }
      }
      AudioManager.AUDIOFOCUS_LOSS -> {
        Log.d(TAG, "Permanently lost audio focus! Stop playback...")
        hasAudioFocus = false
        resumeOnFocusGain = true
        playbackDelayed = false
        pause()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        Log.d(TAG, "Temporarily lost audio focus! Pause playback...")
        hasAudioFocus = false
        resumeOnFocusGain = true
        playbackDelayed = false
        pause()
      }
    }
  }

  // creates audio focus request and handles its response
  private fun requestAudioFocus() {
    if (hasAudioFocus) {
      return
    }

    val result = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
    Log.d(TAG, "AudioFocusRequest result: $result")
    when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        Log.d(TAG, "Audio focus request was delayed! Pause playback for now.")
        playbackDelayed = true
        hasAudioFocus = false
        resumeOnFocusGain = false
        pause()
      }
      AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
        Log.d(TAG, "Failed to get audio focus! Stop playback...")
        hasAudioFocus = false
        playbackDelayed = false
        resumeOnFocusGain = false
        pause()
      }
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        hasAudioFocus = true
        playbackDelayed = false
        resumeOnFocusGain = false
        resume()
      }
    }
  }

  /**
   * starts playing a sound. It also creates an audio focus request if we don't have it.
   * Playback won't start immediately if audio focus is not present. We always ensure that we
   * have audio focus before starting the playback.
   */
  fun play(soundKey: String) {
    if (!players.containsKey(soundKey)) {
      players[soundKey] = Player(Sound.get(soundKey), playbackStrategyFactory)
    }

    if (playbackDelayed) {
      // If audio focus is delayed, add this sound to players and it will be played whenever the
      // we get audio focus.
      state = State.PAUSED
      notifyChanges()
      return
    }

    if (!hasAudioFocus) {
      // if doesn't have audio focus, request and return because a successful audio focus request
      // will start the player.
      requestAudioFocus()
      return
    }

    state = State.PLAYING
    requireNotNull(players[soundKey]).play()
    notifyChanges()
  }

  /**
   * Stops a [Player] and releases underlying resources. It abandons focus if all [Player]s are
   * stopped.
   */
  fun stop(soundKey: String) {
    players[soundKey]?.also {
      it.stop()
      players.remove(it.soundKey)
    }

    if (players.isEmpty()) {
      state = State.STOPPED
      AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
    }

    notifyChanges()
  }

  /**
   * Stops all [Player]s and releases underlying resources. Also abandon the audio focus.
   */
  fun stop() {
    state = State.STOPPED
    players.values.forEach { it.stop() }
    players.clear()
    AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
    notifyChanges()
  }

  /**
   * Stops all [Player]s but maintains their state so that these can be resumed at a later stage.
   * It abandons audio focus.
   */
  fun pause() {
    state = State.PAUSED
    players.values.forEach { it.pause() }
    AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
    notifyChanges()
  }

  /**
   * Resumes all [Player]s from the saved state. It requests AudioFocus if not already present.
   */
  fun resume() {
    if (hasAudioFocus) {
      state = State.PLAYING
      players.values.forEach { it.play() }
    } else if (!playbackDelayed) {
      // request audio focus only if audio focus is not delayed from any previous requests
      requestAudioFocus()
    }

    notifyChanges()
  }

  /**
   * Sets the volume for the [Player] with given key.
   * @param soundKey identifier for the [Player]. If [Player] is not found, no action is taken
   * @param volume updated volume for the [Player]
   */
  fun setVolume(soundKey: String, volume: Int) {
    if (!players.containsKey(soundKey)) {
      return
    }

    requireNotNull(players[soundKey]).setVolume(volume)
  }

  /**
   * Sets the time period for the [Player] with given key
   * @param soundKey identifier for the [Player]. If [Player] is not found, no action is taken
   * @param timePeriod updated time period for the [Player]
   */
  fun setTimePeriod(soundKey: String, timePeriod: Int) {
    if (!players.containsKey(soundKey)) {
      return
    }

    requireNotNull(players[soundKey]).timePeriod = timePeriod
  }

  /**
   * Attempts to recreate the [PlaybackStrategyFactory] for all [Player]s using the current
   * [PlaybackStrategyFactory] instance
   */
  private fun updatePlaybackStrategies() {
    players.values.forEach { it.updatePlaybackStrategy(playbackStrategyFactory) }
  }

  /**
   * Performs cleanup. must be called when final cleanup is required for the instance
   */
  fun cleanup() {
    stop()
    mediaSession.release()
    castAPIWrapper.clearSessionCallbacks()
  }

  /**
   * Allows clients to subscribe for changes in [Player]s
   * @param listener a lambda that is called on every update
   */
  fun setOnPlayerUpdateListener(listener: () -> Unit) {
    onPlayerUpdateListener = listener
  }

  /**
   * [playPreset] efficiently loads a given [Preset] to the [PlayerManager]. It attempts to re-use
   * [Player] instances that are both present in its state and requested by [Preset]. It is also
   * superior to calling [play] manually for each [Preset.PlayerState] since that would cause
   * [PlayerManager] to invoke [onPlayerUpdateListener] with each [Preset.PlayerState]. This method
   * ensures that [onPlayerUpdateListener] is invoked only once for any given [Preset].
   */
  fun playPreset(preset: Preset) {
    // stop players that are not present in preset state
    players.keys.subtract(preset.playerStates.map { it.soundKey }).forEach {
      players.remove(it)?.stop()
    }

    // load states from Preset to manager's state
    preset.playerStates.forEach {
      val player: Player
      if (players.contains(it.soundKey)) {
        player = requireNotNull(players[it.soundKey])
      } else {
        player = Player(Sound.get(it.soundKey), playbackStrategyFactory)
        players[it.soundKey] = player
      }

      player.timePeriod = it.timePeriod
      player.setVolume(it.volume)
    }

    // resume PlayerManager to gain audio focus and play everything
    resume()
  }

  private fun notifyChanges() {
    when (state) {
      State.PLAYING -> playbackStateBuilder.setState(
        PlaybackStateCompat.STATE_PLAYING,
        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
        1f
      )

      State.PAUSED -> playbackStateBuilder.setState(
        PlaybackStateCompat.STATE_PAUSED,
        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
        0f
      )

      State.STOPPED -> playbackStateBuilder.setState(
        PlaybackStateCompat.STATE_STOPPED,
        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
        0f
      )

    }

    mediaSession.setPlaybackState(playbackStateBuilder.build())
    onPlayerUpdateListener()
  }
}
