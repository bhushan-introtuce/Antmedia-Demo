package io.antmedia.webrtc_android_sample_app.mediapipe;

import com.google.mediapipe.framework.AppTextureFrame;

public class CustomAppTextureFrame extends AppTextureFrame implements CustomTextureFrame {

    private int bgTextureName;
    public CustomAppTextureFrame(int bgTextureName, int textureName, int width, int height) {
        super(textureName, width, height);
        this.bgTextureName=bgTextureName;
    }

    @Override
    public int getBGTextureName() {
        return bgTextureName;
    }
}
