package com.example.gaanesuno;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    private List<Song> songList;
    private OnItemClickListener listener;
    private int selectedPosition = RecyclerView.NO_POSITION; // To highlight selected song

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public SongAdapter(List<Song> songList) {
        this.songList = songList;
    }

    // Method to set the selected item for highlighting
    public void setSelectedPosition(int position) {
        if (selectedPosition != position) {
            int oldPosition = selectedPosition;
            selectedPosition = position;
            notifyItemChanged(oldPosition); // Redraw old selected item to remove highlight
            notifyItemChanged(selectedPosition); // Redraw new selected item to add highlight
        }
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.song_item, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song currentSong = songList.get(position);
        holder.tvTitle.setText(currentSong.getTitle());
        holder.tvArtist.setText(currentSong.getArtist());

        // Highlight the selected song
        if (selectedPosition == position) {
            holder.itemView.setBackgroundResource(R.color.design_default_color_primary_dark); // Or a specific color
        } else {
            holder.itemView.setBackgroundResource(android.R.color.transparent); // Or your default background
        }
    }

    @Override
    public int getItemCount() {
        return songList.size();
    }

    public class SongViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvArtist;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_song_title_item);
            tvArtist = itemView.findViewById(R.id.tv_song_artist_item);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onItemClick(position);
                    }
                }
            });
        }
    }
}
