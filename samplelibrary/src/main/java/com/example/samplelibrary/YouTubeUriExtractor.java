package com.example.samplelibrary;

import android.content.Context;
import android.util.SparseArray;

@Deprecated
public abstract class YouTubeUriExtractor extends YouTubeExtractor {

    public YouTubeUriExtractor(Context con) {
        super(con);
    }

    @Override
    protected void onExtractionComplete(SparseArray<YtFile> ytFiles, VideoMeta videoMeta, String errMsg) {
        onUrisAvailable(videoMeta.getVideoId(), videoMeta.getTitle(), ytFiles);
    }

    public abstract void onUrisAvailable(String videoId, String videoTitle, SparseArray<YtFile> ytFiles);
}
