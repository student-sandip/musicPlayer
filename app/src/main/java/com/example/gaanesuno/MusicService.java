package com.example.gaanesuno;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {

    private static final String TAG = "MusicService";

    // Action Constants for Intents
    public static final String ACTION_PLAY = "com.example.gaanesuno.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.gaanesuno.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.example.gaanesuno.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.example.gaanesuno.ACTION_PREVIOUS";
    public static final String ACTION_STOP = "com.example.gaanesuno.ACTION_STOP";

    // Constants for repeat mode
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private MediaPlayer mediaPlayer;
    private List<Song> originalSongList;
    private List<Song> activeSongList;
    private int currentSongIndex = -1;
    private Song currentSong = null;

    private boolean shuffleEnabled = false;
    private int repeatMode = REPEAT_OFF;

    private final IBinder musicBinder = new MusicBinder();
    private OnSongChangedListener songChangedListener;
    private Handler handler = new Handler();
    private Runnable runnable;

    public static final String CHANNEL_ID = "GaaneSunoPlaybackChannel";
    public static final int NOTIFICATION_ID = 101;

    // Flag for starting playback after preparing a newly selected song (from UI interaction)
    private boolean shouldStartPlaybackOnPrepared = false;

    // Fields to store pending restore state for onPrepared
    private int pendingRestorePosition = 0; // Stores the seek position for restoration
    private boolean pendingPlayOnRestore = false; // Stores if playback should resume on restore


    // Flag to indicate if MediaPlayer is prepared and ready for playback/seeking
    private boolean isMediaPlayerPreparedFlag = false;


    public interface OnSongChangedListener {
        void onSongChanged(Song song, boolean isPlaying);
        void onPlaybackStateChanged(boolean isPlaying);
        void onProgressUpdate(int currentPosition, int duration);
    }

    public void setOnSongChangedListener(OnSongChangedListener listener) {
        this.songChangedListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnPreparedListener(this);

        originalSongList = new ArrayList<>();
        activeSongList = new ArrayList<>();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return musicBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopSeekBarUpdates();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            startSeekBarUpdates();
        }
    }

    public class MusicBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            Log.d(TAG, "Received action: " + intent.getAction());
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    if (currentSong == null && !activeSongList.isEmpty()) {
                        playSong(0); // If no song loaded, play the first
                    } else {
                        play(); // Otherwise, resume current song
                    }
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_NEXT:
                    playNextSong(); // Corrected: Call playNextSong
                    break;
                case ACTION_PREVIOUS:
                    playPreviousSong(); // Corrected: Call playPreviousSong
                    break;
                case ACTION_STOP:
                    Log.d(TAG, "ACTION_STOP received. Stopping service.");
                    stopPlaybackAndService();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    public void setSongList(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            Log.w(TAG, "Attempted to set an empty or null song list.");
            originalSongList.clear();
            activeSongList.clear();
            currentSong = null;
            currentSongIndex = -1;
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
            }
            isMediaPlayerPreparedFlag = false;
            stopForeground(true);
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(null, false);
            }
            return;
        }

        this.originalSongList = new ArrayList<>(songs);

        Song previouslyPlayingSong = currentSong;

        this.activeSongList = new ArrayList<>(originalSongList);
        if (shuffleEnabled) {
            if (previouslyPlayingSong != null && activeSongList.contains(previouslyPlayingSong)) {
                activeSongList.remove(previouslyPlayingSong);
                Collections.shuffle(activeSongList);
                activeSongList.add(0, previouslyPlayingSong);
                currentSongIndex = 0;
            } else {
                Collections.shuffle(activeSongList);
                currentSongIndex = 0;
                currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
            }
        } else {
            if (previouslyPlayingSong != null) {
                int newIndex = activeSongList.indexOf(previouslyPlayingSong);
                if (newIndex != -1) {
                    currentSongIndex = newIndex;
                    currentSong = previouslyPlayingSong;
                } else {
                    Log.w(TAG, "Previously playing song not found in new original list. Resetting current song.");
                    currentSongIndex = -1;
                    currentSong = null;
                }
            } else {
                currentSongIndex = activeSongList.isEmpty() ? -1 : 0;
                currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
            }
        }

        Log.d(TAG, "Song list set in service. Original Size: " + originalSongList.size() + ", Active Size: " + activeSongList.size() + ", Current Song Index: " + currentSongIndex);
    }

    public void playSong(int songIndex) {
        if (activeSongList.isEmpty() || songIndex < 0 || songIndex >= activeSongList.size()) {
            Log.e(TAG, "Invalid song index or empty list: " + songIndex);
            Toast.makeText(this, "No song available to play.", Toast.LENGTH_SHORT).show();
            stopPlaybackAndService();
            return;
        }

        currentSongIndex = songIndex;
        currentSong = activeSongList.get(currentSongIndex);
        Log.d(TAG, "Attempting to play: " + currentSong.getTitle() + " at index: " + currentSongIndex);

        mediaPlayer.reset();
        isMediaPlayerPreparedFlag = false; // Not prepared yet
        shouldStartPlaybackOnPrepared = true; // This song should start playing after prepare
        pendingPlayOnRestore = false; // Ensure this is false for new song selection
        pendingRestorePosition = 0; // Always start from beginning for new song selection

        try {
            mediaPlayer.setDataSource(getApplicationContext(), currentSong.getData());
            mediaPlayer.prepareAsync();
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(currentSong, false); // Inform UI song is preparing
            }
            updateNotification();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error setting data source or preparing: " + e.getMessage());
            e.printStackTrace();
            isMediaPlayerPreparedFlag = false;
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(null, false);
            }
            Toast.makeText(this, "Error playing song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            playNextSong(); // Try playing the next song
        }
    }

    /**
     * Prepares a song for restoration from a saved state.
     * @param song The song to prepare.
     * @param index The index of the song in the active list.
     * @param wasPlaying A flag indicating if the song was playing when the app was closed.
     * @param restorePosition The position in milliseconds to seek to after preparation.
     */
    public void prepareSongForRestore(Song song, int index, boolean wasPlaying, int restorePosition) {
        if (song == null || index < 0 || (activeSongList != null && index >= activeSongList.size())) {
            Log.w(TAG, "Invalid song or index for restore preparation: " + (song != null ? song.getTitle() : "null") + ", Index: " + index);
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
            }
            isMediaPlayerPreparedFlag = false;
            shouldStartPlaybackOnPrepared = false;
            pendingPlayOnRestore = false;
            pendingRestorePosition = 0;
            currentSong = null;
            currentSongIndex = -1;
            return;
        }

        this.currentSong = song;
        this.currentSongIndex = index;
        shouldStartPlaybackOnPrepared = false; // Not a new playback command from UI
        pendingPlayOnRestore = wasPlaying; // Set based on saved state
        pendingRestorePosition = restorePosition; // Store the restore position

        mediaPlayer.reset();
        isMediaPlayerPreparedFlag = false;

        try {
            mediaPlayer.setDataSource(getApplicationContext(), currentSong.getData());
            mediaPlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error setting data source or preparing for restore: " + e.getMessage());
            e.printStackTrace();
            mediaPlayer.reset();
            isMediaPlayerPreparedFlag = false;
            pendingPlayOnRestore = false; // Reset if error
            pendingRestorePosition = 0; // Reset if error
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Log.d(TAG, "MediaPlayer prepared.");
        isMediaPlayerPreparedFlag = true;

        // Apply seek position if it's a restore operation
        if (pendingRestorePosition > 0) {
            mp.seekTo(pendingRestorePosition);
            Log.d(TAG, "Seeked to restored position: " + pendingRestorePosition);
            pendingRestorePosition = 0; // Reset after use
        }

        // Determine if playback should start or resume
        if (shouldStartPlaybackOnPrepared || pendingPlayOnRestore) {
            mp.start();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(true);
            }
            startSeekBarUpdates();
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "Playback started after prepare (new song or resume).");
        } else {
            // Prepared but not starting playback (e.g., app just opened to last song but it was paused)
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(false);
            }
            updateNotification(); // Update notification to reflect paused state
            Log.d(TAG, "MediaPlayer prepared, but not starting playback automatically (was paused).");
        }
        // Reset flags after use
        shouldStartPlaybackOnPrepared = false;
        pendingPlayOnRestore = false;

        // Inform UI about song change (even if paused, to update title/artist)
        if (songChangedListener != null) {
            songChangedListener.onSongChanged(currentSong, isPlaying());
        }
    }

    public void play() {
        if (mediaPlayer != null && isMediaPlayerPreparedFlag) {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                if (songChangedListener != null) {
                    songChangedListener.onPlaybackStateChanged(true);
                }
                startSeekBarUpdates();
                startForeground(NOTIFICATION_ID, createNotification());
                Log.d(TAG, "Resumed playing: " + (currentSong != null ? currentSong.getTitle() : "N/A"));
            }
        } else {
            Log.w(TAG, "Cannot play: MediaPlayer is null or not prepared. Attempting to load last known song.");
            // If play() is called but player isn't ready (e.g. initial start, or after error),
            // attempt to load and play the last known song or first song.
            if (currentSong != null && currentSongIndex != -1) {
                playSong(currentSongIndex); // This will set shouldStartPlaybackOnPrepared to true
            } else if (activeSongList != null && !activeSongList.isEmpty()) {
                playSong(0); // This will set shouldStartPlaybackOnPrepared to true
            } else {
                Toast.makeText(this, "No songs to play.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(false);
            }
            stopSeekBarUpdates();
            stopForeground(false); // Keep notification visible but remove foreground state
            updateNotification();
            Log.d(TAG, "Paused song: " + (currentSong != null ? currentSong.getTitle() : "N/A"));
        } else {
            Log.w(TAG, "Cannot pause: MediaPlayer is null or not playing.");
        }
    }

    public boolean isPlaying() {
        try {
            return mediaPlayer != null && isMediaPlayerPreparedFlag && mediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in isPlaying: " + e.getMessage());
            return false;
        }
    }

    public boolean isMediaPlayerPrepared() {
        return isMediaPlayerPreparedFlag;
    }

    public int getCurrentPosition() {
        try {
            return mediaPlayer != null && isMediaPlayerPreparedFlag ? mediaPlayer.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in getCurrentPosition: " + e.getMessage());
            return 0;
        }
    }

    public int getDuration() {
        try {
            return mediaPlayer != null && isMediaPlayerPreparedFlag ? mediaPlayer.getDuration() : 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in getDuration: " + e.getMessage());
            return 0;
        }
    }

    public void seekTo(int position) {
        if (mediaPlayer != null && isMediaPlayerPreparedFlag) {
            try {
                mediaPlayer.seekTo(position);
                Log.d(TAG, "Seeked to: " + position);
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException in seekTo: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot seek: MediaPlayer is null or not prepared.");
        }
    }

    public Song getCurrentSong() {
        return currentSong;
    }

    // Renamed from publicPlayNextSong for clarity since it's the internal logic.
    // MainActivity should call this via service binder.
    public void playNextSong() {
        if (activeSongList == null || activeSongList.isEmpty()) {
            stopPlaybackAndService();
            return;
        }

        int nextIndex = currentSongIndex;
        if (repeatMode == REPEAT_ONE) {
            // Stay on the same song
        } else {
            nextIndex = (currentSongIndex + 1) % activeSongList.size();
        }
        playSong(nextIndex);
    }

    // Renamed from publicPlayPreviousSong for clarity.
    public void playPreviousSong() {
        if (activeSongList == null || activeSongList.isEmpty()) {
            stopPlaybackAndService();
            return;
        }

        int prevIndex = currentSongIndex;
        if (repeatMode == REPEAT_ONE) {
            // Stay on the same song
        } else {
            prevIndex = (currentSongIndex - 1 + activeSongList.size()) % activeSongList.size();
        }
        playSong(prevIndex);
    }

    public void toggleShuffle() {
        setShuffle(!shuffleEnabled);
    }

    public void setShuffle(boolean enable) {
        if (this.shuffleEnabled != enable) {
            this.shuffleEnabled = enable;
            Toast.makeText(this, "Shuffle " + (shuffleEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();

            Song songToKeep = currentSong; // Remember the song currently playing

            if (shuffleEnabled) {
                // When enabling shuffle, rebuild active list from original, then shuffle
                activeSongList = new ArrayList<>(originalSongList);
                if (songToKeep != null && activeSongList.contains(songToKeep)) {
                    activeSongList.remove(songToKeep); // Remove current song temporarily
                    Collections.shuffle(activeSongList); // Shuffle the rest
                    activeSongList.add(0, songToKeep); // Add current song to the beginning
                    currentSongIndex = 0; // Current song is now at index 0
                } else {
                    Collections.shuffle(activeSongList);
                    currentSongIndex = 0; // Default to first song in new shuffled list
                    currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
                }
            } else {
                // When disabling shuffle, revert to original order
                activeSongList = new ArrayList<>(originalSongList);
                if (songToKeep != null) {
                    int newIndex = activeSongList.indexOf(songToKeep);
                    if (newIndex != -1) {
                        currentSongIndex = newIndex; // Find the current song's index in original list
                    } else {
                        Log.w(TAG, "Playing song not found in original list after unshuffle. Resetting index.");
                        currentSongIndex = 0; // Fallback
                        currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
                    }
                } else {
                    currentSongIndex = 0; // Default to first song in original list
                    currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
                }
            }
            // Inform UI about song change (index might have changed, or song might be null)
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(currentSong, isPlaying());
            }
            updateNotification();
        }
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public void toggleRepeat() {
        setRepeatMode((repeatMode + 1) % 3, true); // Cycle through 0, 1, 2
    }

    public void setRepeatMode(int mode, boolean showToast) {
        this.repeatMode = mode;
        String toastText = "";
        switch (repeatMode) {
            case REPEAT_OFF:
                toastText = "Repeat OFF";
                break;
            case REPEAT_ALL:
                toastText = "Repeat ALL";
                break;
            case REPEAT_ONE:
                toastText = "Repeat ONE";
                break;
        }
        if (showToast) {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
        }
        updateNotification();
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public List<Song> getActiveSongList() {
        return activeSongList;
    }

    // These setters are generally not needed for external control if `playSong` is used.
    // They are primarily for internal state management within the service or for initial setup.
    public void setCurrentSong(Song song) {
        this.currentSong = song;
    }

    public void setCurrentSongIndex(int index) {
        this.currentSongIndex = index;
    }

    private void startSeekBarUpdates() {
        if (runnable == null) {
            runnable = new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null && mediaPlayer.isPlaying() && isMediaPlayerPreparedFlag) {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        if (songChangedListener != null) {
                            if (duration > 0) { // Avoid division by zero
                                songChangedListener.onProgressUpdate(currentPosition, duration);
                            }
                        }
                    }
                    handler.postDelayed(this, 1000); // Update every second
                }
            };
        }
        handler.post(runnable);
    }

    private void stopSeekBarUpdates() {
        if (runnable != null) {
            handler.removeCallbacks(runnable);
            runnable = null;
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Song completed. Handling next song based on repeat mode.");
        if (repeatMode == REPEAT_ONE) {
            playSong(currentSongIndex); // Replay current song
        } else if (repeatMode == REPEAT_ALL) {
            playNextSong(); // Play next song, looping to start if at end
        } else { // REPEAT_OFF
            if (currentSongIndex == activeSongList.size() - 1) {
                // If it's the last song and repeat is off, stop playback
                stopPlaybackAndService();
                Toast.makeText(this, "Playback finished.", Toast.LENGTH_SHORT).show();
            } else {
                playNextSong(); // Play next song normally
            }
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
        mp.reset();
        isMediaPlayerPreparedFlag = false;
        shouldStartPlaybackOnPrepared = false; // Reset error state
        pendingPlayOnRestore = false; // Reset error state
        pendingRestorePosition = 0; // Reset error state

        if (songChangedListener != null) {
            songChangedListener.onSongChanged(null, false); // Inform UI about error/no song
        }
        Toast.makeText(this, "Error playing music. Skipping song.", Toast.LENGTH_SHORT).show();
        playNextSong(); // Try playing the next song automatically
        return true; // Indicate that the error was handled
    }

    private void stopPlaybackAndService() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.reset();
        }
        isMediaPlayerPreparedFlag = false;
        shouldStartPlaybackOnPrepared = false;
        pendingPlayOnRestore = false;
        pendingRestorePosition = 0;
        currentSong = null;
        currentSongIndex = -1;
        stopSeekBarUpdates();
        stopForeground(true); // Remove notification and stop foreground state
        stopSelf(); // Stop the service itself
        if (songChangedListener != null) {
            songChangedListener.onSongChanged(null, false); // Clear UI song info
            songChangedListener.onPlaybackStateChanged(false); // Update UI to paused state
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release(); // Release MediaPlayer resources
            mediaPlayer = null;
        }
        stopSeekBarUpdates();
        stopForeground(true); // Ensure notification is removed
        Log.d(TAG, "MusicService onDestroy called.");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: App cleared from recents. Stopping service and releasing resources.");
        stopPlaybackAndService(); // Stop everything when app is swiped away
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't make a sound/vibration
            );
            serviceChannel.setDescription("Controls for music playback");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification() {
        Song current = getCurrentSong();
        String title = (current != null) ? current.getTitle() : "No Song Playing";
        String artist = (current != null) ? current.getArtist() : "Unknown Artist";

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        // Flags to bring existing activity to front rather than creating new one
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Action intents for notification buttons
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 1,
                new Intent(this, MusicService.class).setAction(isPlaying() ? ACTION_PAUSE : ACTION_PLAY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent nextPendingIntent = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent previousPendingIntent = PendingIntent.getService(this, 3,
                new Intent(this, MusicService.class).setAction(ACTION_PREVIOUS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopPendingIntent = PendingIntent.getService(this, 4,
                new Intent(this, MusicService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_music_note_white_24dp)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true) // Notification will only alert once unless cleared/re-created
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT) // Categorize as media playback
                .setOngoing(isPlaying()); // Make it ongoing if playing, dismissible if paused

        MediaStyle mediaStyle = new MediaStyle()
                // Show actions in compact view (index 0, 1, 2 = previous, play/pause, next)
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(null); // No MediaSessionToken for simplicity in this example

        builder.setStyle(mediaStyle);

        // Add actions (buttons) to the notification
        builder.addAction(R.drawable.ic_skip_previous_white_24dp, "Previous", previousPendingIntent);
        if (isPlaying()) {
            builder.addAction(R.drawable.ic_pause_white_24dp, "Pause", playPausePendingIntent);
        } else {
            builder.addAction(R.drawable.ic_play_arrow_white_24dp, "Play", playPausePendingIntent);
        }
        builder.addAction(R.drawable.ic_skip_next_white_24dp, "Next", nextPendingIntent);
        builder.addAction(R.drawable.ic_close_white_24dp, "Stop", stopPendingIntent);

        return builder.build();
    }

    private void updateNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            // Use startForeground here to update an existing foreground notification.
            // If the service is not yet foreground, it will become foreground.
            // If it's already foreground, it will just update the notification.
            if (isPlaying()) {
                startForeground(NOTIFICATION_ID, createNotification());
            } else {
                // If paused, stop foreground but keep notification
                stopForeground(false);
                notificationManager.notify(NOTIFICATION_ID, createNotification());
            }
        }
    }
}