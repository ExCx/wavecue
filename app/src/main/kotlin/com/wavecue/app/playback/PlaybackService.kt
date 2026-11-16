package com.wavecue.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.wavecue.app.MainActivity
import com.wavecue.app.R
import com.wavecue.core.model.PlaybackState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground playback host. The service owns the media session and the notification;
 * all transport work is delegated to the process-wide [PlaybackController], so the
 * service can be recreated without interrupting audio.
 */
@AndroidEntryPoint
class PlaybackService : android.app.Service() {

    @Inject lateinit var controller: PlaybackController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSessionCompat? = null
    private var observeJob: Job? = null
    private var startedForeground = false
    private var hasSeenMedia = false
    @Volatile private var latest: Snapshot? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = this@PlaybackService.controller.play()
                override fun onPause() = this@PlaybackService.controller.pause()
                override fun onSkipToNext() = this@PlaybackService.controller.next()
                override fun onSkipToPrevious() = this@PlaybackService.controller.previous()
                override fun onSeekTo(pos: Long) = this@PlaybackService.controller.seekTo(pos)
            })
            isActive = true
        }
        observeJob = scope.launch {
            combine(
                controller.state,
                controller.media,
                controller.current,
                controller.queueLabel,
            ) { state, media, current, label -> Snapshot(state, media, current, label) }
                .collect { snapshot -> render(snapshot) }
        }
        // Keep the lockscreen and Bluetooth position roughly in step without
        // rebuilding the notification every second.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                latest?.let { updatePlaybackState(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_PLAY -> controller.play()
            ACTION_PAUSE -> controller.pause()
            ACTION_NEXT -> controller.next()
            ACTION_PREV -> controller.previous()
            ACTION_STOP_SERVICE -> {
                controller.pause()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        // Promote to foreground immediately; the notification is refined on the next state.
        ensureForeground(buildNotification(controller.state.value, controller.media.value?.name, controller.queueLabel.value))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep audio alive when the task is swiped away; the notification remains the control surface.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        session?.isActive = false
        session?.release()
        session = null
        if (startedForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        super.onDestroy()
    }

    private fun render(snapshot: Snapshot) {
        latest = snapshot
        if (snapshot.media != null) hasSeenMedia = true
        if (snapshot.media == null && snapshot.state == PlaybackState.IDLE && hasSeenMedia) {
            if (startedForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                startedForeground = false
            }
            stopSelf()
            return
        }
        updateMetadata(snapshot)
        updatePlaybackState(snapshot)
        val notification = buildNotification(snapshot.state, snapshot.media?.name ?: snapshot.current?.name, snapshot.label)
        ensureForeground(notification)
    }

    private fun ensureForeground(notification: Notification) {
        if (!startedForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            startedForeground = true
        } else {
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMetadata(snapshot: Snapshot) {
        val session = session ?: return
        val media = snapshot.media ?: return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media.name)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.label ?: "WaveCue")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, media.durationMs)
                .build()
        )
    }

    private fun updatePlaybackState(snapshot: Snapshot) {
        val session = session ?: return
        val playbackState = when (snapshot.state) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackState.LOADING -> PlaybackStateCompat.STATE_BUFFERING
            PlaybackState.ENDED -> PlaybackStateCompat.STATE_STOPPED
            PlaybackState.ERROR -> PlaybackStateCompat.STATE_ERROR
            PlaybackState.IDLE -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(playbackState, controller.positionMs.value, if (snapshot.state == PlaybackState.PLAYING) 1f else 0f)
                .build()
        )
    }

    private fun buildNotification(state: PlaybackState, title: String?, label: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_waveform)
            .setContentTitle(title ?: "WaveCue")
            .setContentText(label ?: "Ready")
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_prev, "Previous", serviceAction(ACTION_PREV))
            .addAction(
                if (state == PlaybackState.PLAYING) R.drawable.ic_pause else R.drawable.ic_play,
                if (state == PlaybackState.PLAYING) "Pause" else "Play",
                serviceAction(ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_next, "Next", serviceAction(ACTION_NEXT))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Transport controls for the current track"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private data class Snapshot(
        val state: PlaybackState,
        val media: com.wavecue.core.model.LoadedMedia?,
        val current: com.wavecue.core.model.QueueItem?,
        val label: String?,
    )

    companion object {
        private const val CHANNEL_ID = "wavecue_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SESSION_TAG = "WaveCueSession"

        const val ACTION_TOGGLE = "com.wavecue.app.action.TOGGLE"
        const val ACTION_PLAY = "com.wavecue.app.action.PLAY"
        const val ACTION_PAUSE = "com.wavecue.app.action.PAUSE"
        const val ACTION_NEXT = "com.wavecue.app.action.NEXT"
        const val ACTION_PREV = "com.wavecue.app.action.PREV"
        const val ACTION_STOP_SERVICE = "com.wavecue.app.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: IllegalStateException) {
                // Already running in the foreground (the usual case when advancing
                // tracks), or the OS briefly refused a background start. Either way
                // the existing session keeps playing.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
