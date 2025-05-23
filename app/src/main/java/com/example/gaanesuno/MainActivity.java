package com.example.gaanesuno;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
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
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements
        MusicService.OnSongChangedListener,
        SongAdapter.OnSongOptionsClickListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private RecyclerView recyclerViewSongs;
    private SongAdapter songAdapter;
    private List<Song> songList;

    private MusicService musicService;
    private boolean isBound = false;

    private TextView tvSongTitle, tvSongArtist, tvCurrentTime, tvTotalTime;
    private AppCompatImageButton btnPlayPause, btnNext, btnPrevious, btnShuffle, btnRepeat, btnTimer, btnSettingsMenu;
    private SeekBar seekBarProgress;

    private Handler handler = new Handler();
    private Runnable updateSeekBarRunnable;

    private final int COLOR_ACTIVE = 0xFF1DB954; // Spotify green-like
    private final int COLOR_INACTIVE = 0xFFB3B3B3; // Light grey

    // SharedPreferences Keys for saving/restoring state
    public static final String PREFS_NAME = "MusicAppPrefs"; // Made public for MusicService
    public static final String KEY_LAST_SONG_ID = "lastSongId"; // Made public for MusicService
    public static final String KEY_LAST_SONG_POSITION = "lastSongPosition"; // Made public for MusicService
    public static final String KEY_WAS_PLAYING = "wasPlaying"; // Made public for MusicService
    public static final String KEY_SHUFFLE_ENABLED = "shuffleEnabled";
    public static final String KEY_REPEAT_MODE = "repeatMode";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        tvSongTitle = findViewById(R.id.tv_song_title);
        tvSongArtist = findViewById(R.id.tv_song_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnNext = findViewById(R.id.btn_next);
        btnPrevious = findViewById(R.id.btn_previous);
        seekBarProgress = findViewById(R.id.seekbar_progress);

        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnTimer = findViewById(R.id.btn_timer);
        btnSettingsMenu = findViewById(R.id.btn_settings_menu);

        recyclerViewSongs = findViewById(R.id.recyclerView_songs);
        recyclerViewSongs.setLayoutManager(new LinearLayoutManager(this));
        songList = new ArrayList<>(); // Initialize songList here
        songAdapter = new SongAdapter(songList);
        recyclerViewSongs.setAdapter(songAdapter);

        songAdapter.setOnItemClickListener(position -> {
            if (musicService != null) {
                musicService.playSong(position);
            }
        });

        songAdapter.setOnSongOptionsClickListener(this);

        // Start the service explicitly. This ensures it continues running even if MainActivity is destroyed.
        Intent musicServiceIntent = new Intent(this, MusicService.class);
        startService(musicServiceIntent);

        // Bind to MusicService. This allows MainActivity to interact with the service.
        bindService(musicServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);


        btnPlayPause.setOnClickListener(v -> {
            if (musicService == null) return;
            if (musicService.isPlaying()) {
                musicService.pause();
            } else {
                if (musicService.getCurrentSong() != null) {
                    musicService.play(); // Play current song if already loaded
                } else if (!songList.isEmpty()) {
                    musicService.playSong(0); // If no song loaded, play the first one
                } else {
                    Toast.makeText(this, "No songs to play. Please load music.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnNext.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.playNextSong();
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.playPreviousSong();
            }
        });

        btnShuffle.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleShuffle();
                updateShuffleButtonState();
                Song currentSong = musicService.getCurrentSong();
                if (currentSong != null) {
                    // Update the selected item in the RecyclerView to the new index if shuffle changes
                    // Note: If shuffle is enabled, the index in the UI list is not necessarily the same as in activeSongList
                    // We find the song in the UI list to highlight it.
                    int uiListIndex = songList.indexOf(currentSong);
                    if(uiListIndex != -1) {
                        songAdapter.setSelectedPosition(uiListIndex);
                        ((LinearLayoutManager) recyclerViewSongs.getLayoutManager()).scrollToPositionWithOffset(uiListIndex, 0);
                    }
                } else {
                    songAdapter.setSelectedPosition(-1);
                }
            }
        });

        btnRepeat.setOnClickListener(v -> {
            if (musicService != null) {
                musicService.toggleRepeat(); // This will now trigger the toast from the service
                updateRepeatButtonState();
            }
        });

        btnTimer.setOnClickListener(v -> {
            Toast.makeText(this, "Sleep Timer: Feature coming soon! (Will open a timer dialog)", Toast.LENGTH_SHORT).show();
        });

        btnSettingsMenu.setOnClickListener(this::showSettingsMenu);

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && musicService != null) {
                    musicService.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Pause seek bar updates while user is dragging
                handler.removeCallbacks(updateSeekBarRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Resume seek bar updates if music is playing
                if (musicService != null && musicService.isPlaying()) {
                    startSeekBarUpdates();
                } else {
                    // If paused, ensure UI reflects current position after seek
                    tvCurrentTime.setText(formatDuration(seekBar.getProgress()));
                }
            }
        });

        // Request permissions and load audio files
        if (checkPermissions()) {
            loadAudioFiles();
        } else {
            requestPermissions();
        }

        // Apply window insets for proper UI layout (EdgeToEdge)
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
            Log.d(TAG, "MusicService connected.");

            // Set the song list in the service. This is crucial for the service to know
            // what songs are available before any playback commands.
            if (!songList.isEmpty()) {
                musicService.setSongList(songList);
            }

            // Restore playback state from SharedPreferences
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastSongId = prefs.getLong(KEY_LAST_SONG_ID, -1L);
            int lastSongPositionMs = prefs.getInt(KEY_LAST_SONG_POSITION, 0);
            boolean wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false); // Retrieve the 'was playing' state

            Song lastSong = null;
            int lastSongIndex = RecyclerView.NO_POSITION;

            // Find the last played song in the loaded songList
            if (lastSongId != -1L && !songList.isEmpty()) {
                for (int i = 0; i < songList.size(); i++) {
                    if (songList.get(i).getId() == lastSongId) {
                        lastSong = songList.get(i);
                        lastSongIndex = i;
                        break;
                    }
                }
            }

            // Apply shuffle and repeat modes to the service without showing toast
            musicService.setShuffle(prefs.getBoolean(KEY_SHUFFLE_ENABLED, false));
            musicService.setRepeatMode(prefs.getInt(KEY_REPEAT_MODE, MusicService.REPEAT_OFF), false);


            Song currentServiceSong = musicService.getCurrentSong();
            boolean serviceIsPlaying = musicService.isPlaying(); // Current actual state of the service
            boolean serviceIsPrepared = musicService.isMediaPlayerPrepared();
            int serviceCurrentPosition = musicService.getCurrentPosition();
            int serviceDuration = musicService.getDuration();

            // >>>>>>> MODIFICATION FOR SMART RE-ENTRY (MINIMIZE vs. CLOSE) <<<<<<<

            if (currentServiceSong != null && serviceIsPlaying && serviceIsPrepared &&
                    lastSong != null && currentServiceSong.getId() == lastSong.getId()) {
                // Scenario 1: Service is already playing the *same* song, is prepared, and is playing.
                // This indicates the app was minimized and the service continued playing.
                // We want to reflect the current playing state of the service.
                Log.d(TAG, "onServiceConnected: Service was playing same song. Updating UI to match service state.");
                onSongChanged(currentServiceSong, serviceIsPlaying); // Update song info and playing state
                onPlaybackStateChanged(serviceIsPlaying); // Update play/pause button
                onProgressUpdate(serviceCurrentPosition, serviceDuration); // Update seekbar and time

                seekBarProgress.setMax(serviceDuration);
                seekBarProgress.setProgress(serviceCurrentPosition);

                startSeekBarUpdates(); // Ensure seekbar updates are running

            } else if (lastSong != null) {
                // Scenario 2: No song playing, or a different song, or service not prepared.
                // This is where 'wasPlaying' from SharedPreferences becomes crucial.
                Log.d(TAG, "onServiceConnected: Service needs to prepare/load last known song. Using saved 'wasPlaying' state (" + wasPlaying + ").");

                // Pass the 'wasPlaying' preference to decide if it should play automatically
                // The service will handle setting up the MediaPlayer based on this.
                musicService.prepareSongForRestore(lastSong, lastSongIndex, wasPlaying, lastSongPositionMs);

                // Immediately update UI with the song details. Playback state will be
                // dictated by 'wasPlaying' and then updated via onPlaybackStateChanged
                // from the service if it starts playing.
                onSongChanged(lastSong, wasPlaying); // Initial UI state should reflect 'wasPlaying'
                onPlaybackStateChanged(wasPlaying); // Set play/pause button based on 'wasPlaying'
                seekBarProgress.setMax((int)lastSong.getDuration()); // Set max for seekbar
                seekBarProgress.setProgress(lastSongPositionMs);

            } else {
                // Scenario 3: No last song found or list is empty, reset UI
                Log.d(TAG, "onServiceConnected: No last song found or list is empty. Resetting UI.");
                onSongChanged(null, false);
                onPlaybackStateChanged(false); // Ensure play button is shown
            }
            // >>>>>>> END MODIFICATION <<<<<<<

            // Update UI buttons based on restored service state
            updateShuffleButtonState();
            updateRepeatButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            musicService = null;
            Log.d(TAG, "MusicService disconnected.");
        }
    };

    //--- Permission Handling ---
    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
    //--- End Permission Handling ---

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
            int dataColumnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA); // Path string

            do {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                String artist = cursor.getString(artistColumn);
                long duration = cursor.getLong(durationColumn);
                String data = cursor.getString(dataColumnIndex);

                Uri contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                // Ensure data is not null and represents a valid file path if needed for older APIs,
                // or just rely on contentUri for playback.
                if (data != null && new File(data).exists()) {
                    songList.add(new Song(id, title, artist, contentUri, duration, data));
                } else if (data == null) {
                    // Fallback if data is null, but contentUri might still work for some files
                    Log.w(TAG, "Song " + title + " has null data path, attempting with contentUri only.");
                    songList.add(new Song(id, title, artist, contentUri, duration, null)); // path as null
                }
            } while (cursor.moveToNext());
            cursor.close();
        }
        songAdapter.updateSongList(songList);
        if (songList.isEmpty()) {
            Toast.makeText(this, "No music found on your device. Please add music files to your device's storage.", Toast.LENGTH_LONG).show();
        } else {
            // Once songs are loaded, pass them to the service if it's already bound
            if (isBound) {
                musicService.setSongList(songList);
            }
        }
    }

    //--- MusicService.OnSongChangedListener Callbacks ---
    @Override
    public void onSongChanged(Song song, boolean isPlaying) {
        if (song != null) {
            tvSongTitle.setText(song.getTitle());
            tvSongArtist.setText(song.getArtist());
            seekBarProgress.setMax((int) song.getDuration());
            tvTotalTime.setText(formatDuration(song.getDuration()));
            // Highlight the currently playing song in the RecyclerView
            int currentSongIndex = songList.indexOf(song);
            if (currentSongIndex != -1) {
                songAdapter.setSelectedPosition(currentSongIndex);
                // Scroll to the current song, with an offset to keep it visible
                ((LinearLayoutManager) recyclerViewSongs.getLayoutManager()).scrollToPositionWithOffset(currentSongIndex, 0);
            }
        } else {
            // No song playing, reset UI
            tvSongTitle.setText("No song playing");
            tvSongArtist.setText("Artist");
            seekBarProgress.setProgress(0);
            seekBarProgress.setMax(0);
            tvCurrentTime.setText("0:00");
            tvTotalTime.setText("0:00");
            songAdapter.setSelectedPosition(-1);
        }
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        Log.d(TAG, "onPlaybackStateChanged: isPlaying=" + isPlaying);
        if (isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause_white_24dp);
            startSeekBarUpdates(); // Start updating seekbar only when playing
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_white_24dp);
            handler.removeCallbacks(updateSeekBarRunnable); // Stop updates when paused
        }
    }

    @Override
    public void onProgressUpdate(int currentPosition, int duration) {
        // This callback is less critical now as seek bar updates are managed by a Runnable.
        // It could be used for other real-time UI updates or if seekbar updates were pushed from service.
        // For current setup, the seekbar updates are pulled by the Runnable in MainActivity.
        // We still update the current time text to be absolutely sure.
        tvCurrentTime.setText(formatDuration(currentPosition));
    }
    //--- End MusicService.OnSongChangedListener Callbacks ---

    private String formatDuration(long milliseconds) {
        return DateUtils.formatElapsedTime(milliseconds / 1000);
    }

    //--- SeekBar Updates ---
    private void startSeekBarUpdates() {
        // Ensure only one runnable instance is scheduled
        if (updateSeekBarRunnable == null) {
            updateSeekBarRunnable = new Runnable() {
                @Override
                public void run() {
                    if (musicService != null && musicService.isPlaying()) {
                        int currentPosition = musicService.getCurrentPosition();
                        seekBarProgress.setProgress(currentPosition);
                        tvCurrentTime.setText(formatDuration(currentPosition));
                        handler.postDelayed(this, 1000); // Update every second
                    } else {
                        handler.removeCallbacks(this); // Stop updates if not playing
                    }
                }
            };
        }
        handler.post(updateSeekBarRunnable); // Start the updates
    }
    //--- End SeekBar Updates ---

    //--- UI Update Methods for Control Buttons ---

    private void updateShuffleButtonState() {
        if (musicService != null) {
            if (musicService.isShuffleEnabled()) {
                btnShuffle.setColorFilter(COLOR_ACTIVE, PorterDuff.Mode.SRC_IN);
            } else {
                btnShuffle.setColorFilter(COLOR_INACTIVE, PorterDuff.Mode.SRC_IN);
            }
        }
    }

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
                    btnRepeat.setImageResource(R.drawable.ic_repeat_one_white_24dp);
                    break;
            }
        }
    }

    private void showSettingsMenu(android.view.View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenuInflater().inflate(R.menu.settings_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_toggle_theme) {
                Toast.makeText(MainActivity.this, "Theme Toggle Clicked! (Implement light/dark mode logic)", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_equalizer) {
                Toast.makeText(MainActivity.this, "Equalizer Clicked! (Navigate to Equalizer Activity/Fragment)", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_about) {
                Toast.makeText(MainActivity.this, "About Clicked! (Show app info)", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        popup.show();
    }

    //--- End UI Update Methods for Control Buttons ---

    // SongAdapter.OnSongOptionsClickListener Implementation (for deleting songs)
    @Override
    public void onSongOptionsClick(View view, int position) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.getMenuInflater().inflate(R.menu.song_options_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_delete_song) {
                showDeleteConfirmationDialog(position);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Displays a confirmation dialog before deleting a song.
     * @param position The position of the song in the songList.
     */
    private void showDeleteConfirmationDialog(int position) {
        if (position < 0 || position >= songList.size()) {
            Log.e(TAG, "Invalid position for delete dialog: " + position);
            return;
        }
        Song songToDelete = songList.get(position);

        new AlertDialog.Builder(this)
                .setTitle("Delete Song")
                .setMessage("Are you sure you want to delete '" + songToDelete.getTitle() + "'? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteSong(position);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Handles the actual deletion of the song file from storage and updates the UI.
     * @param position The position of the song to delete in the songList.
     */
    private void deleteSong(int position) {
        if (musicService == null) {
            Toast.makeText(this, "Music service not ready for deletion.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (position < 0 || position >= songList.size()) {
            Log.e(TAG, "Invalid position for deletion: " + position);
            Toast.makeText(this, "Error: Song not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        Song songToDelete = songList.get(position);
        Song currentPlayingSong = musicService.getCurrentSong();
        boolean wasPlayingDeletedSong = (currentPlayingSong != null && currentPlayingSong.getId() == songToDelete.getId());

        boolean deletedSuccessfully = false;
        Uri contentUri = songToDelete.getData();

        try {
            ContentResolver contentResolver = getContentResolver();
            int rowsAffected = contentResolver.delete(contentUri, null, null);

            if (rowsAffected > 0) {
                deletedSuccessfully = true;
                Log.d(TAG, "Deleted song via MediaStore: " + songToDelete.getTitle());
            } else {
                Log.w(TAG, "Failed to delete via MediaStore, rows affected: " + rowsAffected + ". Attempting direct file delete (legacy fallback).");
                // Fallback for older Android versions or specific device quirks where MediaStore.delete doesn't work directly
                File file = new File(songToDelete.getPath());
                if (file.exists() && file.delete()) {
                    deletedSuccessfully = true;
                    Log.d(TAG, "Deleted song via direct file delete: " + songToDelete.getTitle());
                } else {
                    Log.e(TAG, "Failed to delete song file: " + songToDelete.getPath());
                    Toast.makeText(this, "Failed to delete file from storage: " + songToDelete.getTitle(), Toast.LENGTH_LONG).show();
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException during song deletion: " + e.getMessage());
            Toast.makeText(this, "Permission denied to delete song. On Android 10+, this might require specific user approval or 'All files access'.", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11 (R) and above, direct file access is restricted.
                // You should use MediaStore.createDeleteRequest(uris) to ask for user consent for deletion.
                Toast.makeText(this, "On Android 11+, deletion requires explicit user confirmation via MediaStore.createDeleteRequest().", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "An unexpected error occurred during deletion: " + e.getMessage());
            Toast.makeText(this, "An unexpected error occurred during deletion: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        if (deletedSuccessfully) {
            Toast.makeText(this, "'" + songToDelete.getTitle() + "' deleted successfully.", Toast.LENGTH_SHORT).show();
            if (wasPlayingDeletedSong) {
                musicService.pause();
                tvSongTitle.setText("No song playing");
                tvSongArtist.setText("Artist");
                seekBarProgress.setProgress(0);
                tvCurrentTime.setText("0:00");
                songAdapter.setSelectedPosition(-1);
                // The service itself needs to clear its current song and index
                // You might need to add methods in MusicService for this, e.g., musicService.clearCurrentSong();
                // For now, if current song is null, service will handle it.
            }
            songList.remove(position);
            songAdapter.updateSongList(songList);

            // Inform the service about the updated song list
            if (musicService != null) {
                musicService.setSongList(songList); // Re-set the song list in the service
                if (songList.isEmpty() && musicService.isPlaying()) {
                    // If the list became empty and something was still playing, stop it
                    musicService.pause();
                    musicService.seekTo(0);
                }
            }
        }
    }
    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "MainActivity onStart.");
        // Re-bind to the service if it was unbound (e.g., app came from background)
        if (!isBound) {
            Intent musicServiceIntent = new Intent(this, MusicService.class);
            bindService(musicServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }
    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "MainActivity onStop.");
        // Save current playback state to SharedPreferences
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (musicService != null && musicService.getCurrentSong() != null) {
            editor.putLong(KEY_LAST_SONG_ID, musicService.getCurrentSong().getId());
            editor.putInt(KEY_LAST_SONG_POSITION, musicService.getCurrentPosition());
            // Save the actual playing state when activity stops (minimize scenario)
            editor.putBoolean(KEY_WAS_PLAYING, musicService.isPlaying()); // <-- Saves actual state for minimization
            editor.putBoolean(KEY_SHUFFLE_ENABLED, musicService.isShuffleEnabled());
            editor.putInt(KEY_REPEAT_MODE, musicService.getRepeatMode());
            Log.d(TAG, "MainActivity onStop: Saved wasPlaying: " + musicService.isPlaying());
        } else {
            // If no song is playing or service is null, clear saved state
            editor.remove(KEY_LAST_SONG_ID);
            editor.remove(KEY_LAST_SONG_POSITION);
            editor.remove(KEY_WAS_PLAYING); // Ensure this is also removed/cleared if no song
            Log.d(TAG, "MainActivity onStop: No current song, cleared saved state.");

            editor.putBoolean(KEY_SHUFFLE_ENABLED, musicService != null && musicService.isShuffleEnabled());
            editor.putInt(KEY_REPEAT_MODE, musicService != null ? musicService.getRepeatMode() : MusicService.REPEAT_OFF);
        }
        editor.apply();

        // Unbind the service when activity stops to prevent memory leaks.
        // The service itself might continue running if it's a foreground service and playing music.
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity onDestroy.");
        // If music is NOT playing when activity is destroyed, stop the service completely.
        // This prevents the service from lingering unnecessarily.
        if (musicService != null && !musicService.isPlaying()) {
            Log.d(TAG, "MainActivity onDestroy: Music not playing, stopping service fully.");
            stopService(new Intent(this, MusicService.class));
            // When app is closed and music was paused, ensure next launch is also paused.
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putBoolean(KEY_WAS_PLAYING, false); // If service was not playing, explicitly save false
            editor.apply();
        } else if (musicService != null && musicService.isPlaying()) {
            // This case means the app UI is being destroyed, but the service is still playing
            // (e.g., user swiped away from recents but foreground service is still active).
            // For the *next* launch, we want it to be paused, regardless of onStop's saved state.
            Log.d(TAG, "MainActivity onDestroy: Service still playing. Forcing WAS_PLAYING to false for next launch.");
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putBoolean(KEY_WAS_PLAYING, false); // <-- THIS IS KEY for "closed = paused" behavior
            editor.apply();
        }
        // Always remove callbacks to prevent memory leaks from the handler
        handler.removeCallbacks(updateSeekBarRunnable);
    }
}