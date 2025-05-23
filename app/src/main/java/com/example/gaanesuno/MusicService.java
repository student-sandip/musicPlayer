package com.example.gaanesuno;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicService extends Service implements
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnPreparedListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicService";

    private MediaPlayer mediaPlayer;
    private List<Song> songList; // The original list of songs
    private List<Song> activeSongList; // The list currently being played (can be shuffled)
    private int currentSongIndex = -1;
    private Song currentSong;
    private int currentPosition; // Current playback position for saving/restoring
    private boolean isPrepared = false; // Flag to indicate if MediaPlayer is prepared
    private boolean shouldPlayAfterPrepared = false; // Flag to play immediately after preparation

    // Service Binder
    private final IBinder musicBinder = new MusicBinder();

    // Callbacks for MainActivity to update UI
    public interface OnSongChangedListener {
        void onSongChanged(Song song, boolean isPlaying);
        void onPlaybackStateChanged(boolean isPlaying);
        void onProgressUpdate(int currentPosition, int duration);
    }
    private OnSongChangedListener listener;

    // Notification
    private static final String CHANNEL_ID = "MusicPlayerChannel";
    private static final int NOTIFICATION_ID = 101;
    private NotificationManager notificationManager;

    // Actions for Notification and Service control
    public static final String ACTION_PLAY = "com.example.gaanesuno.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.gaanesuno.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.example.gaanesuno.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.example.gaanesuno.ACTION_PREVIOUS";
    public static final String ACTION_STOP = "com.example.gaanesuno.ACTION_STOP"; // For notification close

    // Shuffle & Repeat modes
    private boolean isShuffleEnabled = false;
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;
    private int repeatMode = REPEAT_OFF;

    // Handler for updating seekbar and notification periodically
    private Handler handler = new Handler();
    private Runnable updateNotificationAndSeekBarRunnable;

    // Audio Focus
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // For API 26+

    // --- Service Lifecycle Methods ---

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MusicService onCreate: Service is being created.");

        songList = new ArrayList<>();
        activeSongList = new ArrayList<>();
        initMediaPlayer(); // Initialize MediaPlayer

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel(); // Create notification channel for Android O+

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Register receiver for notification button clicks
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_PREVIOUS);
        filter.addAction(ACTION_STOP);
        ContextCompat.registerReceiver(this, notificationActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "Notification action receiver registered.");

        // Runnable to update progress (seekbar and notification)
        updateNotificationAndSeekBarRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    currentPosition = mediaPlayer.getCurrentPosition();
                    if (listener != null) {
                        listener.onProgressUpdate(currentPosition, mediaPlayer.getDuration());
                    }
                    handler.postDelayed(this, 1000); // Update every second
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MusicService onStartCommand, action: " + (intent != null ? intent.getAction() : "null"));

        // If the service is started with an intent, process the action
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_PLAY:
                    play();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_NEXT:
                    playNextSong();
                    break;
                case ACTION_PREVIOUS:
                    playPreviousSong();
                    break;
                case ACTION_STOP:
                    Log.d(TAG, "Received ACTION_STOP. Stopping service.");
                    // This action means user wants to fully stop playback and dismiss notification
                    pause(); // Pause playback
                    stopSelf(); // Stop the service
                    break;
                // Add more actions if needed (e.g., from external sources)
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "MusicService onBind: Activity is binding to service.");
        return musicBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "MusicService onUnbind: Activity is unbinding from service.");
        // When all clients have unbound, and this is not a foreground service,
        // the system might kill it. Returning true allows onRebind().
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.d(TAG, "MusicService onRebind: Activity is re-binding to service.");
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MusicService onDestroy: Service is being destroyed. Releasing resources.");

        // Save current playback state before destruction
        SharedPreferences.Editor editor = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit();
        if (currentSong != null) {
            editor.putLong(MainActivity.KEY_LAST_SONG_ID, currentSong.getId());
            editor.putInt(MainActivity.KEY_LAST_SONG_POSITION, getCurrentPosition()); // Get actual current position
            editor.putBoolean(MainActivity.KEY_WAS_PLAYING, isPlaying()); // Save if it was playing or paused
            Log.d(TAG, "onDestroy: Saved last song ID: " + currentSong.getId() + ", position: " + getCurrentPosition() + ", wasPlaying: " + isPlaying());
        } else {
            // No song playing or available, clear saved state
            editor.remove(MainActivity.KEY_LAST_SONG_ID);
            editor.remove(MainActivity.KEY_LAST_SONG_POSITION);
            editor.putBoolean(MainActivity.KEY_WAS_PLAYING, false); // No song, so not playing
            Log.d(TAG, "onDestroy: No current song, cleared last song ID/position from prefs.");
        }
        editor.apply();

        // Release MediaPlayer resources
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                Log.d(TAG, "MediaPlayer stopped in onDestroy.");
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released in onDestroy.");
        }

        // Abandon audio focus
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                Log.d(TAG, "Audio focus request abandoned (API 26+).");
            } else {
                audioManager.abandonAudioFocus(this);
                Log.d(TAG, "Audio focus abandoned (API < 26).");
            }
        }

        // Stop the handler's updates
        handler.removeCallbacks(updateNotificationAndSeekBarRunnable);
        Log.d(TAG, "Handler callbacks removed.");

        // Stop the foreground service and remove the notification
        stopForeground(true); // true means remove the notification
        Log.d(TAG, "Foreground service stopped and notification removed.");

        // Unregister the broadcast receiver
        try {
            unregisterReceiver(notificationActionReceiver);
            Log.d(TAG, "Notification action receiver unregistered.");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered, skipping unregister: " + e.getMessage());
        }
    }

    /**
     * Called when all tasks associated with this service are removed from the recent apps.
     * This is crucial for stopping background playback when the user "swipes away" the app.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "MusicService onTaskRemoved: App process removed from recent tasks. Stopping service.");
        // User has swiped the app away from recents. Stop music and service.
        if (isPlaying()) {
            pause(); // Pause playback
        }
        stopSelf(); // Stop the service itself
        Log.d(TAG, "MusicService explicitly stopped due to onTaskRemoved.");
        super.onTaskRemoved(rootIntent);
    }

    // --- MediaPlayer Setup and Callbacks ---

    /** Initializes the MediaPlayer instance. */
    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnPreparedListener(this); // Set the default onPreparedListener here
            Log.d(TAG, "MediaPlayer initialized for the first time.");
        } else {
            Log.d(TAG, "MediaPlayer already initialized.");
        }
    }

    /** Prepares the MediaPlayer with a new song. */
    private void prepareMediaPlayer(Song song) {
        if (mediaPlayer == null) {
            initMediaPlayer(); // Ensure mediaPlayer is not null
        }
        mediaPlayer.reset(); // Reset to idle state
        isPrepared = false; // Mark as not prepared yet
        try {
            mediaPlayer.setDataSource(getApplicationContext(), song.getData());
            mediaPlayer.prepareAsync(); // Asynchronously prepare
            Log.d(TAG, "MediaPlayer preparing asynchronously for: " + song.getTitle());
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source or preparing for " + song.getTitle() + ": " + e.getMessage(), e);
            Toast.makeText(this, "Error loading song: " + song.getTitle() + ". Skipping...", Toast.LENGTH_SHORT).show();
            // Handle error: e.g., skip to next song
            if (listener != null) {
                listener.onSongChanged(null, false); // Clear UI
                listener.onPlaybackStateChanged(false); // Update play/pause button
            }
            playNextSong(); // Try to play next song if current one fails
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true; // MediaPlayer is now prepared
        Log.d(TAG, "MediaPlayer onPrepared for: " + currentSong.getTitle() + ", shouldPlayAfterPrepared: " + shouldPlayAfterPrepared);

        if (shouldPlayAfterPrepared) {
            // Only attempt to start if audio focus is granted
            if (requestAudioFocus()) {
                mp.start(); // Start actual playback
                Log.d(TAG, "MediaPlayer started playing from onPrepared: " + currentSong.getTitle());
                startForeground(NOTIFICATION_ID, createNotification(currentSong, true)); // Promote to foreground
                handler.post(updateNotificationAndSeekBarRunnable); // Start seekbar updates
                if (listener != null) {
                    listener.onPlaybackStateChanged(true); // Notify activity that it's playing
                    listener.onSongChanged(currentSong, true); // Update song info and playing state in UI
                }
            } else {
                Log.w(TAG, "Audio focus denied onPrepared. Cannot play immediately.");
                if (listener != null) {
                    listener.onPlaybackStateChanged(false); // Update UI to paused state
                }
                // Optional: update notification to show paused state if focus denied
                notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
            }
            shouldPlayAfterPrepared = false; // Reset the flag after use
        } else {
            Log.d(TAG, "MediaPlayer prepared, but not starting playback immediately (shouldPlayAfterPrepared was false).");
            // If just preparing (e.g., for seek or restore paused state), update UI to paused
            if (listener != null) {
                listener.onSongChanged(currentSong, false);
                listener.onPlaybackStateChanged(false);
            }
            // Update notification to reflect paused state
            notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "MediaPlayer onCompletion. Current song: " + (currentSong != null ? currentSong.getTitle() : "null"));
        switch (repeatMode) {
            case REPEAT_ONE:
                Log.d(TAG, "Repeat ONE: Replaying current song.");
                play(); // Replay the same song by calling play()
                break;
            case REPEAT_ALL:
                Log.d(TAG, "Repeat ALL: Playing next song.");
                playNextSong(); // Play next song
                break;
            case REPEAT_OFF:
            default:
                if (currentSongIndex < activeSongList.size() - 1) {
                    Log.d(TAG, "Repeat OFF: Playing next song.");
                    playNextSong(); // Play next if not the last song
                } else {
                    // Last song in list, repeat off. Stop playback.
                    Log.d(TAG, "Repeat OFF: Last song finished. Stopping playback.");
                    pause(); // Pause playback
                    seekTo(0); // Reset to beginning of the last song
                    if (listener != null) {
                        listener.onSongChanged(currentSong, false); // Update UI for finished song, paused
                        listener.onPlaybackStateChanged(false); // Update play/pause button
                        listener.onProgressUpdate(0, currentSong.getDuration()); // Reset seekbar
                    }
                    stopForeground(false); // Keep notification visible but not foreground
                    notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
                }
                break;
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer onError: what=" + what + ", extra=" + extra);
        isPrepared = false; // MediaPlayer is no longer prepared
        if (listener != null) {
            listener.onPlaybackStateChanged(false); // Update UI
        }
        Toast.makeText(this, "Error playing song. Skipping...", Toast.LENGTH_SHORT).show();
        // Attempt to play the next song to continue playback
        playNextSong();
        return true; // Indicates that the error has been handled
    }

    // --- Audio Focus Management ---

    /** Requests audio focus for playback. */
    private boolean requestAudioFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus granted.");
            return true;
        } else {
            Log.w(TAG, "Audio focus request denied: " + result);
            return false;
        }
    }

    /** Handles changes in audio focus. */
    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "Audio focus gained.");
                // Resume playback if it was paused due to transient loss
                if (mediaPlayer != null && !mediaPlayer.isPlaying() && isPrepared) {
                    mediaPlayer.start();
                    startForeground(NOTIFICATION_ID, createNotification(currentSong, true));
                    handler.post(updateNotificationAndSeekBarRunnable);
                    if (listener != null) {
                        listener.onPlaybackStateChanged(true);
                    }
                }
                mediaPlayer.setVolume(1.0f, 1.0f); // Restore full volume
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(TAG, "Audio focus lost (long term). Pausing and abandoning focus.");
                // Permanent loss of audio focus, stop playback entirely
                pause();
                // No need to abandon here, as requestAudioFocus handled it.
                // If you explicitly abandon here, ensure it's not double abandoned.
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(TAG, "Audio focus lost (transient). Pausing.");
                // Temporary loss (e.g., phone call), pause playback
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    currentPosition = mediaPlayer.getCurrentPosition();
                    stopForeground(false); // Keep notification visible but downgrade service
                    notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
                    handler.removeCallbacks(updateNotificationAndSeekBarRunnable);
                    if (listener != null) {
                        listener.onPlaybackStateChanged(false);
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "Audio focus lost (transient, can duck). Ducking volume.");
                // Temporary loss where system allows "ducking" (lower volume)
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.setVolume(0.1f, 0.1f); // Lower volume
                }
                break;
        }
    }

    // --- Music Playback Controls (Public API for MainActivity) ---

    /** Sets the main list of songs and initializes the active list. */
    public void setSongList(List<Song> songs) {
        if (songs == null) {
            this.songList = new ArrayList<>();
            this.activeSongList = new ArrayList<>();
            Log.w(TAG, "setSongList: Provided song list is null. Initializing empty lists.");
            return;
        }
        this.songList = new ArrayList<>(songs);
        this.activeSongList = new ArrayList<>(songs); // Active list initially same as original
        Log.d(TAG, "Song list set. Total songs: " + songList.size() + ". Active list initialized.");
    }

    /**
     * Plays a song at a specific index from the active song list.
     * This method handles preparing the MediaPlayer and initiates playback via onPrepared.
     */
    public void playSong(int songIndex) {
        Log.d(TAG, "playSong called with index: " + songIndex);
        if (activeSongList.isEmpty()) {
            Toast.makeText(this, "No songs to play.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "playSong: activeSongList is empty. Cannot play.");
            if (listener != null) {
                listener.onSongChanged(null, false);
                listener.onPlaybackStateChanged(false);
            }
            stopForeground(true); // Ensure notification is gone if no songs
            return;
        }
        if (songIndex < 0 || songIndex >= activeSongList.size()) {
            Log.e(TAG, "Invalid song index: " + songIndex + ". List size: " + activeSongList.size());
            Toast.makeText(this, "Invalid song selection.", Toast.LENGTH_SHORT).show();
            return;
        }

        currentSongIndex = songIndex;
        currentSong = activeSongList.get(currentSongIndex);
        Log.d(TAG, "Attempting to play song: " + currentSong.getTitle() + " at index " + currentSongIndex);

        // Signal to onPrepared that playback should start after preparation
        shouldPlayAfterPrepared = true;
        prepareMediaPlayer(currentSong); // This will call onPrepared when ready

        // Update UI with new song info immediately (show as initially paused until started)
        if (listener != null) {
            listener.onSongChanged(currentSong, false); // Update UI with new song details
            listener.onPlaybackStateChanged(false); // Ensure UI shows paused state initially
        }
        Log.d(TAG, "playSong: Initiated preparation for " + currentSong.getTitle() + ". Playback will start in onPrepared.");
    }

    /**
     * Resumes playback if paused, or starts playback of current song if not playing.
     * Also handles initial song selection if no song is loaded.
     */
    public void play() {
        Log.d(TAG, "play() called.");
        if (currentSong == null) {
            if (!activeSongList.isEmpty()) {
                Log.d(TAG, "play(): No current song selected, playing first song (index 0).");
                playSong(0); // If no song loaded, play the first one
                return; // playSong will handle preparation and starting
            } else {
                Log.w(TAG, "play(): No songs available in the list. Cannot play.");
                Toast.makeText(this, "No song to play.", Toast.LENGTH_SHORT).show();
                stopForeground(true); // Ensure notification is gone
                return;
            }
        }

        if (!isPrepared) {
            // If MediaPlayer is not prepared, prepare it and signal to play after.
            Log.d(TAG, "play(): MediaPlayer not prepared for " + currentSong.getTitle() + ". Preparing now.");
            shouldPlayAfterPrepared = true; // Set flag to play after preparation
            prepareMediaPlayer(currentSong); // Re-prepare the current song
            return; // Wait for onPrepared to start playback
        }

        // If prepared and not currently playing, attempt to start
        if (!mediaPlayer.isPlaying()) {
            if (requestAudioFocus()) {
                mediaPlayer.start();
                Log.d(TAG, "play(): MediaPlayer started playing: " + currentSong.getTitle());
                // Crucial: Start foreground service and update notification when playing
                startForeground(NOTIFICATION_ID, createNotification(currentSong, true));
                handler.post(updateNotificationAndSeekBarRunnable); // Start seekbar updates
                if (listener != null) {
                    listener.onPlaybackStateChanged(true); // Notify activity that it's playing
                }
            } else {
                Log.w(TAG, "play(): Could not get audio focus. Cannot play.");
                Toast.makeText(this, "Could not get audio focus to play music.", Toast.LENGTH_SHORT).show();
                if (listener != null) {
                    listener.onPlaybackStateChanged(false); // Update UI to paused
                }
                // Update notification to reflect paused state if focus denied
                notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
            }
        } else {
            Log.d(TAG, "play(): MediaPlayer already playing. No action needed.");
        }
    }

    /** Pauses the current playback. */
    public void pause() {
        Log.d(TAG, "pause() called.");
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            currentPosition = mediaPlayer.getCurrentPosition(); // Save current position
            Log.d(TAG, "MediaPlayer paused. Position: " + currentPosition);

            // Stop foreground service but keep notification visible
            stopForeground(false);
            // Update notification icon to 'play'
            notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));

            handler.removeCallbacks(updateNotificationAndSeekBarRunnable); // Stop seekbar updates
            if (listener != null) {
                listener.onPlaybackStateChanged(false); // Notify activity to update UI
            }
        } else {
            Log.d(TAG, "pause(): MediaPlayer not playing or null. No action needed.");
        }
    }

    /** Plays the next song in the active list. */
    public void playNextSong() {
        Log.d(TAG, "playNextSong() called.");
        if (activeSongList.isEmpty()) {
            Log.w(TAG, "Cannot play next song: song list is empty.");
            Toast.makeText(this, "No songs in list.", Toast.LENGTH_SHORT).show();
            stopForeground(true); // Ensure notification is gone
            return;
        }
        int nextIndex = currentSongIndex + 1;
        if (nextIndex >= activeSongList.size()) {
            nextIndex = 0; // Wrap around to the beginning
        }
        Log.d(TAG, "Playing next song. Current index: " + currentSongIndex + ", New index: " + nextIndex);
        playSong(nextIndex); // Use playSong to handle preparation and start
    }

    /** Plays the previous song in the active list. */
    public void playPreviousSong() {
        Log.d(TAG, "playPreviousSong() called.");
        if (activeSongList.isEmpty()) {
            Log.w(TAG, "Cannot play previous song: song list is empty.");
            Toast.makeText(this, "No songs in list.", Toast.LENGTH_SHORT).show();
            stopForeground(true); // Ensure notification is gone
            return;
        }
        int prevIndex = currentSongIndex - 1;
        if (prevIndex < 0) {
            prevIndex = activeSongList.size() - 1; // Wrap around to the end
        }
        Log.d(TAG, "Playing previous song. Current index: " + currentSongIndex + ", New index: " + prevIndex);
        playSong(prevIndex); // Use playSong to handle preparation and start
    }

    /** Seeks to a specific position in the current song. */
    public void seekTo(int position) {
        Log.d(TAG, "seekTo() called with position: " + position);
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.seekTo(position);
            currentPosition = position; // Update current position
            Log.d(TAG, "MediaPlayer seeked to: " + position);
            if (listener != null) {
                listener.onProgressUpdate(currentPosition, mediaPlayer.getDuration());
            }
        } else {
            Log.w(TAG, "seekTo(): MediaPlayer not prepared or null. Cannot seek.");
        }
    }

    /**
     * Prepares a specific song for restoration (e.g., app re-launch).
     * This method customizes onPrepared behavior for seeking and conditional playback.
     */
    public void prepareSongForRestore(Song song, int index, boolean shouldPlay, int positionMs) {
        if (song == null) {
            Log.d(TAG, "prepareSongForRestore: No song to restore.");
            if (listener != null) {
                listener.onSongChanged(null, false);
                listener.onPlaybackStateChanged(false);
            }
            stopForeground(true); // Remove notification if no song to restore
            return;
        }
        this.currentSong = song;
        this.currentSongIndex = index;
        this.currentPosition = positionMs;
        Log.d(TAG, "prepareSongForRestore: " + song.getTitle() + ", shouldPlay: " + shouldPlay + ", position: " + positionMs);

        // Temporarily override onPrepared to handle seek and play/pause logic after preparation
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                isPrepared = true;
                Log.d(TAG, "MediaPlayer onPrepared for restore. Seeking to: " + positionMs);
                mp.seekTo(positionMs);

                if (shouldPlay) {
                    Log.d(TAG, "Auto-playing after restore based on shouldPlay=true.");
                    // Request focus and start. This is similar to the play() method's starting logic.
                    if (requestAudioFocus()) {
                        mp.start();
                        startForeground(NOTIFICATION_ID, createNotification(currentSong, true));
                        handler.post(updateNotificationAndSeekBarRunnable);
                        if (listener != null) {
                            listener.onPlaybackStateChanged(true);
                            listener.onSongChanged(currentSong, true); // Update UI playing state
                        }
                    } else {
                        Log.w(TAG, "Audio focus denied during restore auto-play. Remaining paused.");
                        if (listener != null) {
                            listener.onPlaybackStateChanged(false);
                            listener.onSongChanged(currentSong, false); // Update UI paused state
                        }
                        notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
                    }
                } else {
                    Log.d(TAG, "Keeping paused after restore based on shouldPlay=false.");
                    if (listener != null) {
                        listener.onSongChanged(currentSong, false); // Update UI to show song paused
                        listener.onPlaybackStateChanged(false);
                        listener.onProgressUpdate(positionMs, mp.getDuration()); // Update seekbar
                    }
                    // Update notification for paused state
                    notificationManager.notify(NOTIFICATION_ID, createNotification(currentSong, false));
                    // No need to call stopForeground here unless it was previously playing and now needs to be downgraded.
                    // If it was already paused before app close, it won't be foreground.
                }
                // IMPORTANT: Reset listener to default after restore logic is done
                mediaPlayer.setOnPreparedListener(MusicService.this);
            }
        });
        prepareMediaPlayer(song); // Prepare the song to trigger the temporary onPreparedListener
    }


    // --- Getters for Activity to query service state ---
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public Song getCurrentSong() {
        return currentSong;
    }

    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : currentPosition;
    }

    public int getDuration() {
        return mediaPlayer != null && isPrepared ? mediaPlayer.getDuration() : 0;
    }

    public boolean isMediaPlayerPrepared() {
        return isPrepared;
    }

    public void setOnSongChangedListener(OnSongChangedListener listener) {
        this.listener = listener;
    }

    // --- Shuffle & Repeat Logic ---
    public void toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled;
        if (isShuffleEnabled) {
            Log.d(TAG, "Shuffle enabled.");
            shuffleSongList();
        } else {
            Log.d(TAG, "Shuffle disabled.");
            resetActiveSongListOrder();
        }
        Toast.makeText(this, "Shuffle: " + (isShuffleEnabled ? "On" : "Off"), Toast.LENGTH_SHORT).show();
        // Notify UI about shuffle state change if needed
        if (listener != null) {
            listener.onPlaybackStateChanged(isPlaying()); // Re-trigger UI update to reflect shuffle icon
        }
    }

    public boolean isShuffleEnabled() {
        return isShuffleEnabled;
    }

    private void shuffleSongList() {
        if (songList.isEmpty()) return;

        // Create a new shuffled list
        List<Song> newActiveList = new ArrayList<>(songList);

        // If a song is currently playing, ensure it remains at the beginning
        // or its position is preserved in the new shuffled list.
        if (currentSong != null) {
            // Find its original index
            int originalIndex = -1;
            for(int i = 0; i < newActiveList.size(); i++) {
                if(newActiveList.get(i).getId() == currentSong.getId()) {
                    originalIndex = i;
                    break;
                }
            }

            if (originalIndex != -1) {
                // Remove current song, shuffle the rest, then re-add current song to active position
                Song tempCurrentSong = newActiveList.remove(originalIndex);
                Collections.shuffle(newActiveList);
                newActiveList.add(0, tempCurrentSong); // Place current song at the start of the shuffled list
                currentSongIndex = 0; // Update index to 0
            } else {
                // Should not happen if currentSong came from songList, but as a fallback
                Collections.shuffle(newActiveList);
                currentSongIndex = 0; // Play the first song of the newly shuffled list
            }
        } else {
            Collections.shuffle(newActiveList);
            currentSongIndex = 0; // Default to first song if no current song
        }
        this.activeSongList = newActiveList;
        Log.d(TAG, "Song list shuffled. Current song index: " + currentSongIndex);
    }

    private void resetActiveSongListOrder() {
        if (songList.isEmpty()) return;
        this.activeSongList = new ArrayList<>(songList); // Reset to original order

        // Find the new index of the current song in the original order
        if (currentSong != null) {
            for (int i = 0; i < songList.size(); i++) {
                if (songList.get(i).getId() == currentSong.getId()) {
                    currentSongIndex = i;
                    Log.d(TAG, "Song list order reset. Current song " + currentSong.getTitle() + " new index: " + currentSongIndex);
                    return; // Found and set, exit
                }
            }
            // If currentSong not found (shouldn't happen if it was from songList)
            currentSongIndex = 0;
            Log.w(TAG, "Current song not found in original list after reset. Setting index to 0.");
        } else {
            currentSongIndex = -1; // No current song
            Log.d(TAG, "Song list order reset. No current song.");
        }
    }


    public void toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        String toastMessage = "";
        switch (repeatMode) {
            case REPEAT_OFF:
                toastMessage = "Repeat: Off";
                break;
            case REPEAT_ALL:
                toastMessage = "Repeat: All";
                break;
            case REPEAT_ONE:
                toastMessage = "Repeat: One";
                break;
        }
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Repeat mode toggled to: " + repeatMode);
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(int mode, boolean showToast) {
        if (mode >= REPEAT_OFF && mode <= REPEAT_ONE) {
            repeatMode = mode;
            Log.d(TAG, "Repeat mode set to: " + repeatMode + " (via setRepeatMode)");
            if (showToast) {
                String toastMessage = "";
                switch (repeatMode) {
                    case REPEAT_OFF: toastMessage = "Repeat: Off"; break;
                    case REPEAT_ALL: toastMessage = "Repeat: All"; break;
                    case REPEAT_ONE: toastMessage = "Repeat: One"; break;
                }
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "Attempted to set invalid repeat mode: " + mode);
        }
    }

    public void setShuffle(boolean enable) {
        isShuffleEnabled = enable;
        if (isShuffleEnabled) {
            shuffleSongList();
        } else {
            resetActiveSongListOrder();
        }
        Log.d(TAG, "Shuffle set to: " + isShuffleEnabled + " (via setShuffle)");
    }

    // --- Notification Handling ---

    /** Creates the notification channel for Android O and above. */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Player Channel",
                    NotificationManager.IMPORTANCE_LOW // Low importance to prevent heads-up notifications
            );
            serviceChannel.setDescription("Notification channel for music playback controls");
            notificationManager.createNotificationChannel(serviceChannel);
            Log.d(TAG, "Notification channel created (Android O+).");
        }
    }

    /** Builds and returns the Notification for the foreground service. */
    private Notification createNotification(Song song, boolean isPlaying) {
        // Use RemoteViews for custom notification layout
        RemoteViews notificationLayout = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
        RemoteViews notificationLayoutExpanded = new RemoteViews(getPackageName(), R.layout.notification_expanded);

        // Set song title and artist
        if (song != null) {
            notificationLayout.setTextViewText(R.id.notification_song_title, song.getTitle());
            notificationLayout.setTextViewText(R.id.notification_song_artist, song.getArtist());
            notificationLayoutExpanded.setTextViewText(R.id.notification_song_title_expanded, song.getTitle());
            notificationLayoutExpanded.setTextViewText(R.id.notification_song_artist_expanded, song.getArtist());
        } else {
            notificationLayout.setTextViewText(R.id.notification_song_title, "No song playing");
            notificationLayout.setTextViewText(R.id.notification_song_artist, "");
            notificationLayoutExpanded.setTextViewText(R.id.notification_song_title_expanded, "No song playing");
            notificationLayoutExpanded.setTextViewText(R.id.notification_song_artist_expanded, "");
        }

        // Set play/pause icon based on playback state
        int playPauseIcon = isPlaying ? R.drawable.ic_pause_white_24dp : R.drawable.ic_play_arrow_white_24dp;
        String playPauseAction = isPlaying ? ACTION_PAUSE : ACTION_PLAY;

        notificationLayout.setImageViewResource(R.id.notification_play_pause, playPauseIcon);
        notificationLayoutExpanded.setImageViewResource(R.id.notification_play_pause_expanded, playPauseIcon);

        // Create PendingIntents for notification actions
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 0,
                new Intent(this, MusicService.class).setAction(playPauseAction),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 1, // Use unique request codes
                new Intent(this, MusicService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent previousPendingIntent = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_PREVIOUS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 3,
                new Intent(this, MusicService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Set OnClickPendingIntents for buttons
        notificationLayout.setOnClickPendingIntent(R.id.notification_play_pause, playPausePendingIntent);
        notificationLayout.setOnClickPendingIntent(R.id.notification_next, nextPendingIntent);
        notificationLayout.setOnClickPendingIntent(R.id.notification_previous, previousPendingIntent);
        notificationLayout.setOnClickPendingIntent(R.id.notification_stop, stopPendingIntent);

        notificationLayoutExpanded.setOnClickPendingIntent(R.id.notification_play_pause_expanded, playPausePendingIntent);
        notificationLayoutExpanded.setOnClickPendingIntent(R.id.notification_next_expanded, nextPendingIntent);
        notificationLayoutExpanded.setOnClickPendingIntent(R.id.notification_previous_expanded, previousPendingIntent);
        notificationLayoutExpanded.setOnClickPendingIntent(R.id.notification_stop_expanded, stopPendingIntent);

        // Intent to open MainActivity when notification is clicked
        Intent notificationIntent = new Intent(this, MainActivity.class);
        // Flags to bring existing activity to front rather than creating new one
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note_white_24dp)
                .setContentTitle("Music Player")
                .setContentText(song != null ? song.getTitle() : "No song playing")
                .setPriority(NotificationCompat.PRIORITY_LOW) // Important: keep priority low for background
                .setOnlyAlertOnce(true) // Don't make sound/vibrate on every update
                .setCustomContentView(notificationLayout)
                .setCustomBigContentView(notificationLayoutExpanded)
                .setContentIntent(contentIntent) // Click on notification body opens app
                .setOngoing(isPlaying); // Set to true if playing to make it non-dismissible by swipe

        // Optional: Add a delete intent for when the notification is dismissed by user (if setOngoing is false)
        // If setOngoing(true), the user can only dismiss it via the ACTION_STOP button or by stopping the service explicitly.
        if (!isPlaying) { // If not playing, allow it to be dismissible
            Intent deleteIntent = new Intent(this, MusicService.class);
            deleteIntent.setAction(ACTION_STOP); // Use your stop action
            PendingIntent deletePendingIntent = PendingIntent.getService(this, 4, deleteIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.setDeleteIntent(deletePendingIntent);
        }

        return builder.build();
    }

    // --- BroadcastReceiver for Notification Actions (Internal to service) ---
    private BroadcastReceiver notificationActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Notification Action Received: " + action);
            if (action != null) {
                switch (action) {
                    case ACTION_PLAY:
                        play();
                        break;
                    case ACTION_PAUSE:
                        pause();
                        break;
                    case ACTION_NEXT:
                        playNextSong();
                        break;
                    case ACTION_PREVIOUS:
                        playPreviousSong();
                        break;
                    case ACTION_STOP:
                        Log.d(TAG, "Notification explicit STOP action received. Calling stopSelf().");
                        pause(); // Pause before stopping
                        stopSelf(); // Stop the service
                        break;
                    default:
                        Log.w(TAG, "Unknown notification action: " + action);
                        break;
                }
            }
        }
    };

    // --- Service Binder Class ---
    public class MusicBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }
}