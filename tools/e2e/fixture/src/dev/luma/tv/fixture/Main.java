package dev.luma.tv.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** The app's own start screen, which Luma opens when it launches the app instead of a programme. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText(getClass().getSimpleName());
        label.setTextSize(40);
        setContentView(label);
    }
}
