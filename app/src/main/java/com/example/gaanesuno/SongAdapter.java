package com.example.gaanesuno;

import android.graphics.Color; // Import for Color.parseColor
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton; // Import for the 3 dots button
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    private List<Song> songList;
    private OnItemClickListener itemClickListener; // Renamed 'listener' for clarity
    private OnSongOptionsClickListener songOptionsClickListener; // New listener for the 3 dots button
    private int selectedPosition = RecyclerView.NO_POSITION; // To highlight currently playing song

    // 1. Interface for general item clicks (playing a song)
    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    // Setter for the general item click listener
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    // 2. NEW: Interface for song options (3 dots) clicks
    public interface OnSongOptionsClickListener {
        // Pass the clicked view as an anchor for the PopupMenu and the song's position
        void onSongOptionsClick(View view, int position);
    }

    // NEW: Setter for the song options click listener
    public void setOnSongOptionsClickListener(OnSongOptionsClickListener listener) {
        this.songOptionsClickListener = listener;
    }

    public SongAdapter(List<Song> songList) {
        this.songList = songList;
    }

    /**
     * Sets the position of the currently selected/playing song.
     * This triggers a redraw of the old and new selected items to update their highlight.
     * @param position The adapter position of the song to highlight.
     */
    public void setSelectedPosition(int position) {
        // Only update if the new position is different from the old one
        if (selectedPosition != position) {
            int oldSelected = selectedPosition;
            selectedPosition = position;
            // Notify RecyclerView that these items have changed so they can be redrawn
            notifyItemChanged(oldSelected); // Redraw old selected item to unhighlight
            notifyItemChanged(selectedPosition); // Redraw new selected item to add highlight
        }
    }

    /**
     * Updates the entire song list and clears any existing selection.
     * @param newSongList The new list of songs.
     */
    public void updateSongList(List<Song> newSongList) {
        this.songList = newSongList;
        this.selectedPosition = RecyclerView.NO_POSITION; // Clear selection
        notifyDataSetChanged();
    }


    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Inflate the item_song.xml layout
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.song_item, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song currentSong = songList.get(position);
        holder.tvTitle.setText(currentSong.getTitle());
        holder.tvArtist.setText(currentSong.getArtist());

        // Update the background color and the visibility of the selected indicator
        if (selectedPosition == position) {
            // Apply a darker background color to the entire item
            holder.itemView.setBackgroundColor(Color.parseColor("#303030")); // A darker gray
            // Show the green indicator bar
            holder.selectedIndicator.setVisibility(View.VISIBLE);
        } else {
            // Revert to the default transparent background (from selectableItemBackground)
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
            // Hide the green indicator bar
            holder.selectedIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return songList.size();
    }

    /**
     * ViewHolder class to hold references to the views in each song item.
     */
    public class SongViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvArtist;
        View selectedIndicator; // Reference to the green indicator bar
        AppCompatImageButton btnOptions; // Reference to the 3 dots button

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            // Initialize TextViews
            tvTitle = itemView.findViewById(R.id.tv_song_item_title); // Note: Changed ID from song_title_item to song_item_title
            tvArtist = itemView.findViewById(R.id.tv_song_item_artist); // Note: Changed ID from song_artist_item to song_item_artist

            // Initialize the new views from item_song.xml
            selectedIndicator = itemView.findViewById(R.id.view_selected_indicator);
            btnOptions = itemView.findViewById(R.id.btn_song_options);

            // Set OnClickListener for the entire item (for playing the song)
            itemView.setOnClickListener(v -> {
                if (itemClickListener != null) {
                    int position = getAdapterPosition(); // Get the current position of the item
                    if (position != RecyclerView.NO_POSITION) { // Ensure position is valid
                        itemClickListener.onItemClick(position);
                    }
                }
            });

            // Set OnClickListener for the 3 dots options button
            btnOptions.setOnClickListener(v -> {
                if (songOptionsClickListener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        // Pass the view (v) itself as an anchor for the PopupMenu, and the position
                        songOptionsClickListener.onSongOptionsClick(v, position);
                    }
                }
            });
        }
    }
}