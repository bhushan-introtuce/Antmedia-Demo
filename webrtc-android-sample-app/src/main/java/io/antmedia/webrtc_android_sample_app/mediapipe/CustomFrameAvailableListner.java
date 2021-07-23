package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.graphics.Bitmap;


public interface CustomFrameAvailableListner {

    public void onFrame(Bitmap bitmap);
    public void onBGFrame(Bitmap bitmap);
}
