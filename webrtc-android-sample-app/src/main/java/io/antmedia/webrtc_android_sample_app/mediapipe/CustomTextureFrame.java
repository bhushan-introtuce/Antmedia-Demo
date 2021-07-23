package io.antmedia.webrtc_android_sample_app.mediapipe;

import com.google.mediapipe.framework.TextureFrame;

public interface CustomTextureFrame extends TextureFrame {
    int getBGTextureName();
}
