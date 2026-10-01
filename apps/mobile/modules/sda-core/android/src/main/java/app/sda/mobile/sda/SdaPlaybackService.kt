package app.sda.mobile.sda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SdaPlaybackService : Service() {
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered = false
    private var ducked = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && SdaPlaybackController.playing && !SdaPlaybackController.paused) {
                SdaPlaybackController.pause()
                publish()
            }
        }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (SdaPlaybackController.playing && !SdaPlaybackController.paused) SdaPlaybackController.pause(user = false)
                abandonFocus()
                publish()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (SdaPlaybackController.playing && !SdaPlaybackController.paused) {
                    SdaPlaybackController.userPaused = false
                    SdaPlaybackController.pause(user = false)
                    publish()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!ducked && SdaPlaybackController.playing && !SdaPlaybackController.paused) {
                    ducked = true
                    SdaPlaybackController.duck(true)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) { ducked = false; SdaPlaybackController.duck(false) }
                if (SdaPlaybackController.playing && SdaPlaybackController.paused && !SdaPlaybackController.userPaused) {
                    SdaPlaybackController.resume(user = false)
                    publish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SDA 音频播放", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseFromNotification()
            ACTION_RESUME -> resumeFromNotification()
            ACTION_STOP -> stopPlayback()
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification())
                if (SdaPlaybackController.playing && !SdaPlaybackController.paused) requestFocus()
            }
        }
        publish()
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun requestFocus() {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(false).build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        focusRequest = null
    }

    private fun pauseFromNotification() {
        if (SdaPlaybackController.playing && !SdaPlaybackController.paused) SdaPlaybackController.pause()
        publish()
    }

    private fun resumeFromNotification() {
        if (SdaPlaybackController.playing && SdaPlaybackController.paused) {
            requestFocus()
            SdaPlaybackController.resume()
        }
        publish()
    }

    private fun stopPlayback() { SdaPlaybackController.stop(); abandonFocus() }

    private fun publish() {
        if (SdaPlaybackController.playing) {
            if (Build.VERSION.SDK_INT >= 26) startForeground(NOTIFICATION_ID, notification())
            else @Suppress("DEPRECATION") (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            abandonFocus()
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val activity = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags())
        val action = if (SdaPlaybackController.paused) ACTION_RESUME else ACTION_PAUSE
        val label = if (SdaPlaybackController.paused) "继续" else "暂停"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(SdaPlaybackController.title)
            .setContentText(if (SdaPlaybackController.paused) "已暂停" else "正在播放")
            .setContentIntent(activity).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(0, label, commandIntent(action, 1))
            .addAction(0, "停止", commandIntent(ACTION_STOP, 2))
            .build()
    }

    private fun commandIntent(action: String, requestCode: Int) = PendingIntent.getService(this, requestCode, Intent(this, SdaPlaybackService::class.java).setAction(action), pendingFlags())
    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (noisyReceiverRegistered) unregisterReceiver(noisyReceiver)
        if (SdaPlaybackController.playing) SdaPlaybackController.stop()
        abandonFocus()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sda_playback"
        private const val NOTIFICATION_ID = 4701
        const val ACTION_START = "app.sda.mobile.sda.START"
        const val ACTION_PAUSE = "app.sda.mobile.sda.PAUSE"
        const val ACTION_RESUME = "app.sda.mobile.sda.RESUME"
        const val ACTION_STOP = "app.sda.mobile.sda.STOP"

        fun start(context: Context, title: String) {
            SdaPlaybackController.setTitle(title)
            val intent = Intent(context, SdaPlaybackService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, SdaPlaybackService::class.java)) }
    }
}
