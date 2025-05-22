package com.example.gaanesuno;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.MenuItem; // For PopupMenu
import android.widget.ImageButton;
import android.widget.PopupMenu; // For PopupMenu
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton; // Use this for tinting
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MusicService.OnSongChangedListener {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private RecyclerView recyclerViewSongs;
    private SongAdapter songAdapter;
    private List<Song> songList;

    private MusicService musicService;
    private boolean isBound = false;

    private TextView tvSongTitle, tvSongArtist, tvCurrentTime, tvTotalTime;
    private AppCompatImageButton btnPlayPause, btnNext, btnPrevious, btnShuffle, btnRepeat, btnTimer, btnSettingsMenu;
    private SeekBar seekBarProgress;

    // Handler for updating seekbar and time
    private Handler handler = new Handler();
    private Runnable updateSeekBarRunnable;

    // Colors for active/inactive state of shuffle/repeat buttons
    private final int COLOR_ACTIVE = 0xFF1DB954; // Spotify green-like
    private final int COLOR_INACTIVE = 0xFFB3B3B3; // Light grey

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        tvSongTitle = findViewById(R.id.tv_song_title);
        tvSongArtist = findViewById(R.id.tv_song_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnNext = findViewById(R.id.btn_next);
        btnPrevious = findViewById(R.id.btn_previous);
        seekBarProgress = findViewById(R.id.seekbar_progress);

        // New UI components for shuffle, repeat, timer, and settings
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnTimer = findViewById(R.id.btn_timer);
        btnSettingsMenu = findViewById(R.id.btn_settings_menu); // This is in the header_layout

        recyclerViewSongs = findViewById(R.id.recyclerView_songs);
        recyclerViewSongs.setLayoutManager(new LinearLayoutManager(this));
        songList = new ArrayList<>();
        songAdapter = new SongAdapter(songList);
        recyclerViewSongs.setAdapter(songAdapter);

        // Bind to MusicService
        Intent musicServiceIntent = new Intent(this, MusicService.class);
        bindService(musicServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        startService(musicServiceIntent); // Start the service in the background

        // Set listeners for playback controls
        songAdapter.setOnItemClickListener(position -> {
            if (musicService != null) {
                musicService.playSong(position);
                songAdapter.setSelectedPosition(position); // Highlight clicked song
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (musicService == null) return;
            if (musicService.isPlaying()) {
                musicService.pause();
            } else {
                if (musicService.getCurrentSong() != null) {
                    musicService.play();
                } else if (!songList.isEmpty()) { // If no song selected, play the first one
                    musicService.playSong(0);
                    songAdapter.setSelectedPosition(0);
                }
            }
        });

        btnNext.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.playNextSong();
                Song nextSong = musicService.getCurrentSong();
                if (nextSong != null) {
                    int nextIndex = songList.indexOf(nextSong);
                    songAdapter.setSelectedPosition(nextIndex);
                    recyclerViewSongs.scrollToPosition(nextIndex); // Scroll to the next song
                }
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.playPreviousSong();
                Song prevSong = musicService.getCurrentSong();
                if (prevSong != null) {
                    int prevIndex = songList.indexOf(prevSong);
                    songAdapter.setSelectedPosition(prevIndex);
                    recyclerViewSongs.scrollToPosition(prevIndex); // Scroll to the previous song
                }
            }
        });

        // New listeners for Shuffle, Repeat, Timer, and Settings
        btnShuffle.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleShuffle();
                updateShuffleButtonState(); // Update UI immediately
            }
        });

        btnRepeat.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleRepeat(); // MusicService will cycle through repeat modes
                updateRepeatButtonState(); // Update UI immediately
            }
        });

        btnTimer.setOnClickListener(v -> {
            // In a full app, you'd show a dialog here for timer options (e.g., 15min, 30min, 1hr)
            Toast.makeText(this, "Sleep Timer: Feature coming soon! (Will open a timer dialog)", Toast.LENGTH_SHORT).show();
        });

        btnSettingsMenu.setOnClickListener(v -> {
            showSettingsMenu(v);
        });

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && musicService != null) {
                    musicService.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Stop updating seekbar from runnable while user is dragging
                handler.removeCallbacks(updateSeekBarRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Restart updating seekbar after user stops dragging
                if (musicService != null && musicService.isPlaying()) {
                    startSeekBarUpdates();
                }
            }
        });

        // Request permissions
        if (checkPermissions()) {
            loadAudioFiles();
        } else {
            requestPermissions();
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            isBound = true;
            musicService.setOnSongChangedListener(MainActivity.this);
            Log.d("MainActivity", "MusicService connected.");

            // If songs are already loaded, set them in the service
            if (!songList.isEmpty()) {
                musicService.setSongList(songList);
            }

            // Update UI with current song info and control states if service is already active
            Song currentSong = musicService.getCurrentSong();
            if (currentSong != null) {
                onSongChanged(currentSong, musicService.isPlaying());
            }
            // Update shuffle/repeat button states from the service
            updateShuffleButtonState();
            updateRepeatButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            musicService = null;
            Log.d("MainActivity", "MusicService disconnected.");
        }
    };

    //region Permission Handling
    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else { // Android 12 and below
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9+ needs FOREGROUND_SERVICE
            permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.FOREGROUND_SERVICE};
        } else {
            permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                loadAudioFiles();
            } else {
                Toast.makeText(this, "Permissions denied. Music player features will be limited.", Toast.LENGTH_LONG).show();
            }
        }
    }
    //endregion

    private void loadAudioFiles() {
        songList.clear();
        ContentResolver contentResolver = getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0";
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        Cursor cursor = contentResolver.query(uri, null, selection, null, sortOrder);

        if (cursor != null && cursor.moveToFirst()) {
            int idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

            do {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                String artist = cursor.getString(artistColumn);
                long duration = cursor.getLong(durationColumn);

                Uri contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                songList.add(new Song(id, title, artist, contentUri, duration));
            } while (cursor.moveToNext());
            cursor.close();
        }
        songAdapter.notifyDataSetChanged();
        if (songList.isEmpty()) {
            Toast.makeText(this, "No music found on your device. Please add music files to your device's storage.", Toast.LENGTH_LONG).show();
        } else {
            // Set the song list to the service once loaded
            if (isBound) {
                musicService.setSongList(songList);
            }
        }
    }

    //region MusicService.OnSongChangedListener Callbacks
    @Override
    public void onSongChanged(Song song, boolean isPlaying) {
        if (song != null) {
            tvSongTitle.setText(song.getTitle());
            tvSongArtist.setText(song.getArtist());
            seekBarProgress.setMax((int) song.getDuration());
            tvTotalTime.setText(formatDuration(song.getDuration()));
            // Highlight the current song in the list
            int currentSongIndex = songList.indexOf(song);
            songAdapter.setSelectedPosition(currentSongIndex);
            recyclerViewSongs.scrollToPosition(currentSongIndex);
        } else {
            tvSongTitle.setText("No song playing");
            tvSongArtist.setText("Artist");
            seekBarProgress.setProgress(0);
            seekBarProgress.setMax(0);
            tvCurrentTime.setText("0:00");
            tvTotalTime.setText("0:00");
            songAdapter.setSelectedPosition(-1); // No song selected
        }
        onPlaybackStateChanged(isPlaying); // Update play/pause button immediately
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause_white_24dp);
            startSeekBarUpdates();
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_white_24dp);
            handler.removeCallbacks(updateSeekBarRunnable); // Stop updates when paused
        }
    }

    @Override
    public void onProgressUpdate(int currentPosition, int duration) {
        // This callback from service might not be strictly needed if using the Handler Runnable for seekbar
        // But it's good for precise updates if needed, or if service has its own timer.
        // For now, we rely on the Runnable in MainActivity for seekbar updates.
    }
    //endregion

    // Helper to format milliseconds to "MM:SS"
    private String formatDuration(long milliseconds) {
        return DateUtils.formatElapsedTime(milliseconds / 1000);
    }

    //region SeekBar Updates
    private void startSeekBarUpdates() {
        if (updateSeekBarRunnable == null) {
            updateSeekBarRunnable = new Runnable() {
                @Override
                public void run() {
                    if (musicService != null && musicService.isPlaying()) {
                        int currentPosition = musicService.getCurrentPosition();
                        int duration = musicService.getDuration();
                        seekBarProgress.setProgress(currentPosition);
                        tvCurrentTime.setText(formatDuration(currentPosition));
                        handler.postDelayed(this, 1000); // Update every second
                    } else {
                        // If not playing, stop runnable
                        handler.removeCallbacks(this);
                    }
                }
            };
        }
        handler.post(updateSeekBarRunnable);
    }
    //endregion

    //region New Feature UI Update Methods

    /**
     * Updates the UI for the shuffle button based on MusicService's state.
     */
    private void updateShuffleButtonState() {
        if (musicService != null) {
            if (musicService.isShuffleEnabled()) {
                btnShuffle.setColorFilter(COLOR_ACTIVE, PorterDuff.Mode.SRC_IN);
            } else {
                btnShuffle.setColorFilter(COLOR_INACTIVE, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /**
     * Updates the UI for the repeat button based on MusicService's state.
     * Cycles through: REPEAT_OFF, REPEAT_ALL, REPEAT_ONE.
     */
    private void updateRepeatButtonState() {
        if (musicService != null) {
            int repeatMode = musicService.getRepeatMode();
            switch (repeatMode) {
                case MusicService.REPEAT_OFF:
                    btnRepeat.setColorFilter(COLOR_INACTIVE, PorterDuff.Mode.SRC_IN);
                    btnRepeat.setImageResource(R.drawable.ic_repeat_white_24dp);
                    break;
                case MusicService.REPEAT_ALL:
                    btnRepeat.setColorFilter(COLOR_ACTIVE, PorterDuff.Mode.SRC_IN);
                    btnRepeat.setImageResource(R.drawable.ic_repeat_white_24dp);
                    break;
                case MusicService.REPEAT_ONE:
                    btnRepeat.setColorFilter(COLOR_ACTIVE, PorterDuff.Mode.SRC_IN);
                    btnRepeat.setImageResource(R.drawable.ic_repeat_one_white_24dp); // Requires a different icon for "repeat one"
                    break;
            }
        }
    }

    /**
     * Shows the settings menu as a PopupMenu attached to the settings button.
     */
    private void showSettingsMenu(android.view.View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        // Inflate the menu from a menu XML file
        popup.getMenuInflater().inflate(R.menu.settings_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_toggle_theme) {
                    Toast.makeText(MainActivity.this, "Theme Toggle Clicked! (Implement light/dark mode logic)", Toast.LENGTH_SHORT).show();
                    // Example: Implement logic to switch app theme (requires styling setup)
                    // getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES); // Or MODE_NIGHT_NO, MODE_NIGHT_FOLLOW_SYSTEM
                    return true;
                } else if (id == R.id.action_equalizer) {
                    Toast.makeText(MainActivity.this, "Equalizer Clicked! (Navigate to Equalizer Activity/Fragment)", Toast.LENGTH_SHORT).show();
                    return true;
                } else if (id == R.id.action_about) {
                    Toast.makeText(MainActivity.this, "About Clicked! (Show app info)", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }
        });
        popup.show();
    }

    //endregion

    @Override
    protected void onStart() {
        super.onStart();
        // Re-bind service in case it was disconnected
        if (!isBound) {
            Intent musicServiceIntent = new Intent(this, MusicService.class);
            bindService(musicServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            // We usually start the service in onCreate, but if it was stopped by the OS, this re-starts it.
            // Consider starting it again here if you want it to always run in background after app launch.
            // For now, it's started in onCreate and bound/re-bound here.
            // startService(musicServiceIntent); // Uncomment if you want to ensure service starts on onStart too
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            // Unbind service. If the service is a foreground service, it will continue running.
            // If it's not a foreground service and no other components are bound, it might stop.
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (musicService != null && !musicService.isPlaying()) {
            stopService(new Intent(this, MusicService.class));
        }
        handler.removeCallbacks(updateSeekBarRunnable);
    }
}