package io.antmedia.webrtc_android_sample_app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.common.flogger.backend.LogData;
import com.google.mediapipe.framework.AndroidAssetUtil;

import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.webrtc.DataChannel;
import org.webrtc.EglRenderer;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.voiceengine.NewFrameListioner;
import org.webrtc.voiceengine.NewNetworkTextureListioner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;

import de.tavendo.autobahn.WebSocket;
import io.antmedia.webrtc_android_sample_app.mediapipe.GLTochListner;
import io.antmedia.webrtc_android_sample_app.mediapipe.MediapipeController;
import io.antmedia.webrtc_android_sample_app.mediapipe.MyGL2SurfaceView;
import io.antmedia.webrtc_android_sample_app.mediapipe.Nex2meSegmenter;
import io.antmedia.webrtcandroidframework.IDataChannelObserver;
import io.antmedia.webrtcandroidframework.IWebRTCClient;
import io.antmedia.webrtcandroidframework.IWebRTCListener;
import io.antmedia.webrtcandroidframework.StreamInfo;
import io.antmedia.webrtcandroidframework.WebRTCClient;
import io.antmedia.webrtcandroidframework.apprtc.CallActivity;

import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_DATA_CHANNEL_ENABLED;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_VIDEO_BITRATE;
import static io.antmedia.webrtcandroidframework.apprtc.CallActivity.EXTRA_VIDEO_FPS;
import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.imgproc.Imgproc.cvtColor;
import static org.opencv.imgproc.Imgproc.threshold;

public class MainActivity extends AppCompatActivity implements IWebRTCListener, IDataChannelObserver {

    /**
     * Change this address with your Ant Media Server address
     */
    public static final String SERVER_ADDRESS = "172.105.36.192:5080";

    /**
     * Mode can Publish, Play or P2P
     */

    private String webRTCMode = IWebRTCClient.MODE_JOIN;

    private boolean enableDataChannel = true;

    public static final String SERVER_URL = "ws://" + SERVER_ADDRESS + "/WebRTCAppEE/websocket";
    public static final String REST_URL = "http://" + SERVER_ADDRESS + "/WebRTCAppEE/rest/v2";

    private WebRTCClient webRTCClient;

    private Button startStreamingButton;
    private String operationName = "";
    private String streamId;

    private SurfaceViewRenderer cameraViewRenderer;
    private SurfaceViewRenderer pipViewRenderer;
    private Spinner streamInfoListSpinner;

    // variables for handling reconnection attempts after disconnected
    final int RECONNECTION_PERIOD_MLS = 1000000;
    private boolean stoppedStream = false;
    Handler reconnectionHandler = new Handler();
    Runnable reconnectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!webRTCClient.isStreaming()) {
                attempt2Reconnect();
                // call the handler again in case startStreaming is not successful
                reconnectionHandler.postDelayed(this, RECONNECTION_PERIOD_MLS);
            }
        }
    };

    private String TAG = "MainActivity";
    TextureView camTexture, pipTexture;

    // For Mediapipe Integration
    private MyGL2SurfaceView newSurfaceView;
    Nex2meSegmenter mediapipeController;
    private ViewGroup mediapipeView;
    private Bitmap original, mask, incoming;

    static {
        // Load all native libraries need ed by the app.
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (java.lang.UnsatisfiedLinkError e) {
            // Some example apps (e.g. template matching) require OpenCV 4.
            System.loadLibrary("opencv_java4");
        }
    }

    private void initializaMediapipe() {
        mediapipeView = findViewById(R.id.mediapipeView);
        original = BitmapFactory.decodeResource(getResources(), R.drawable.button_press);
        mediapipeController = new MediapipeController(MainActivity.this);
        mediapipeController.setEventListener(mediapipeEventListner);
        mediapipeController.initMediapipe(mediapipeView, newSurfaceView);
    }

    private MediapipeController.EventListener mediapipeEventListner = new MediapipeController.EventListener() {
        @Override
        public void onSurfaceAvailable() {
            try {
                Log.d("SURFACE_CREATION", "surface available");
            } catch (Exception e) {
                Log.d("SURFACE_CREATION", "Error recorder " + e.toString());
            }
        }
    };


    @Override
    protected void onResume() {
        super.onResume();
        resumeMediapipe();

        try {
            newSurfaceView.resume();
            initializaMediapipe();

        } catch (Exception e) {

        }

//        newSetupDisplay();
//        converter = new AcExternalTextureConverter(eglManager.getContext());
//        converter.setFlipY(true);
//        converter.setConsumer(processor);
//        converter.setFgTimeStamp(processor.getFgTimestamp());
////        if (PermissionHelper.cameraPermissionsGranted(this)) {
////            startCamera();
////        }
//        mediaPlay();

    }

//    private void startCamera() {
//        cameraHelper = new MyCameraXHelper();
//        cameraHelper.setOnCameraStartedListener(
//                surfaceTexture -> {
//                    previewFrameTexture = surfaceTexture;
//                    // Make the display view visible to start showing the preview. This triggers the
//                    // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
//                    // videoTexture.setVisibility(View.VISIBLE);
//                    newSurfaceView.setVisibility(View.VISIBLE);
//                    newSurfaceView.resume();
//
//                    Log.d(TAG, "Camera Started");
//                });
//        cameraHelper.startCamera(this, CAMERA_FACING, /*surfaceTexture=*/ null);
//    }
//
//    private void newSetupDisplay() {
//        Log.d(TAG, "Creation start");
//        newSurfaceView = new MyGL2SurfaceView(this);
//        //newSurfaceView.setVisibility(View.GONE);
//        ViewGroup viewGroup = findViewById(R.id.mediapie_view);
//        viewGroup.removeAllViews();
//        viewGroup.addView(newSurfaceView);
//
//        MyGL2SurfaceView.CustomSurfaceListener surfaceListener = new MyGL2SurfaceView.CustomSurfaceListener() {
//            @Override
//            public void onSurfaceChanged(int width, int height) {
//                Log.d(TAG, "Created surface");
//                Log.d(TAG, "Setting " + width + " , " + height);
//
//                Size viewSize = new Size(width, height);
////                Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
//
//
//                converter.setbGSurfaceTextureAndAttachToGLContext(
//                        surfaceTexture, width, height);
//
//                converter.setSurfaceTextureAndAttachToGLContext(
//                        previewFrameTexture, width, height);
//
//
//            }
//
//            @Override
//            public void onSurfaceDestroyed() {
//                processor.getVideoSurfaceOutput().setSurface(null);
//            }
//
//            @Override
//            public void onSurfaceCreated(SurfaceTexture surfaceTexture) {
//                Log.d(TAG, "On Surface Created");
//                Surface surface = new Surface(surfaceTexture);
//                processor.getVideoSurfaceOutput().setSurface(surface);
//
//
//            }
//        };
//        newSurfaceView.setCustomSurfaceListener(surfaceListener);
//    }


    @Override
    protected void onPause() {
        super.onPause();
        newSurfaceView.pause();

//        converter.onpause();
//        converter.close();
//        System.gc();
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            this.requestWindowFeature(Window.FEATURE_NO_TITLE);
            this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!OpenCVLoader.initDebug())
            Log.d("ERROR", "Unable to load OpenCV");
        else
            Log.d("SUCCESS", "OpenCV loaded " + System.currentTimeMillis());

//
//
        AndroidAssetUtil.initializeNativeAssetManager(this);

        initializaMediapipe();

//        eglManager = new EglManager(null);
//
//        processor = new MultiInputFrameProcessor(
//                this,
//                eglManager.getNativeContext(),
//                BINARY_GRAPH_NAME,
//                INPUT_VIDEO_STREAM_NAME,
//                OUTPUT_VIDEO_STREAM_NAME, BG_VIDEO_INPUT_STREAM);
//
//        processor.getVideoSurfaceOutput().setFlipY(true); // Flip frames Vertically
//        processor.getGraph().addPacketCallback(OUTPUT_VIDEO_STREAM_NAME, new PacketCallback() {
//            @Override
//            public void process(Packet packet) {
//                Log.d(TAG, "On New Packet : " + packet.getTimestamp());
//            }
//        });

        cameraViewRenderer = findViewById(R.id.camera_view_renderer);
        pipViewRenderer = findViewById(R.id.pip_view_renderer);
        camTexture = findViewById(R.id.texture_view_Camera);
        pipTexture = findViewById(R.id.texture_view_pip);
        startStreamingButton = findViewById(R.id.start_streaming_button);

        streamInfoListSpinner = findViewById(R.id.stream_info_list);

        if (!webRTCMode.equals(IWebRTCClient.MODE_PLAY)) {
            streamInfoListSpinner.setVisibility(View.INVISIBLE);
        } else {
            streamInfoListSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                boolean firstCall = true;

                @Override
                public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                    //for some reason in android onItemSelected is called automatically at first.
                    //there are some discussions about it in stackoverflow
                    //so we just have simple check
                    if (firstCall) {
                        firstCall = false;
                        return;
                    }
                    webRTCClient.forceStreamQuality(Integer.parseInt((String) adapterView.getSelectedItem()));
                    Log.i("MainActivity", "Spinner onItemSelected");
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView) {

                }
            });
        }


        cameraViewRenderer.getHolder().addCallback(new SurfaceHolder.Callback2() {
            @Override
            public void surfaceRedrawNeeded(SurfaceHolder surfaceHolder) {
                Log.d("TEST", surfaceHolder.getSurface().toString() + "");
            }

            @Override
            public void surfaceCreated(SurfaceHolder surfaceHolder) {

            }

            @Override
            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                Log.d("TEST", surfaceHolder.getSurface().toString() + "On changed ");
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

            }
        });


        // Check for mandatory permissions.
        for (String permission : CallActivity.MANDATORY_PERMISSIONS) {
            if (this.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission " + permission + " is not granted", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (webRTCMode.equals(IWebRTCClient.MODE_PUBLISH)) {
            startStreamingButton.setText("Start Publishing");
            operationName = "Publishing";
        } else if (webRTCMode.equals(IWebRTCClient.MODE_PLAY)) {
            startStreamingButton.setText("Start Playing");
            operationName = "Playing";
        } else if (webRTCMode.equals(IWebRTCClient.MODE_JOIN)) {
            startStreamingButton.setText("Start P2P");
            operationName = "P2P";
        }
        this.getIntent().putExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, true);
        this.getIntent().putExtra(EXTRA_VIDEO_FPS, 30);
        this.getIntent().putExtra(EXTRA_VIDEO_BITRATE, 1500);
        this.getIntent().putExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, true);
        this.getIntent().putExtra(EXTRA_DATA_CHANNEL_ENABLED, enableDataChannel);

        // mediapipe Impl


        // previewDisplayView = new SurfaceView(this);
        //setupPreviewDisplayView();

        webRTCClient = new WebRTCClient(this, this);


        webRTCClient.setListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {

                VideoFrame.I420Buffer mainbuffer = frame.getBuffer().toI420();
                // EGL14.eglMakeCurrent(new EGL14.eglGetDisplay(), new EGLSurface,new EGLContext());

            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {
//                Log.d(TAG, "New Texture");
//
//                if (i == 0) {
//                    i = 1;
//                    //previewFrameTexture = texture;
//
////                    SurfaceTexture temp = new SurfaceTexture(12);
////                    previewFrameTexture = temp;
//
//                    // previewFrameTexture.detachFromGLContext();
//
////                    runOnUiThread(new Runnable() {
////                        @Override
////                        public void run() {
////                            texture2.setSurfaceTexture(texture);
////                        }
////                    });
//
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            texture2.setSurfaceTexture(texture);
//                        }
//                    });
//
//                    texture.detachFromGLContext();
//
//
////                    texture2.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
////                        @Override
////                        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
////
////                        }
////
////                        @Override
////                        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {
////
////                        }
////
////                        @Override
////                        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
////                            return false;
////                        }
////
////                        @Override
////                        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
////                            if (j == 0) {
////                                j = 1;
////                                //previewFrameTexture = surfaceTexture;
////
////                            }
////                        }
////                    });
//
//                }
            }
            //  }
        });

        webRTCClient.setNetworkTextureListioner(new NewNetworkTextureListioner() {
            @Override
            public void onNewNetworkTexture(SurfaceTexture texture) {
//                Log.d(TAG, "new Network Texture");
//
////                Log.d(TAG, "New Texture From Network");
////                //previewFrameTexture = texture;
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            texture1.setSurfaceTexture(texture);
//                        }
//
//                    });
//
//                    texture.detachFromGLContext();

            }
        });
        // eglManager = new EglManager(null);
//
//        try{
//
//            // Mediapipe Processor
//            processor = new MultiInputFrameProcessor(
//                    this,
//                    eglManager.getNativeContext(),
//                    BINARY_GRAPH_NAME,
//                    INPUT_VIDEO_STREAM_NAME,
//                    OUTPUT_VIDEO_STREAM_NAME, BG_VIDEO_INPUT_STREAM);
//
//        }catch (Exception e)
//        {
//
//        }
//
//
//        processor.getVideoSurfaceOutput().setFlipY(true);
//        processor.getGraph().addPacketCallback(OUTPUT_VIDEO_STREAM_NAME, new PacketCallback() {
//            @Override
//            public void process(Packet packet) {
//                //Log.d("FRAME_TEST", "New frame " + hasImin);
//                long curTime = System.currentTimeMillis();
//              //  Log.d("RUNTIME>>", curTime - oldTime + "MS");
//               // run_logs = run_logs + (curTime - oldTime) + " MS\n ";
//                Log.d("RUNTIME>>", curTime - oldTime + "MS");
//                oldTime = curTime;
//
////                if (!hasImin) {
////                    Log.d("FRAME_TEST_i", "Condition true");
////                    hasImin = true;
////                    directionMessage();
////                }
//            }
//        });


        //webRTCClient.setOpenFrontCamera(false);

        streamId = "stream1";
        String tokenId = "tokenId";
        webRTCClient.setVideoRenderers(pipViewRenderer, cameraViewRenderer);

//        cameraViewRenderer.getHolder().addCallback(new SurfaceHolder.Callback() {
//            @Override
//            public void surfaceCreated(SurfaceHolder surfaceHolder) {
//                processor.getVideoSurfaceOutput().setSurface(surfaceHolder.getSurface());
//
//            }
//
//            @Override
//            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int width, int height) {
//
//                // (Re-)Compute the ideal size of the camera-preview display (the area that the
//                // camera-preview frames get rendered onto, potentially with scaling and rotation)
//                // based on the size of the SurfaceView that contains the display.
//               // Size viewSize = new Size(width, height);
//               // Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
//
//                // Connect the converter to the camera-preview frames as its input (via
//                // previewFrameTexture), and configure the output width and height as the computed
//                // display size.
//                converter.setSurfaceTextureAndAttachToGLContext(
//                        previewFrameTexture,width, height);
//
//            }
//
//            @Override
//            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
//                processor.getVideoSurfaceOutput().setSurface(null);
//            }
//        });
        cameraViewRenderer.addFrameListener(new EglRenderer.FrameListener() {
            @Override
            public void onFrame(Bitmap frame) {
                Log.d("TEST", "mON Bitmap Frame");
            }
        }, 20f);


        cameraViewRenderer.setFrameListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {
                Log.d(TAG, "new Frame by CameraRenderer");
//                VideoFrame.I420Buffer mainBuffer = frame.getBuffer().toI420();
//
//                byte[] byteY = new byte[mainBuffer.getDataY().remaining()];
//                mainBuffer.getDataY().get(byteY);
//
//                byte[] byteU = new byte[mainBuffer.getDataU().remaining()];
//                mainBuffer.getDataU().get(byteU);
//
//                byte[] byteV = new byte[mainBuffer.getDataV().remaining()];
//                mainBuffer.getDataV().get(byteV);
//
//                byte[] finalBuffer = new byte[byteY.length + byteU.length + byteV.length];
//
//                Mat picyv12 = new Mat(frame.getRotatedHeight(), frame.getRotatedWidth(), CvType.CV_8UC1);  //(im_height*3/2,im_width), should be even no...


                Bitmap iconbg = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888);
                Bitmap iconfg = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888);

                //
//                System.arraycopy(byteY, 0, finalBuffer, 0, byteY.length);
//                System.arraycopy(byteU, 0, finalBuffer, byteY.length, byteU.length);
//                System.arraycopy(byteV, 0, finalBuffer, byteY.length + byteU.length, byteV.length);
//
//                picyv12.put(0, 0, finalBuffer); // buffer - byte array with i420 data
//             //   Imgproc.cvtColor(picyv12, picyv12, Imgproc.COLOR_YUV2BGR_YV12);
//
//                Utils.matToBitmap(picyv12, tbmp2);
                Log.d(TAG, "Tesrt");

                // Bitmap icon = BitmapFactory.decodeResource(getResources(),R.drawable.button_focus);
                if (new Random().nextInt(200) > 100) {
                    iconbg.eraseColor(Color.GREEN);
                    iconfg.eraseColor(Color.RED);
                } else {
                    iconbg.eraseColor(Color.RED);
                    iconfg.eraseColor(Color.GREEN);
                }

                mediapipeController.onForegroundFrame(iconfg);
                mediapipeController.onBackGroundFrame(iconbg);
                Log.d(TAG, GLES20.glGetError() + "Egl status");

            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {

//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        camTexture.setSurfaceTexture(texture);
//                    }
//                });
//
//                texture.detachFromGLContext();

            }
        });
        pipViewRenderer.setFrameListioner(new NewFrameListioner() {
            @Override
            public void onNewFrame(VideoFrame frame) {
                Log.d(TAG, "new Frame by pipViewRenderer");

//                VideoFrame.I420Buffer mainBuffer = frame.getBuffer().toI420();
//
//                byte[] byteY = new byte[mainBuffer.getDataY().remaining()];
//                mainBuffer.getDataY().get(byteY);
//
//                byte[] byteU = new byte[mainBuffer.getDataU().remaining()];
//                mainBuffer.getDataU().get(byteU);
//
//                byte[] byteV = new byte[mainBuffer.getDataV().remaining()];
//                mainBuffer.getDataV().get(byteV);
//
//                byte[] finalBuffer = new byte[byteY.length + byteU.length + byteV.length];
//
//                Mat picyv12 = new Mat(frame.getRotatedHeight(), frame.getRotatedWidth(), CvType.CV_8UC1);  //(im_height*3/2,im_width), should be even no...
//                Bitmap tbmp2 = Bitmap.createBitmap(frame.getRotatedWidth(), frame.getRotatedHeight(), Bitmap.Config.ARGB_8888);
//
//                System.arraycopy(byteY, 0, finalBuffer, 0, byteY.length);
//                System.arraycopy(byteU, 0, finalBuffer, byteY.length, byteU.length);
//                System.arraycopy(byteV, 0, finalBuffer, byteY.length + byteU.length, byteV.length);
//
//                picyv12.put(0, 0, finalBuffer); // buffer - byte array with i420 data
//               // Imgproc.cvtColor(picyv12, picyv12, Imgproc.COLOR_YUV2BGR_YV12);
//
//                Utils.matToBitmap(picyv12, tbmp2);
                Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.button_focus);
                mediapipeController.onBackGroundFrame(icon);
            }

            @Override
            public void onNewTexture(SurfaceTexture texture) {
//                Log.d(TAG, "new Texture by pipViewRenderer");
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        pipTexture.setSurfaceTexture(texture);
//                    }
//                });
//
//                texture.detachFromGLContext();
            }
        });

        // this.getIntent().putExtra(CallActivity.EXTRA_VIDEO_FPS, 24);
        webRTCClient.init(SERVER_URL, streamId, webRTCMode, tokenId, this.getIntent());
        webRTCClient.setDataChannelObserver(this);
    }

//    private void setupPreviewDisplayView() {
//        previewDisplayView.setVisibility(View.GONE);
//        ViewGroup viewGroup = findViewById(R.id.publisher_container);
//        viewGroup.addView(previewDisplayView);
//    }


    public void startStreaming(View v) {

        if (!webRTCClient.isStreaming()) {
            ((Button) v).setText("Stop " + operationName);
            webRTCClient.startStream();
            if (webRTCMode == IWebRTCClient.MODE_JOIN) {
                pipViewRenderer.setZOrderOnTop(true);
            }
        } else {
            ((Button) v).setText("Start " + operationName);
            webRTCClient.stopStream();
            webRTCClient.startStream();
            stoppedStream = true;
        }

    }

    private void attempt2Reconnect() {
        Log.w(getClass().getSimpleName(), "Attempt2Reconnect called");
        if (!webRTCClient.isStreaming()) {
            webRTCClient.startStream();
            if (webRTCMode == IWebRTCClient.MODE_JOIN) {
                pipViewRenderer.setZOrderOnTop(true);
            }
        }
    }

    @Override
    public void onPlayStarted(String streamId) {
        Log.w(getClass().getSimpleName(), "onPlayStarted");
        Toast.makeText(this, "Play started", Toast.LENGTH_LONG).show();
        webRTCClient.switchVideoScaling(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        webRTCClient.getStreamInfoList();
    }

    @Override
    public void onPublishStarted(String streamId) {
        Log.w(getClass().getSimpleName(), "onPublishStarted");
        Toast.makeText(this, "Publish started", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPublishFinished(String streamId) {
        Log.w(getClass().getSimpleName(), "onPublishFinished");
        Toast.makeText(this, "Publish finished", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPlayFinished(String streamId) {
        Log.w(getClass().getSimpleName(), "onPlayFinished");
        Toast.makeText(this, "Play finished", Toast.LENGTH_LONG).show();
    }

    @Override
    public void noStreamExistsToPlay(String streamId) {
        Log.w(getClass().getSimpleName(), "noStreamExistsToPlay");
        Toast.makeText(this, "No stream exist to play", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void streamIdInUse(String streamId) {
        Log.w(getClass().getSimpleName(), "streamIdInUse");
        Toast.makeText(this, "Stream id is already in use.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onError(String description, String streamId) {
        Toast.makeText(this, "Error: " + description, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        webRTCClient.stopStream();
    }

    @Override
    public void onSignalChannelClosed(WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification code, String streamId) {
        Toast.makeText(this, "Signal channel closed with code " + code, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDisconnected(String streamId) {
        Log.w(getClass().getSimpleName(), "disconnected");
        Toast.makeText(this, "Disconnected", Toast.LENGTH_LONG).show();

        startStreamingButton.setText("Start " + operationName);
        // handle reconnection attempt
        if (!stoppedStream) {
            Toast.makeText(this, "Disconnected Attempting to reconnect", Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!reconnectionHandler.hasCallbacks(reconnectionRunnable)) {
                    reconnectionHandler.postDelayed(reconnectionRunnable, RECONNECTION_PERIOD_MLS);
                }
            } else {
                reconnectionHandler.postDelayed(reconnectionRunnable, RECONNECTION_PERIOD_MLS);
            }
        } else {
            Toast.makeText(this, "Stopped the stream", Toast.LENGTH_LONG).show();
            stoppedStream = false;
        }
    }

    @Override
    public void onIceConnected(String streamId) {
        //it is called when connected to ice
        startStreamingButton.setText("Stop " + operationName);
        // remove scheduled reconnection attempts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (reconnectionHandler.hasCallbacks(reconnectionRunnable)) {
                reconnectionHandler.removeCallbacks(reconnectionRunnable, null);
            }
        } else {
            reconnectionHandler.removeCallbacks(reconnectionRunnable, null);
        }
    }

    @Override
    public void onIceDisconnected(String streamId) {
        //it's called when ice is disconnected
    }

    public void onOffVideo(View view) {
        if (webRTCClient.isVideoOn()) {
            webRTCClient.disableVideo();
        } else {
            webRTCClient.enableVideo();
        }
    }

    public void onOffAudio(View view) {
        if (webRTCClient.isAudioOn()) {
            webRTCClient.disableAudio();
        } else {
            webRTCClient.enableAudio();
        }
    }

    @Override
    public void onTrackList(String[] tracks) {

    }

    @Override
    public void onBitrateMeasurement(String streamId, int targetBitrate, int videoBitrate, int audioBitrate) {
        Log.e(getClass().getSimpleName(), "st:" + streamId + " tb:" + targetBitrate + " vb:" + videoBitrate + " ab:" + audioBitrate);
        if (targetBitrate < (videoBitrate + audioBitrate)) {
            Toast.makeText(this, "low bandwidth", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStreamInfoList(String streamId, ArrayList<StreamInfo> streamInfoList) {
        String[] stringArray = new String[streamInfoList.size()];
        int i = 0;
        for (StreamInfo si : streamInfoList) {
            stringArray[i++] = si.getHeight() + "";
        }
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, android.R.id.text1, stringArray);
        streamInfoListSpinner.setAdapter(modeAdapter);
    }

    /**
     * This method is used in an experiment. It's not for production
     *
     * @param streamId
     */
    public void calculateAbsoluteLatency(String streamId) {
        String url = REST_URL + "/broadcasts/" + streamId + "/rtmp-to-webrtc-stats";

        RequestQueue queue = Volley.newRequestQueue(this);


        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        try {
                            Log.i("MainActivity", "recevied response " + response);
                            JSONObject jsonObject = new JSONObject(response);
                            long absoluteStartTimeMs = jsonObject.getLong("absoluteTimeMs");
                            //this is the frame id in sending the rtp packet. Actually it's rtp timestamp
                            long frameId = jsonObject.getLong("frameId");
                            long relativeCaptureTimeMs = jsonObject.getLong("captureTimeMs");
                            long captureTimeMs = frameId / 90;
                            Map<Long, Long> captureTimeMsList = WebRTCClient.getCaptureTimeMsMapList();

                            long absoluteDecodeTimeMs = 0;
                            if (captureTimeMsList.containsKey(captureTimeMs)) {
                                absoluteDecodeTimeMs = captureTimeMsList.get(captureTimeMs);
                            }

                            long absoluteLatency = absoluteDecodeTimeMs - relativeCaptureTimeMs - absoluteStartTimeMs;
                            Log.i("MainActivity", "recevied absolute start time: " + absoluteStartTimeMs
                                    + " frameId: " + frameId + " relativeLatencyMs : " + relativeCaptureTimeMs
                                    + " absoluteDecodeTimeMs: " + absoluteDecodeTimeMs
                                    + " absoluteLatency: " + absoluteLatency);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }


                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("MainActivity", "That didn't work!");

            }
        });

        // Add the request to the RequestQueue.
        queue.add(stringRequest);

    }

    @Override
    public void onBufferedAmountChange(long previousAmount, String dataChannelLabel) {
        Log.d(MainActivity.class.getName(), "Data channel buffered amount changed: ");
    }

    @Override
    public void onStateChange(DataChannel.State state, String dataChannelLabel) {
        Log.d(MainActivity.class.getName(), "Data channel state changed: ");
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, String dataChannelLabel) {
        ByteBuffer data = buffer.data;
        String messageText = new String(data.array(), StandardCharsets.UTF_8);
        Toast.makeText(this, "New Message: " + messageText, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onMessageSent(DataChannel.Buffer buffer, boolean successful) {
        if (successful) {
            ByteBuffer data = buffer.data;
            final byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String messageText = new String(bytes, StandardCharsets.UTF_8);

            Toast.makeText(this, "Message is sent", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not send the text message", Toast.LENGTH_LONG).show();
        }
    }

    public void sendTextMessage(String messageToSend) {
        final ByteBuffer buffer = ByteBuffer.wrap(messageToSend.getBytes(StandardCharsets.UTF_8));
        DataChannel.Buffer buf = new DataChannel.Buffer(buffer, false);
        webRTCClient.sendMessageViaDataChannel(buf);
    }

    public void showSendDataChannelMessageDialog(View view) {
        if (webRTCClient != null && webRTCClient.isDataChannelEnabled()) {
            // create an alert builder
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Send Message via Data Channel");
            // set the custom layout
            final View customLayout = getLayoutInflater().inflate(R.layout.send_message_data_channel, null);
            builder.setView(customLayout);
            // add a button
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // send data from the AlertDialog to the Activity
                    EditText editText = customLayout.findViewById(R.id.message_text_input);
                    sendTextMessage(editText.getText().toString());
                    // sendDialogDataToActivity(editText.getText().toString());
                }
            });
            builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });
            // create and show the alert dialog
            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            Toast.makeText(this, R.string.data_channel_not_available, Toast.LENGTH_LONG).show();
        }
    }

    // For YUV conversion
    public Mat getYUV2Mat(byte[] data, int height, int width) {
        Mat mYuv = new Mat(height + height / 2, width, CV_8UC1);
        mYuv.put(0, 0, data);
        Mat mRGB = new Mat();
        cvtColor(mYuv, mRGB, Imgproc.COLOR_YUV2RGB_NV21, 3);
        return mRGB;
    }

    private void resumeMediapipe() {
        try {
            Log.d("SURFACE_CREATION", "Activity resume");
            newSurfaceView = new MyGL2SurfaceView(this);
            mediapipeController.setOutputSurface(newSurfaceView);
            setToListenEvent();
            mediapipeController.resume();
            //startSegmentation();
        } catch (Exception e) {

        }
    }

    public void setToListenEvent() {
        newSurfaceView.setTochListner(new GLTochListner() {
            @Override
            public void onTochEvent(MotionEvent event) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //onScreenTauch();
                    }//
                });
            }
        });
    }


    public void mediaPlay() {
//        try {
//            surfaceTexture = new MySurfaceTexture(42);
//            player = new NewDefaultPlayer();
//            player.setSurface(new Surface(surfaceTexture));
//            player.setDataSource("http://demo.unified-streaming.com/video/tears-of-steel/tears-of-steel.ism/.m3u8");
//            player.setLooping(true);
//            //player.setBufferEventInfoListner(bufferEventInfoListner);
//            player.prepare();
//            player.start();
//        } catch (Exception e) {
//            Log.d(TAG, e.toString());
//            e.printStackTrace();
//        }
    }


}
