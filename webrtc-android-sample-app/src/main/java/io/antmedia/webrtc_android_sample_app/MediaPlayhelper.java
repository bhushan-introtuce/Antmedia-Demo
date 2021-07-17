package io.antmedia.webrtc_android_sample_app;

import android.util.Log;
import android.view.Surface;

import io.antmedia.webrtc_android_sample_app.mediapipe.MySurfaceTexture;
import io.antmedia.webrtc_android_sample_app.mediapipe.NewDefaultPlayer;

public class MediaPlayhelper {

    private MySurfaceTexture surfaceTexture;
    private NewDefaultPlayer player;
    private String TAG = "MediaPlayhelper";

    public MySurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    public void setSurfaceTexture(MySurfaceTexture surfaceTexture) {
        this.surfaceTexture = surfaceTexture;
    }

    public NewDefaultPlayer getPlayer() {
        return player;
    }

    public void setPlayer(NewDefaultPlayer player) {
        this.player = player;
    }

    public void mediaPlay() {
        try {
            surfaceTexture = new MySurfaceTexture(42);
            player = new NewDefaultPlayer();
            player.setSurface(new Surface(surfaceTexture));
            player.setDataSource("https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,950x540_1500,.f4v.csmil/master.m3u8");
            player.setLooping(true);
            //player.setBufferEventInfoListner(bufferEventInfoListner);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.d(TAG, e.toString());
            e.printStackTrace();
        }
    }





}
