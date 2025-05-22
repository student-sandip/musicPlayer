package com.example.gaanesuno;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast; // Added Toast import

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle; // Explicitly import MediaStyle

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections; // Import for Collections.shuffle()
import java.util.List;

public class MusicService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {

    private static final String TAG = "MusicService";
    public static final String ACTION_PLAY = "com.example.gaanesuno.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.gaanesuno.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.example.gaanesuno.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.example.gaanesuno.ACTION_PREVIOUS";

    // Constants for repeat mode
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private MediaPlayer mediaPlayer;
    private List<Song> originalSongList; // Stores the original, ordered list of songs
    private List<Song> activeSongList;   // The list currently used for playback (can be shuffled)
    private int currentSongIndex = -1;
    private Song currentSong = null; // Store the actual Song object to handle shuffle changes

    private boolean shuffleEnabled = false;
    private int repeatMode = REPEAT_OFF;

    // Binder for clients to interact with the service
    private final IBinder musicBinder = new MusicBinder();

    // Listener to inform MainActivity about song changes, play/pause status
    private OnSongChangedListener songChangedListener;

    // Handler for updating seekbar
    private Handler handler = new Handler();
    private Runnable runnable;

    // Notification Channel ID
    public static final String CHANNEL_ID = "GaaneSunoPlaybackChannel";
    public static final int NOTIFICATION_ID = 101;


    public interface OnSongChangedListener {
        void onSongChanged(Song song, boolean isPlaying);
        void onPlaybackStateChanged(boolean isPlaying);
        void onProgressUpdate(int currentPosition, int duration); // Current position and total duration
    }

    public void setOnSongChangedListener(OnSongChangedListener listener) {
        this.songChangedListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
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
        // Stop updating seekbar when UI is unbinded
        stopSeekBarUpdates();
        return true; // Allow rebind
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        // Restart seekbar updates when UI rebinds
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
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    // If no song selected yet, and list is not empty, play the first one
                    if (currentSongIndex == -1 && !activeSongList.isEmpty()) {
                        playSong(0);
                    } else if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                        play();
                    }
                    break;
                case ACTION_PAUSE:
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        pause();
                    }
                    break;
                case ACTION_NEXT:
                    playNextSong();
                    break;
                case ACTION_PREVIOUS:
                    playPreviousSong();
                    break;
            }
        }
        return START_STICKY; // Service will be restarted if killed
    }

    public void setSongList(List<Song> songs) {
        // Always store the original list order
        this.originalSongList = new ArrayList<>(songs);

        // Update active song list based on shuffle state
        Song previouslyPlayingSong = null;
        if (currentSong != null) {
            previouslyPlayingSong = currentSong;
        }

        this.activeSongList = new ArrayList<>(originalSongList); // Start with original order
        if (shuffleEnabled) {
            Collections.shuffle(activeSongList); // Shuffle if shuffle is active
        }

        // If a song was playing, try to maintain its position in the new active list
        if (previouslyPlayingSong != null) {
            int newIndex = activeSongList.indexOf(previouslyPlayingSong);
            if (newIndex != -1) {
                currentSongIndex = newIndex;
                currentSong = previouslyPlayingSong; // Keep the same song object
            } else {
                // Should not happen if the song is still in originalSongList
                // But as a fallback, reset index or log error
                Log.w(TAG, "Previously playing song not found in new active list after setSongList.");
                currentSongIndex = -1; // No song selected
                currentSong = null;
            }
        } else {
            // If no song was playing, ensure index is valid if list is not empty
            currentSongIndex = activeSongList.isEmpty() ? -1 : 0;
            currentSong = activeSongList.isEmpty() ? null : activeSongList.get(currentSongIndex);
        }

        Log.d(TAG, "Song list set in service. Original Size: " + originalSongList.size() + ", Active Size: " + activeSongList.size());
    }

    public void playSong(int songIndex) {
        if (activeSongList.isEmpty() || songIndex < 0 || songIndex >= activeSongList.size()) {
            Log.e(TAG, "Invalid song index or empty list: " + songIndex);
            Toast.makeText(this, "No song available to play.", Toast.LENGTH_SHORT).show();
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(null, false);
            }
            stopForeground(true); // Stop foreground if no song to play
            return;
        }

        currentSongIndex = songIndex;
        currentSong = activeSongList.get(currentSongIndex);
        Log.d(TAG, "Attempting to play: " + currentSong.getTitle() + " at index: " + currentSongIndex);

        mediaPlayer.reset();
        try {
            mediaPlayer.setDataSource(getApplicationContext(), currentSong.getData());
            mediaPlayer.prepareAsync(); // Prepare asynchronously to avoid blocking UI
            // onPrepared() will be called when ready to play
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(currentSong, false); // Inform UI song is preparing
            }
            updateNotification(); // Update notification (e.g., loading state)
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source: " + e.getMessage());
            e.printStackTrace();
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(null, false); // Inform UI about error
            }
            Toast.makeText(this, "Error playing song: " + e.getMessage(), Toast.LENGTH_SHORT).show(); // Show toast on error
            playNextSong(); // Try playing the next song
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        // MediaPlayer is prepared, start playing
        play();
    }

    public void play() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(true);
            }
            startSeekBarUpdates();
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "Playing song: " + currentSong.getTitle());
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(false);
            }
            stopSeekBarUpdates();
            stopForeground(false); // Keep notification visible
            updateNotification(); // Update notification with pause button
            Log.d(TAG, "Paused song: " + currentSong.getTitle());
        }
    }

    public void playNextSong() {
        if (activeSongList.isEmpty()) {
            Log.d(TAG, "Active song list is empty, cannot play next song.");
            return;
        }

        if (repeatMode == REPEAT_ONE) {
            playSong(currentSongIndex); // Replay current song
            return;
        }

        currentSongIndex++;
        if (currentSongIndex >= activeSongList.size()) {
            if (repeatMode == REPEAT_ALL) {
                currentSongIndex = 0; // Loop back to start
            } else {
                // Stop playback at end of list if no repeat
                mediaPlayer.stop();
                currentSong = null;
                currentSongIndex = -1; // Reset index
                if (songChangedListener != null) {
                    songChangedListener.onSongChanged(null, false);
                }
                stopForeground(true); // Remove notification
                Toast.makeText(this, "Playback finished.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        playSong(currentSongIndex);
    }

    public void playPreviousSong() {
        if (activeSongList.isEmpty()) {
            Log.d(TAG, "Active song list is empty, cannot play previous song.");
            return;
        }

        if (repeatMode == REPEAT_ONE) {
            playSong(currentSongIndex); // Replay current song
            return;
        }

        currentSongIndex--;
        if (currentSongIndex < 0) {
            if (repeatMode == REPEAT_ALL) {
                currentSongIndex = activeSongList.size() - 1; // Loop to end
            } else {
                // If at the beginning and not repeating all, just play the first song again
                currentSongIndex = 0;
            }
        }
        playSong(currentSongIndex);
    }

    public boolean isPlaying() {
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in isPlaying: " + e.getMessage());
            return false;
        }
    }

    public int getCurrentPosition() {
        try {
            return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in getCurrentPosition: " + e.getMessage());
            return 0;
        }
    }

    public int getDuration() {
        try {
            return mediaPlayer != null && mediaPlayer.getDuration() > 0 ? mediaPlayer.getDuration() : 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException in getDuration: " + e.getMessage());
            return 0;
        }
    }

    public void seekTo(int position) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(position);
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException in seekTo: " + e.getMessage());
            }
        }
    }

    public Song getCurrentSong() {
        // Return the stored currentSong object, which is updated by playSong
        return currentSong;
    }

    //region Shuffle and Repeat Logic
    public void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        if (activeSongList.isEmpty()) {
            Toast.makeText(this, "No songs to shuffle.", Toast.LENGTH_SHORT).show();
            return;
        }

        Song songToKeep = currentSong; // Remember the currently playing song

        if (shuffleEnabled) {
            // Shuffle the active list
            Collections.shuffle(activeSongList);
            Toast.makeText(this, "Shuffle ON", Toast.LENGTH_SHORT).show();
        } else {
            // Restore original order
            activeSongList = new ArrayList<>(originalSongList);
            Toast.makeText(this, "Shuffle OFF", Toast.LENGTH_SHORT).show();
        }

        // Find the new index of the remembered song in the (re)ordered active list
        if (songToKeep != null) {
            int newIndex = activeSongList.indexOf(songToKeep);
            if (newIndex != -1) {
                currentSongIndex = newIndex;
            } else {
                // This shouldn't happen if songToKeep was from originalSongList
                // Fallback: if song not found, perhaps reset to start
                currentSongIndex = 0;
                currentSong = activeSongList.get(currentSongIndex);
                if (isPlaying()) { // If playback was active, might need to restart current song at new index
                    playSong(currentSongIndex);
                } else { // Or just update UI state without playing
                    if (songChangedListener != null) {
                        songChangedListener.onSongChanged(currentSong, false);
                    }
                }
            }
        } else {
            // If no song was playing, reset to beginning of the new active list
            currentSongIndex = 0;
            currentSong = activeSongList.get(currentSongIndex);
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(currentSong, false); // Update UI with first song of new list
            }
        }

        // No need to restart playback just for shuffle/unshuffle, but ensure UI is updated
        updateNotification(); // Re-issue notification if needed
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public void toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3; // Cycles: 0 (OFF) -> 1 (ALL) -> 2 (ONE) -> 0 (OFF)
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
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
        updateNotification(); // Re-issue notification if needed
    }

    public int getRepeatMode() {
        return repeatMode;
    }
    //endregion

    //region SeekBar Updates
    private void startSeekBarUpdates() {
        if (runnable == null) {
            runnable = new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        if (songChangedListener != null) {
                            // Check if duration is valid before updating
                            if (duration > 0) {
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
            runnable = null; // Reset runnable
        }
    }


    //region MediaPlayer Callbacks
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Song completed. Handling next song based on repeat mode.");
        playNextSong(); // This will now handle repeat logic internally
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
        mp.reset(); // Resetting to recover from error
        stopForeground(true); // Remove notification
        if (songChangedListener != null) {
            songChangedListener.onSongChanged(null, false); // Inform UI about error
        }
        Toast.makeText(this, "Error playing music. Skipping song.", Toast.LENGTH_SHORT).show();
        playNextSong(); // Attempt to play next song
        return true; // Indicate error was handled
    }
    //endregion


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        stopSeekBarUpdates();
        stopForeground(true);
        Log.d(TAG, "MusicService destroyed.");
    }


    // Notification Logic
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW // Use low importance to avoid constant sound/vibration
            );
            serviceChannel.setDescription("Notification for music playback");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createNotification() {
        // Use the currentSong object directly as it's maintained
        Song current = getCurrentSong();
        String title = (current != null) ? current.getTitle() : "No Song";
        String artist = (current != null) ? current.getArtist() : "Unknown Artist";

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Intent for playback actions (Play/Pause, Next, Previous)
        // Make sure actions are unique per PendingIntent with a unique request code
        Intent playPauseIntent = new Intent(this, MusicService.class);
        playPauseIntent.setAction(mediaPlayer != null && mediaPlayer.isPlaying() ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 1, playPauseIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, MusicService.class).setAction(ACTION_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 2, nextIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent previousIntent = new Intent(this, MusicService.class).setAction(ACTION_PREVIOUS);
        PendingIntent previousPendingIntent = PendingIntent.getService(this, 3, previousIntent, PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_music_note_white_24dp) // You'll need to create this drawable
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Consistent with channel importance
                .setOnlyAlertOnce(true) // Don't make sound/vibrate on updates
                .addAction(android.R.drawable.ic_media_previous, "Previous", previousPendingIntent)
                .addAction((mediaPlayer != null && mediaPlayer.isPlaying() ?
                                android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        (mediaPlayer != null && mediaPlayer.isPlaying() ? "Pause" : "Play"), playPausePendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                .setStyle(new MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2) // Show previous, play/pause, next in compact view
                        .setMediaSession(null)) // Set MediaSession token if you have one for lock screen controls
                .setOngoing(mediaPlayer != null && mediaPlayer.isPlaying()); // Persistent if playing

        return builder.build();
    }

    private void updateNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
    }
}