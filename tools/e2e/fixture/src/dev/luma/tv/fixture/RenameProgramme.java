package dev.luma.tv.fixture;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;

public final class RenameProgramme extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String row = context.getSharedPreferences("row", Context.MODE_PRIVATE).getString("row", null);
        ContentValues values = new ContentValues();
        values.put(WatchNextPrograms.COLUMN_TITLE, intent.getStringExtra("title"));
        setResultCode(context.getContentResolver().update(Uri.parse(row), values, null, null));
    }
}
