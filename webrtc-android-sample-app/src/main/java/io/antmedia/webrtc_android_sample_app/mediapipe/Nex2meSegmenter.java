package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.graphics.Bitmap;
import android.view.ViewGroup;

public interface Nex2meSegmenter {
    void initMediapipe(ViewGroup viewGroup, MyGL2SurfaceView surface);

    void resume();

    void pause();

    void setFrameReceiveMode(boolean frameReceiveMode);

    void onForegroundFrame(Bitmap bitmap);

    void onBackGroundFrame(Bitmap bitmap);

    void setOutputSurface(MyGL2SurfaceView surface);

    void setEventListener(Nex2meSegmenterEventListener eventListener);

    void startGraph() throws NoOutputTextureDefineException;

    class NoOutputTextureDefineException extends Exception {
        public NoOutputTextureDefineException(String message) {
            super(message);
        }
    }

    interface Nex2meSegmenterEventListener {
        void onSurfaceAvailable();
    }
}
