package com.example.gaanesuno;

import android.net.Uri;

public class Song {
    private long id;
    private String title;
    private String artist;
    private Uri data; // Content URI for playback and deletion (preferred)
    private long duration;
    private String path; // Direct file path (for deletion fallback on older Android versions)

    // Updated constructor to accept 6 arguments, including 'path'
    public Song(long id, String title, String artist, Uri data, long duration, String path) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.data = data;
        this.duration = duration;
        this.path = path; // Store the direct file path
    }

    // Existing getters
    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public Uri getData() {
        return data;
    }

    public long getDuration() {
        return duration;
    }

    // NEW: Getter for the direct file path
    public String getPath() {
        return path;
    }

    // Override equals and hashCode for reliable list operations (like indexOf)
    // This is crucial for finding songs in lists, especially after shuffle/unshuffle
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return id == song.id; // Compare by ID, assuming IDs are unique for each song
    }

    @Override
    public int hashCode() {
        return Long.valueOf(id).hashCode();
    }
}