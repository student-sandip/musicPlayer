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

// IMPORTANT: Add this import for MediaStyle
import androidx.media.app.NotificationCompat.MediaStyle; // Explicitly import MediaStyle


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MusicService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {

    private static final String TAG = "MusicService";
    public static final String ACTION_PLAY = "com.example.gaanesuno.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.gaanesuno.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.example.gaanesuno.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.example.gaanesuno.ACTION_PREVIOUS";

    private MediaPlayer mediaPlayer;
    private List<Song> songList;
    private int currentSongIndex = -1;

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
        void onProgressUpdate(int currentPosition, int duration);
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

        songList = new ArrayList<>(); // Initialize the song list here
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
                    if (mediaPlayer != null && !mediaPlayer.isPlaying() && currentSongIndex != -1) {
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
        this.songList = songs;
        Log.d(TAG, "Song list set in service. Size: " + songList.size());
    }

    public void playSong(int songIndex) {
        if (songIndex < 0 || songIndex >= songList.size()) {
            Log.e(TAG, "Invalid song index: " + songIndex);
            return;
        }

        currentSongIndex = songIndex;
        Song currentSong = songList.get(currentSongIndex);
        Log.d(TAG, "Attempting to play: " + currentSong.getTitle());

        mediaPlayer.reset();
        try {
            mediaPlayer.setDataSource(getApplicationContext(), currentSong.getData());
            mediaPlayer.prepareAsync(); // Prepare asynchronously to avoid blocking UI
            // onPrepared() will be called when ready to play
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(currentSong, false); // Inform UI song is preparing
            }
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source: " + e.getMessage());
            e.printStackTrace();
            if (songChangedListener != null) {
                songChangedListener.onSongChanged(null, false); // Inform UI about error
            }
            Toast.makeText(this, "Error playing song: " + e.getMessage(), Toast.LENGTH_SHORT).show(); // Show toast on error
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        // MediaPlayer is prepared, start playing
        play();
    }

    public void play() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) { // Added null check for mediaPlayer
            mediaPlayer.start();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(true);
            }
            startSeekBarUpdates();
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "Playing song: " + songList.get(currentSongIndex).getTitle());
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) { // Added null check for mediaPlayer
            mediaPlayer.pause();
            if (songChangedListener != null) {
                songChangedListener.onPlaybackStateChanged(false);
            }
            stopSeekBarUpdates();
            stopForeground(false); // Keep notification visible
            updateNotification();
            Log.d(TAG, "Paused song: " + songList.get(currentSongIndex).getTitle());
        }
    }

    public void playNextSong() {
        if (songList.isEmpty()) {
            Log.d(TAG, "Song list is empty, cannot play next song.");
            return;
        }
        currentSongIndex = (currentSongIndex + 1) % songList.size();
        playSong(currentSongIndex);
    }

    public void playPreviousSong() {
        if (songList.isEmpty()) {
            Log.d(TAG, "Song list is empty, cannot play previous song.");
            return;
        }
        currentSongIndex = (currentSongIndex - 1 + songList.size()) % songList.size();
        playSong(currentSongIndex);
    }

    public boolean isPlaying() {
        try { // Added try-catch for isPlaying to prevent IllegalStateException if MediaPlayer is not in valid state
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
            return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
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
        if (currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            return songList.get(currentSongIndex);
        }
        return null;
    }

    private void startSeekBarUpdates() {
        if (runnable == null) {
            runnable = new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        if (songChangedListener != null) {
                            songChangedListener.onProgressUpdate(currentPosition, duration);
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


    //region MediaPlayer.OnCompletionListener
    @Override
    public void onCompletion(MediaPlayer mp) {
        Log.d(TAG, "Song completed. Playing next.");
        playNextSong();
    }
    //endregion

    //region MediaPlayer.OnErrorListener
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
        Song current = getCurrentSong();
        String title = (current != null) ? current.getTitle() : "No Song";
        String artist = (current != null) ? current.getArtist() : "Unknown Artist";

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE); // Use FLAG_IMMUTABLE

        // Intent for playback actions (Play/Pause, Next, Previous)
        Intent playPauseIntent = new Intent(this, MusicService.class).setAction(ACTION_PLAY);
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            playPauseIntent.setAction(ACTION_PAUSE); // If playing, button should pause
        }
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, MusicService.class).setAction(ACTION_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 0, nextIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent previousIntent = new Intent(this, MusicService.class).setAction(ACTION_PREVIOUS);
        PendingIntent previousPendingIntent = PendingIntent.getService(this, 0, previousIntent, PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_music_note_white_24dp) // You'll need to create this drawable
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Consistent with channel importance
                .setOnlyAlertOnce(true) // Don't make sound/vibrate on updates
                .addAction(android.R.drawable.ic_media_previous, "Previous", previousPendingIntent)
                .addAction((mediaPlayer != null && mediaPlayer.isPlaying() ?
                                android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), // Dynamic icon
                        (mediaPlayer != null && mediaPlayer.isPlaying() ? "Pause" : "Play"), playPausePendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                .setStyle(new MediaStyle()) // FIX: Removed the full package path here, relying on the import
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