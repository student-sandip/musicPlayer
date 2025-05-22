package com.example.gaanesuno;

import android.net.Uri;
import java.io.Serializable; // Add Serializable for passing objects via Intent

public class Song implements Serializable { // Implement Serializable
    private long id;
    private String title;
    private String artist;
    private Uri data; // URI to the actual audio file
    private long duration; // Add duration

    public Song(long id, String title, String artist, Uri data, long duration) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.data = data;
        this.duration = duration;
    }

    // Getters
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
}