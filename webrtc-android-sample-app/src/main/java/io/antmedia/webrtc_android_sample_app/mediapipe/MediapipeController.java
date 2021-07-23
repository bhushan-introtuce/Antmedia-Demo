package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketCallback;
import com.google.mediapipe.glutil.EglManager;

public class MediapipeController implements Nex2meSegmenter {

    private static final String TAG = "MediapipeController";
    private static final String BINARY_GRAPH_NAME = "person_segmentation_android_gpu.binarypb";
    private static final String INPUT_VIDEO_STREAM_NAME = "input_video";
    private static final String BG_VIDEO_INPUT_STREAM = "bg_video";
    private static final String OUTPUT_VIDEO_STREAM_NAME = "output_video";
    // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
    // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
    // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
    // corner, whereas MediaPipe in general assumes the image origin is at top-left.
    private static final boolean FLIP_FRAMES_VERTICALLY = true;
    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private MultiInputFrameProcessor processor;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private ViewGroup viewGroup;
    private MpExternalTextureConverter converter;
    private Activity context;
    private TextureInitializerListner textureInitializerListner;

    public MediapipeController(Activity context) {
        this.context = context;
    }

    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (UnsatisfiedLinkError e) {
            // Some example apps (e.g. template matching) require OpenCV 4.
            System.loadLibrary("opencv_java4");
        }
    }

    private MyGL2SurfaceView newSurfaceView;

    @Override
    public void initMediapipe(ViewGroup viewGroup, MyGL2SurfaceView surface) {
        //videoTexture.getSurfaceTexture().detachFromGLContext();
        this.viewGroup = viewGroup;
        newSurfaceView = surface;
        //previewDisplayView = new SurfaceView(this);
        //setupPreviewDisplayView();
        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager((Context) context);
        eglManager = new EglManager(null);
        processor =
                new MultiInputFrameProcessor(
                        context,
                        eglManager.getNativeContext(),
                        BINARY_GRAPH_NAME,
                        INPUT_VIDEO_STREAM_NAME,
                        OUTPUT_VIDEO_STREAM_NAME, BG_VIDEO_INPUT_STREAM);
        processor.getVideoSurfaceOutput().setFlipY(FLIP_FRAMES_VERTICALLY);

        processor.getGraph().addPacketCallback(OUTPUT_VIDEO_STREAM_NAME, new PacketCallback() {
            @Override
            public void process(Packet packet) {
                Log.d(TAG, "new output");
            }
        });
        PermissionHelper.checkAndRequestCameraPermissions(context);
    }

    @Override
    public void setOutputSurface(MyGL2SurfaceView surface) {
        newSurfaceView = surface;
    }

    @Override
    public void resume() {
        Log.d("SURFACE_CREATION", "mediapipe resume");
        converter = new MpExternalTextureConverter(eglManager.getContext());
        converter.setFlipY(FLIP_FRAMES_VERTICALLY);
        converter.setConsumer(processor);
        converter.setFgTimeStamp(processor.getFgTimestamp());
        setupPreviewDisplayView(viewGroup);
        if (PermissionHelper.cameraPermissionsGranted(context)) {
            setFrameReceiveMode(true);
            try {
                startGraph();
            } catch (Exception e) {
                Log.d("SURFACE_CREATION", e.toString());
                Log.d(TAG, e.toString());
            }
        }
    }


    @Override
    public void pause() {
        frameReceiveMode = false;
        converter.close();
        newSurfaceView.setVisibility(View.GONE);
    }

    private void setupPreviewDisplayView(ViewGroup viewGroup) {
        Log.d("SURFACE_CREATION", "setup start ");
        newSurfaceView.setVisibility(View.GONE);
        viewGroup.removeAllViews();
        viewGroup.addView(newSurfaceView);
        MyGL2SurfaceView.CustomSurfaceListener surfaceListener = new MyGL2SurfaceView.CustomSurfaceListener() {
            @Override
            public void onSurfaceChanged(int width, int height) {
                Log.d(TAG, "Setting " + width + " , " + height);
                //initRecorder();
            }

            @Override
            public void onSurfaceDestroyed() {
                processor.getVideoSurfaceOutput().setSurface(null);
            }

            @Override
            public void onSurfaceCreated(SurfaceTexture surfaceTexture) {
                Log.d(TAG, "Setting up output surface");
                Log.d("SURFACE_CREATION", "surface created ");
                Surface surface = new Surface(surfaceTexture);
                processor.getVideoSurfaceOutput().setSurface(surface);
                //newSurfaceView.resume();
                if (eventListener != null) {
                    eventListener.onSurfaceAvailable();
                }
            }
        };
        newSurfaceView.setCustomSurfaceListener(surfaceListener);
       /* textureView.setVisibility(View.GONE);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG,"onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height)");
                Surface surface1 = new Surface(surface);
                mHeight=height;
                mWidth=width;
                processor.getVideoSurfaceOutput().setSurface(surface1);
                //converter.setSurfaceTextureAndAttachToGLContext(previewFrameTexture,width,height);
                //converter.setbGSurfaceTextureAndAttachToGLContext(surfaceTexture,width,height);
                //converter.setbGSurfaceTextureAndAttachToGLContext(previewFrameTexture,width,height);
                //converter.setSurfaceTextureAndAttachToGLContext(surfaceTexture,width,height);
                //initRecorder();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.d(TAG,"onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height)");
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.d(TAG,"oonSurfaceTextureDestroyed(SurfaceTexture surface)");
                processor.getVideoSurfaceOutput().setSurface(null);
                return false;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                //Log.d(TAG,"onSurfaceTextureUpdated(SurfaceTexture surface)");
            }
        });*/
    }

    private boolean frameReceiveMode = false;

    @Override
    public void setFrameReceiveMode(boolean frameReceiveMode) {
        this.frameReceiveMode = frameReceiveMode;
    }

    @Override
    public void onForegroundFrame(Bitmap bitmap) {
        if (frameReceiveMode && converter != null) {
            Log.d(TAG, "onForegroundFrame(Bitmap bitmap)");
            converter.onFrame(bitmap);
        }
    }

    @Override
    public void onBackGroundFrame(Bitmap bitmap) {
        if (frameReceiveMode && converter != null) {
            converter.onBGFrame(bitmap);
        }
    }

    @Override
    public void startGraph() throws NoOutputTextureDefineException {
        Log.d(TAG, "Starting graph");
        Log.d("SURFACE_CREATION", "starting graph");
        if (newSurfaceView != null) {
            newSurfaceView.setVisibility(View.VISIBLE);
            newSurfaceView.resume();
        } else {
            throw new NoOutputTextureDefineException("No output surface found");
        }
    }

    public interface EventListener extends Nex2meSegmenterEventListener {

    }

    private Nex2meSegmenterEventListener eventListener;

    @Override
    public void setEventListener(Nex2meSegmenterEventListener eventListener) {
        this.eventListener = eventListener;
    }

    public void setTextureInitializerListener(TextureInitializerListner textureInitializerListner) {
        this.textureInitializerListner = textureInitializerListner;
    }
}