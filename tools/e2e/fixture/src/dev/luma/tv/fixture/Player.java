package dev.luma.tv.fixture;

import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;
import android.os.Bundle;

/** A stand-in player that owns one Watch Next row.
 *
 *  Started with a title, it writes the row (inserting it once, then updating the same row) and stores the
 *  "next" values. Opened from the row's link without a title, it plays: it writes the stored next values to the
 *  same row, the way a real player saves progress, and stays open until Back. With clear it deletes its rows. */
public class Player extends Main {
    static final long DURATION = 60_000L;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        SharedPreferences prefs = getSharedPreferences("row", MODE_PRIVATE);
        if (intent.getBooleanExtra("clear", false)) {
            String previous = prefs.getString("row", null);
            if (previous != null) getContentResolver().delete(Uri.parse(previous), null, null);
            prefs.edit().clear().commit();
        }
        if (intent.hasExtra("title")) {
            write(prefs, intent.getStringExtra("title"), intent.getLongExtra("position", 0), intent.getStringExtra("link"));
            prefs.edit()
                .putString("next_title", intent.getStringExtra("next_title"))
                .putLong("next_position", intent.getLongExtra("next_position", 0))
                .putString("next_link", intent.getStringExtra("next_link"))
                .commit();
        } else if (prefs.getString("next_title", null) != null) {
            write(prefs, prefs.getString("next_title", null), prefs.getLong("next_position", 0), prefs.getString("next_link", null));
            prefs.edit().remove("next_title").remove("next_position").remove("next_link").commit();
        }
        if (intent.getBooleanExtra("finish", false)) finish();
    }

    private void write(SharedPreferences prefs, String title, long position, String link) {
        ContentValues row = new ContentValues();
        row.put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_MOVIE);
        row.put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        row.put(WatchNextPrograms.COLUMN_TITLE, title);
        if (getIntent().hasExtra("poster")) row.put(WatchNextPrograms.COLUMN_POSTER_ART_URI, getIntent().getStringExtra("poster"));
        row.put(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, "Fixture episode.");
        row.put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, position);
        row.put(WatchNextPrograms.COLUMN_DURATION_MILLIS, DURATION);
        row.put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
        row.put(WatchNextPrograms.COLUMN_INTENT_URI, link);
        String saved = prefs.getString("row", null);
        if (saved != null && getContentResolver().update(Uri.parse(saved), row, null, null) == 1) return;
        Uri inserted = getContentResolver().insert(WatchNextPrograms.CONTENT_URI, row);
        if (inserted == null) throw new IllegalStateException("The TV provider refused the Watch Next row");
        prefs.edit().putString("row", inserted.toString()).commit();
    }
}
