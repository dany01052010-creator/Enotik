package com.enotik.game;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
public class MainActivity extends Activity {
    WebView w;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        setContentView(w);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        w.setWebChromeClient(new WebChromeClient());
        w.loadUrl("file:///android_asset/index.html");
        hide();
    }
    void hide() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hide(); }
    @Override protected void onPause() { super.onPause(); w.onPause(); }
    @Override protected void onResume() { super.onResume(); w.onResume(); }
}
