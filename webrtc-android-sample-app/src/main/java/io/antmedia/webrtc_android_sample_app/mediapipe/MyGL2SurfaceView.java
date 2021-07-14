package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class MyGL2SurfaceView extends RecordableSurfaceView implements RecordableSurfaceView.RendererCallbacks {

    private final MyGL2Renderer mRenderer;

    public interface CustomSurfaceListener {
        public void onSurfaceChanged(int width, int height);
        public void onSurfaceDestroyed();
        public void onSurfaceCreated(SurfaceTexture surfaceTexture);
    }

    private CustomSurfaceListener customSurfaceListener;

    public void setCustomSurfaceListener(CustomSurfaceListener customSurfaceListener) {
        this.customSurfaceListener = customSurfaceListener;
        mRenderer.setCustomSurfaceListener(customSurfaceListener);
    }

    public MyGL2SurfaceView(Context context) {
        super(context);
        // Set the Renderer for drawing on the GLSurfaceView
        //setEGLContextClientVersion ( 2 );
        mRenderer = new MyGL2Renderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setRendererCallbacks(this);
    }

    private final float TOUCH_SCALE_FACTOR = 180.0f / 320;
    private float mPreviousX;
    private float mPreviousY;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // MotionEvent reports input details from the touch screen
        // and other input controls. In this case, you are only
        // interested in events where the touch position changed.

       /* float x = e.getX();
        float y = e.getY();

        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:

                float dx = x - mPreviousX;
                float dy = y - mPreviousY;

                // reverse direction of rotation above the mid-line
                if (y > getHeight() / 2) {
                    dx = dx * -1 ;
                }

                // reverse direction of rotation to left of the mid-line
                if (x < getWidth() / 2) {
                    dy = dy * -1 ;
                }

                mRenderer.setAngle(
                        mRenderer.getAngle() +
                                ((dx + dy) * TOUCH_SCALE_FACTOR));  // = 180.0f / 320
                requestRender();
        }

        mPreviousX = x;
        mPreviousY = y;*/

        super.onTouchEvent(e);
        return true;
    }

    @Override
    public void onSurfaceCreated() {
        mRenderer.onSurfaceCreated(null, null);
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        mRenderer.onSurfaceChanged(null, width, height);

    }

    @Override
    public void onSurfaceDestroyed() {
        if(customSurfaceListener !=null){
            customSurfaceListener.onSurfaceDestroyed();
        }
    }

    @Override
    public void onContextCreated() {

    }

    @Override
    public void onPreDrawFrame() {

    }

    private BitmapCallBack bitmapCallBack;
    public BitmapCallBack getBitmapCallBack() {
        return bitmapCallBack;
    }

    public void setBitmapCallBack(BitmapCallBack bitmapCallBack) {
        this.bitmapCallBack = bitmapCallBack;
        if(mRenderer!=null){
            mRenderer.setBitmapCallBack(bitmapCallBack);
        }
    }

    public void requestBitmap(){
        if(mRenderer!=null){
            mRenderer.requestBitmap();
        }
    }


    @Override
    public void onDrawFrame() {
        mRenderer.onDrawFrame(null);
    }
}