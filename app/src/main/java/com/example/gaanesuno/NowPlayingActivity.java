package com.example.gaanesuno; // Make sure this matches your package name

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class NowPlayingActivity extends AppCompatActivity implements
        MusicService.OnSongChangedListener {

    private static final String TAG = "NowPlayingActivity";

    // UI Elements
    private ImageView ivMusicIconRotating;
    private TextView tvSongTitle;
    private TextView tvSongArtist;
    private SeekBar seekbarProgress;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private ImageButton btnShuffle;
    private ImageButton btnPrevious;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;
    private ImageButton btnRepeat;
    private ImageButton btnBack;
    private ImageButton btnMoreOptions; // Assuming you have this ID in your layout

    // Music Service Connection
    private MusicService musicService;
    private boolean isBound = false;

    // Handler for SeekBar updates
    private Handler handler = new Handler();
    private Runnable updateSeekBarRunnable;

    // Animation for the rotating icon
    private RotateAnimation rotateAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        initViews();
        setupListeners();
        setupAnimation();

        // Bind to MusicService when activity starts
        Intent serviceIntent = new Intent(this, MusicService.class);
        // Using startService() ensures the service keeps running in the background,
        // even if no components are bound to it. This is crucial for music playback.
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Setup Runnable for SeekBar updates
        updateSeekBarRunnable = new Runnable() {
            @Override
            public void run() {
                if (musicService != null && musicService.isPlaying()) {
                    int currentPosition = musicService.getCurrentPosition();
                    int duration = musicService.getDuration();
                    seekbarProgress.setProgress(currentPosition);
                    tvCurrentTime.setText(formatTime(currentPosition));
                    tvTotalTime.setText(formatTime(duration));
                    Log.d(TAG, "SeekBar updated: " + formatTime(currentPosition) + "/" + formatTime(duration));
                } else if (musicService == null) {
                    Log.w(TAG, "updateSeekBarRunnable: MusicService is null.");
                } else {
                    Log.d(TAG, "updateSeekBarRunnable: MusicService not playing.");
                }
                handler.postDelayed(this, 1000); // Update every second
            }
        };
    }

    private void initViews() {
        ivMusicIconRotating = findViewById(R.id.iv_music_icon_rotating);
        tvSongTitle = findViewById(R.id.now_playing_song_title);
        tvSongArtist = findViewById(R.id.now_playing_song_artist);
        seekbarProgress = findViewById(R.id.now_playing_seekbar_progress);
        tvCurrentTime = findViewById(R.id.now_playing_current_time);
        tvTotalTime = findViewById(R.id.now_playing_total_time);
        btnShuffle = findViewById(R.id.btn_shuffle_now_playing);
        btnPrevious = findViewById(R.id.btn_previous_now_playing);
        btnPlayPause = findViewById(R.id.btn_play_pause_now_playing);
        btnNext = findViewById(R.id.btn_next_now_playing);
        btnRepeat = findViewById(R.id.btn_repeat_now_playing);
        btnBack = findViewById(R.id.btn_back);
        btnMoreOptions = findViewById(R.id.btn_more_options); // Initialize if you have this ID
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            Log.d(TAG, "Back button clicked.");
            onBackPressed(); // Go back to MainActivity
        });

        btnPlayPause.setOnClickListener(v -> {
            if (musicService != null) {
                Log.d(TAG, "Play/Pause button clicked. Current state: " + (musicService.isPlaying() ? "PLAYING" : "PAUSED"));
                if (musicService.isPlaying()) {
                    musicService.pause();
                } else {
                    musicService.play();
                }
            } else {
                Toast.makeText(this, "Music service not ready yet.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Play/Pause button clicked but MusicService is null.");
            }
        });

        btnNext.setOnClickListener(v -> {
            if (musicService != null) {
                Log.d(TAG, "Next button clicked.");
                musicService.playNextSong();
            } else {
                Toast.makeText(this, "Music service not ready yet.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Next button clicked but MusicService is null.");
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (musicService != null) {
                Log.d(TAG, "Previous button clicked.");
                musicService.playPreviousSong();
            } else {
                Toast.makeText(this, "Music service not ready yet.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Previous button clicked but MusicService is null.");
            }
        });

        btnShuffle.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleShuffle();
                updateShuffleButtonState(musicService.isShuffleEnabled());
                Toast.makeText(this, "Shuffle: " + (musicService.isShuffleEnabled() ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Shuffle button clicked. Shuffle enabled: " + musicService.isShuffleEnabled());
            } else {
                Toast.makeText(this, "Music service not ready yet.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Shuffle button clicked but MusicService is null.");
            }
        });

        btnRepeat.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleRepeat();
                updateRepeatButtonState(musicService.getRepeatMode());
                String repeatModeText = "";
                switch (musicService.getRepeatMode()) {
                    case MusicService.REPEAT_OFF: repeatModeText = "OFF"; break;
                    case MusicService.REPEAT_ALL: repeatModeText = "ALL"; break;
                    case MusicService.REPEAT_ONE: repeatModeText = "ONE"; break;
                }
                Toast.makeText(this, "Repeat: " + repeatModeText, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Repeat button clicked. Repeat mode: " + musicService.getRepeatMode());
            } else {
                Toast.makeText(this, "Music service not ready yet.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Repeat button clicked but MusicService is null.");
            }
        });

        seekbarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && musicService != null) {
                    musicService.seekTo(progress);
                    Log.d(TAG, "SeekBar progress changed by user to: " + formatTime(progress));
                }
                tvCurrentTime.setText(formatTime(progress)); // Update current time text immediately
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Log.d(TAG, "SeekBar tracking started.");
                handler.removeCallbacks(updateSeekBarRunnable); // Stop updates while user is seeking
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Log.d(TAG, "SeekBar tracking stopped.");
                if (musicService != null && musicService.isPlaying()) { // Only resume if playing
                    handler.post(updateSeekBarRunnable); // Resume updates
                }
            }
        });

        // Optional: More options button listener if you implement a menu
        if (btnMoreOptions != null) { // Check if the button exists in layout
            btnMoreOptions.setOnClickListener(v -> {
                Toast.makeText(this, "More options coming soon!", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "More options button clicked.");
                // Implement a popup menu or new activity for options like sleep timer, share, etc.
            });
        }
    }

    private void setupAnimation() {
        rotateAnimation = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        rotateAnimation.setDuration(15000); // 15 seconds for one full rotation
        rotateAnimation.setRepeatCount(Animation.INFINITE);
        rotateAnimation.setInterpolator(new LinearInterpolator());
        rotateAnimation.setFillAfter(true); // Keep the final rotation state
    }

    private void startRotationAnimation() {
        if (ivMusicIconRotating != null && rotateAnimation != null) {
            ivMusicIconRotating.startAnimation(rotateAnimation);
            Log.d(TAG, "Rotation animation started.");
        }
    }

    private void stopRotationAnimation() {
        if (ivMusicIconRotating != null) {
            ivMusicIconRotating.clearAnimation();
            Log.d(TAG, "Rotation animation stopped.");
        }
    }


    // --- Music Service Connection ---
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            isBound = true;
            musicService.setOnSongChangedListener(NowPlayingActivity.this);
            Log.d(TAG, "MusicService bound successfully to NowPlayingActivity.");

            // Update UI with current song info from service immediately after binding
            updateUiFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            musicService = null;
            Log.d(TAG, "MusicService disconnected from NowPlayingActivity.");
            handler.removeCallbacks(updateSeekBarRunnable); // Stop updates
            // Potentially show a message to the user that service is unavailable
            Toast.makeText(NowPlayingActivity.this, "Music service disconnected.", Toast.LENGTH_LONG).show();
        }
    };

    /** Updates UI elements with data from the MusicService. */
    private void updateUiFromService() {
        if (musicService == null) {
            Log.w(TAG, "updateUiFromService called but musicService is null.");
            // Reset UI to default "no song playing" state
            tvSongTitle.setText("No song playing");
            tvSongArtist.setText("");
            seekbarProgress.setProgress(0);
            seekbarProgress.setMax(0);
            tvCurrentTime.setText("0:00");
            tvTotalTime.setText("0:00");
            onPlaybackStateChanged(false); // Ensure play button is shown and animation stopped
            updateShuffleButtonState(false);
            updateRepeatButtonState(MusicService.REPEAT_OFF);
            handler.removeCallbacks(updateSeekBarRunnable); // Stop any lingering updates
            return;
        }

        Song currentSong = musicService.getCurrentSong();
        if (currentSong != null) {
            tvSongTitle.setText(currentSong.getTitle());
            tvSongArtist.setText(currentSong.getArtist());
            int duration = musicService.getDuration();
            seekbarProgress.setMax(duration); // Set max duration
            tvTotalTime.setText(formatTime(duration));
            Log.d(TAG, "updateUiFromService: Song info updated. Title: " + currentSong.getTitle() + ", Duration: " + formatTime(duration));
        } else {
            tvSongTitle.setText("No song playing");
            tvSongArtist.setText("");
            seekbarProgress.setProgress(0);
            seekbarProgress.setMax(0);
            tvCurrentTime.setText("0:00");
            tvTotalTime.setText("0:00");
            Log.d(TAG, "updateUiFromService: No current song found.");
        }

        onPlaybackStateChanged(musicService.isPlaying()); // Update play/pause button and animation
        onProgressUpdate(musicService.getCurrentPosition(), musicService.getDuration()); // Initial seekbar pos
        updateShuffleButtonState(musicService.isShuffleEnabled());
        updateRepeatButtonState(musicService.getRepeatMode());

        handler.post(updateSeekBarRunnable); // Start periodic updates
    }


    // --- MusicService.OnSongChangedListener Implementation ---

    @Override
    public void onSongChanged(Song song, boolean isPlaying) {
        Log.d(TAG, "onSongChanged callback received in NowPlayingActivity. Song: " + (song != null ? song.getTitle() : "null"));
        if (song != null) {
            tvSongTitle.setText(song.getTitle());
            tvSongArtist.setText(song.getArtist());
            // It's important to get the duration directly from the service after a song change
            if (musicService != null) {
                int duration = musicService.getDuration();
                seekbarProgress.setMax(duration);
                tvTotalTime.setText(formatTime(duration));
            } else {
                seekbarProgress.setMax(0);
                tvTotalTime.setText("0:00");
            }
            seekbarProgress.setProgress(0); // Reset seekbar for new song
            tvCurrentTime.setText("0:00"); // Reset current time for new song
        } else {
            tvSongTitle.setText("No song playing");
            tvSongArtist.setText("");
            seekbarProgress.setMax(0);
            seekbarProgress.setProgress(0);
            tvTotalTime.setText("0:00");
            tvCurrentTime.setText("0:00");
        }
        onPlaybackStateChanged(isPlaying); // Update play/pause button and animation
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "onPlaybackStateChanged callback received in NowPlayingActivity: " + isPlaying);
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause_white_24dp);
            startRotationAnimation();
            handler.post(updateSeekBarRunnable); // Ensure seekbar updates
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_white_24dp);
            stopRotationAnimation();
            handler.removeCallbacks(updateSeekBarRunnable); // Stop seekbar updates
        }
    }

    @Override
    public void onProgressUpdate(int currentPosition, int duration) {
        Log.d(TAG, "onProgressUpdate callback received: " + formatTime(currentPosition) + "/" + formatTime(duration));
        if (seekbarProgress.getMax() == 0 && duration > 0) { // Set max duration if not already set
            seekbarProgress.setMax(duration);
            tvTotalTime.setText(formatTime(duration));
        }
        seekbarProgress.setProgress(currentPosition);
        tvCurrentTime.setText(formatTime(currentPosition));
    }


    // --- Helper Methods ---

    private String formatTime(long milliseconds) {
        return String.format(Locale.getDefault(), "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(milliseconds),
                TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds))
        );
    }

    private void updateShuffleButtonState(boolean isShuffleEnabled) {
        if (isShuffleEnabled) {
            btnShuffle.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, R.color.green_accent), PorterDuff.Mode.SRC_IN));
        } else {
            btnShuffle.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, R.color.grey_text), PorterDuff.Mode.SRC_IN));
        }
        Log.d(TAG, "Shuffle button state updated: " + isShuffleEnabled);
    }

    private void updateRepeatButtonState(int repeatMode) {
        switch (repeatMode) {
            case MusicService.REPEAT_OFF:
                btnRepeat.setImageResource(R.drawable.ic_repeat_white_24dp);
                btnRepeat.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, R.color.grey_text), PorterDuff.Mode.SRC_IN));
                break;
            case MusicService.REPEAT_ALL:
                btnRepeat.setImageResource(R.drawable.ic_repeat_white_24dp);
                btnRepeat.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, R.color.green_accent), PorterDuff.Mode.SRC_IN));
                break;
            case MusicService.REPEAT_ONE:
                btnRepeat.setImageResource(R.drawable.ic_repeat_one_white_24dp); // You'll need this drawable
                btnRepeat.setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, R.color.green_accent), PorterDuff.Mode.SRC_IN));
                break;
        }
        Log.d(TAG, "Repeat button state updated to mode: " + repeatMode);
    }

    // --- Activity Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "NowPlayingActivity onResume called. isBound: " + isBound);
        if (isBound && musicService != null) {
            musicService.setOnSongChangedListener(this); // Re-register listener
            updateUiFromService(); // Refresh UI state from service
            Log.d(TAG, "NowPlayingActivity onResume: UI updated and listener set.");
        } else {
            // Re-bind if service was unbound or destroyed
            Intent serviceIntent = new Intent(this, MusicService.class);
            startService(serviceIntent); // Ensure service is started
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "NowPlayingActivity onResume: Attempting to re-bind service.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "NowPlayingActivity onPause called.");
        // Unregister listener to prevent memory leaks if activity is in background
        if (isBound && musicService != null) {
            musicService.setOnSongChangedListener(null);
            Log.d(TAG, "NowPlayingActivity onPause: Listener cleared.");
        }
        handler.removeCallbacks(updateSeekBarRunnable); // Stop updates
        stopRotationAnimation(); // Ensure animation stops when activity is paused
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "NowPlayingActivity onDestroy: Unbinding service and stopping service.");
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        stopService(new Intent(this, MusicService.class));

        handler.removeCallbacks(updateSeekBarRunnable); // Ensure runnable is stopped
        stopRotationAnimation(); // Ensure animation stops
    }
}