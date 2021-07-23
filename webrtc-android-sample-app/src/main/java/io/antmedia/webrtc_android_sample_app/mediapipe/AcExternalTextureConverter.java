package io.antmedia.webrtc_android_sample_app.mediapipe;



import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import com.google.mediapipe.components.TextureFrameConsumer;
import com.google.mediapipe.components.TextureFrameProducer;
import com.google.mediapipe.framework.AppTextureFrame;
import com.google.mediapipe.framework.TextureFrame;
import com.google.mediapipe.glutil.ExternalTextureRenderer;
import com.google.mediapipe.glutil.ShaderUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.microedition.khronos.egl.EGLContext;


/**
 * Textures from {@link SurfaceTexture} are only supposed to be bound to target {@link
 * GLES11Ext#GL_TEXTURE_EXTERNAL_OES}, which is accessed using samplerExternalOES in the shader.
 * This means they cannot be used with a regular shader that expects a sampler2D. This class creates
 * a copy of the texture that can be used with {@link GLES20#GL_TEXTURE_2D} and sampler2D.
 */
public class
AcExternalTextureConverter implements TextureFrameProducer {
    private static final String TAG = "ExternalTextureConv"; // Max length of a tag is 23.
    private static final int DEFAULT_NUM_BUFFERS = 2; // Number of output frames allocated.
    private static final String THREAD_NAME = "AcExternalTextureConverter";
    private long bgTimeStamp=0l;
    private long fgTimeStamp=0l;
    private RenderThread thread;

    /**
     * Creates the AcExternalTextureConverter to create a working copy of each camera frame.
     *
     * @param numBuffers the number of camera frames that can enter processing simultaneously.
     */
    public AcExternalTextureConverter(EGLContext parentContext, int numBuffers) {
        thread = new RenderThread(parentContext, numBuffers);
        thread.setName(THREAD_NAME);
        thread.start();
        try {
            thread.waitUntilReady();
        } catch (InterruptedException ie) {
            // Someone interrupted our thread. This is not supposed to happen: we own
            // the thread, and we are not going to interrupt it. Therefore, it is not
            // reasonable for this constructor to throw an InterruptedException
            // (which is a checked exception). If it should somehow happen that the
            // thread is interrupted, let's set the interrupted flag again, log the
            // error, and throw a RuntimeException.
            Thread.currentThread().interrupt();
            Log.e(TAG, "thread was unexpectedly interrupted: " + ie.getMessage());
            throw new RuntimeException(ie);
        }
    }

    public void setBgTimeStamp(long bgTimeStamp) {
        this.bgTimeStamp = bgTimeStamp;
    }

    public void setFgTimeStamp(long fgTimeStamp) {
        this.fgTimeStamp = fgTimeStamp;
        try {
            thread.setFgTimeStamp(fgTimeStamp);
        }catch (Exception e){ }
    }

    /**
     * Sets vertical flipping of the texture, useful for conversion between coordinate systems with
     * top-left v.s. bottom-left origins. This should be called before {@link
     * #setSurfaceTexture(SurfaceTexture, int, int)} or {@link
     * #setSurfaceTextureAndAttachToGLContext(SurfaceTexture, int, int)}.
     */
    public void setFlipY(boolean flip) {
        thread.setFlipY(flip);
    }

    public void onpause(){
        thread.onpause();
    }
    public void onresume(){
        thread.onresume();
    }

    public AcExternalTextureConverter(EGLContext parentContext) {
        this(parentContext, DEFAULT_NUM_BUFFERS);
    }

    public AcExternalTextureConverter(
            EGLContext parentContext, SurfaceTexture texture, int targetWidth, int targetHeight) {
        this(parentContext);
        thread.setSurfaceTexture(texture, targetWidth, targetHeight);
    }

    /**
     * Sets the input surface texture.
     *
     * <p>The provided width and height will be the size of the converted texture, so if the input
     * surface texture is rotated (as expressed by its transformation matrix) the provided width and
     * height should be swapped.
     */
    // TODO: Clean up setSurfaceTexture methods.
    public void setSurfaceTexture(SurfaceTexture texture, int width, int height) {
        if (texture != null && (width == 0 || height == 0)) {
            throw new RuntimeException(
                    "AcExternalTextureConverter: setSurfaceTexture dimensions cannot be zero");
        }
        thread.getHandler().post(() -> thread.setSurfaceTexture(texture, width, height));
    }

    // TODO: Clean up setSurfaceTexture methods.
    public void setSurfaceTextureAndAttachToGLContext(SurfaceTexture texture, int width, int height) {
        if (texture != null && (width == 0 || height == 0)) {
            throw new RuntimeException(
                    "AcExternalTextureConverter: setSurfaceTexture dimensions cannot be zero");
        }
        thread
                .getHandler()
                .post(() -> thread.setSurfaceTextureAndAttachToGLContext(texture, width, height));
    }

    public void setbGSurfaceTextureAndAttachToGLContext(SurfaceTexture texture, int width, int height){
        if(texture != null && (width == 0 || height == 0)){
            throw  new RuntimeException("AcExternalTextureConverter: setSurfaceTexture dimensions cannot be zero");

        }
        thread
                .getHandler()
                .post(()->thread.setbGSurfaceTextureAndAttachToGLContext(texture,width,height));
    }

    @Override
    public void setConsumer(TextureFrameConsumer next) {
        thread.setConsumer(next);
    }

    public void addConsumer(TextureFrameConsumer consumer) {
        thread.addConsumer(consumer);
    }

    public void removeConsumer(TextureFrameConsumer consumer) {
        thread.removeConsumer(consumer);
    }

    public void close() {
        if (thread == null) {
            return;
        }
        thread.getHandler().post(() -> thread.setSurfaceTexture(null, 0, 0));
        thread.quitSafely();
        try {
            thread.join();
        } catch (InterruptedException ie) {
            // Set the interrupted flag again, log the error, and throw a RuntimeException.
            Thread.currentThread().interrupt();
            Log.e(TAG, "thread was unexpectedly interrupted: " + ie.getMessage());
            throw new RuntimeException(ie);
        }
    }



    private static class RenderThread extends AcGlThread
            implements SurfaceTexture.OnFrameAvailableListener {
        private static final long NANOS_PER_MICRO = 1000; // Nanoseconds in one microsecond.
        private volatile SurfaceTexture surfaceTexture = null;
        private volatile SurfaceTexture bGSurfaceTexture = null;
        private final List<TextureFrameConsumer> consumers;
        private List<AppTextureFrame> outputFrames = null;
        private List<AppTextureFrame> bGoutputFrame = null;
        private int outputFrameIndex = -1;
        private int bGOutputFrameIndex = -1;
        private ExternalTextureRenderer renderer = null;
        private long timestampOffset = 0;
        private long previousTimestamp = 0;
        private boolean previousTimestampValid = false;

        protected int destinationWidth = 0;
        protected int destinationHeight = 0;
        protected int bGdestinationWidth =0;
        protected int bGdestinationHeight = 0;

        public RenderThread(EGLContext parentContext, int numBuffers) {
            super(parentContext);
            outputFrames = new ArrayList<>();
            outputFrames.addAll(Collections.nCopies(numBuffers, null));
            bGoutputFrame = new ArrayList<>();
            bGoutputFrame.addAll(Collections.nCopies(numBuffers,null));
            renderer = new ExternalTextureRenderer();
            consumers = new ArrayList<>();
        }

        public void setFlipY(boolean flip) {
            renderer.setFlipY(flip);
        }
        private boolean isPause=false;
        public void onpause(){
            isPause=true;
        }
        public void onresume(){
            isPause = false;
        }


        public void setSurfaceTexture(SurfaceTexture texture, int width, int height) {
            if (surfaceTexture != null) {
                surfaceTexture.setOnFrameAvailableListener(null);
            }
            surfaceTexture = texture;
            if (surfaceTexture != null) {
                surfaceTexture.setOnFrameAvailableListener(this);
            }
            destinationWidth = width;
            destinationHeight = height;
        }

        public void setbGSurfaceTexture(SurfaceTexture texture,int width,int height){
            if(bGSurfaceTexture!=null){
                bGSurfaceTexture.setOnFrameAvailableListener(null);
            }
            bGSurfaceTexture = texture;
            if(bGSurfaceTexture!=null){
                Log.d("BG_FRAME","seting up frame Listner");
                bGSurfaceTexture.setOnFrameAvailableListener(bGFrameAvailable);
            }
            bGdestinationHeight = height;
            bGdestinationWidth = width;
        }

        public void setSurfaceTextureAndAttachToGLContext(
                SurfaceTexture texture, int width, int height) {
            setSurfaceTexture(texture, width, height);
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            surfaceTexture.attachToGLContext(textures[0]);
        }

        public void setbGSurfaceTextureAndAttachToGLContext(SurfaceTexture textureAndAttachToGLContext, int width, int height){

            setbGSurfaceTexture(textureAndAttachToGLContext,width,height);
            int [] textures = new int[1];
                GLES20.glGenTextures(1,textures,0);

            textureAndAttachToGLContext.attachToGLContext(textures[0]);
        }

        public void setConsumer(TextureFrameConsumer consumer) {
            synchronized (consumers) {
                consumers.clear();
                consumers.add(consumer);
            }
            try{
                MultiInputFrameProcessor rpp = (MultiInputFrameProcessor)consumer;
                rpp.setConsumer(outputConsumer);
                Log.d("OUTPUT_CONSUMER_SETTED","consumer setup");

            }catch (Exception e){

            }
        }

        public void addConsumer(TextureFrameConsumer consumer) {
            synchronized (consumers) {
                consumers.add(consumer);
            }
        }

        public void removeConsumer(TextureFrameConsumer consumer) {
            synchronized (consumers) {
                consumers.remove(consumer);
            }
        }



        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            if(isPause)
                return;
            handler.post(() -> renderNext(surfaceTexture));
        }

        @Override
        public void prepareGl() {
            super.prepareGl();

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            renderer.setup();
        }

        @Override
        public void releaseGl() {
            for (int i = 0; i < outputFrames.size(); ++i) {
                teardownDestination(i);
            }
            renderer.release();
            super.releaseGl(); // This releases the EGL context, so must do it after any GL calls.
        }

        protected void renderNext(SurfaceTexture fromTexture) {
            Log.d(TAG,"New Frame available RenderNext");
            if (fromTexture != surfaceTexture) {
                Log.d(TAG,"New Frame available RenderNext returning");
                // Although the setSurfaceTexture and renderNext methods are correctly sequentialized on
                // the same thread, the onFrameAvailable callback is not. Therefore, it is possible for
                // onFrameAvailable to queue up a renderNext call while a setSurfaceTexture call is still
                // pending on the handler. When that happens, we should simply disregard the call.
                return;
            }

            try {
                synchronized (consumers) {
                    boolean frameUpdated = false;
                    for (TextureFrameConsumer consumer : consumers) {
                        AppTextureFrame outputFrame = nextOutputFrame();
                        AppTextureFrame bgOutputFrame = nextbGoutputFrame();
                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.
                        updateOutputFrame(outputFrame);
                        updateOutpuFrame(bgOutputFrame,outputFrame.getTimestamp());

                        //Updating Bg Frame with previos frame if New Frame is available Renderer will update.
                        /*if(isbGFrameAvailable){
                            updateOutpuFrame(bgOutputFrame,outputFrame.getTimestamp());
                            prevBgFrame = bgOutputFrame;
                        }
                        else {
                            if(prevBgFrame==null){
                                Log.d(TAG,"BG Frame is not yet available");
                                Log.d("BG_FRAME","BG Frame is not yet available");
                                return;
                            }
                            Log.d("BG_FRAME","Using previous frame");
                            prevBgFrame.setTimestamp(outputFrame.getTimestamp());
                            bgOutputFrame = prevBgFrame;
                        }*/

                        frameUpdated = true;

                        if (consumer != null) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(
                                        TAG,
                                        String.format(
                                                "Locking tex: %d width: %d height: %d",
                                                outputFrame.getTextureName(),
                                                outputFrame.getWidth(),
                                                outputFrame.getHeight()));
                            }
                            outputFrame.setInUse();
                            bgOutputFrame.setInUse();

                            try {
                                MultiInputFrameProcessor processor = (MultiInputFrameProcessor)consumer;
                                processor.onNewFrame(bgOutputFrame,outputFrame);
                            } catch (Exception e) {
                                Log.d("EXCEPTION_IN",e.toString());
                                e.printStackTrace();
                            }
                            //FrameProcessor processor = (FrameProcessor)consumer;
                            //processor.onNewFrame(outputFrame,bgOutputFrame);
                            //consumer.onNewFrame(outputFrame);

                        }
                    }
                    if (!frameUpdated) {  // Need to update the frame even if there are no consumers.
                        AppTextureFrame outputFrame = nextOutputFrame();
                        AppTextureFrame bgOutputFrame = nextbGoutputFrame();
                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.
                        updateOutputFrame(outputFrame);
                        if(isbGFrameAvailable){
                            updateOutpuFrame(bgOutputFrame,outputFrame.getTimestamp());
                            prevBgFrame = bgOutputFrame;
                        }
                        else {
                            if(prevBgFrame==null){
                                Log.d(TAG,"BG Frame is not yet available");
                                return;
                            }
                            prevBgFrame.setTimestamp(outputFrame.getTimestamp());
                            bgOutputFrame = prevBgFrame;
                        }
                    }
                }
            } finally {
            }
        }

        private void teardownDestination(int index) {
            if (outputFrames.get(index) != null) {
                waitUntilReleased(outputFrames.get(index));
                GLES20.glDeleteTextures(1, new int[] {outputFrames.get(index).getTextureName()}, 0);
                outputFrames.set(index, null);
            }
        }

        private void teardownbGDestination(int index){
            if(bGoutputFrame.get(index) != null){
                waitUntilReleased(bGoutputFrame.get(index));
                GLES20.glDeleteTextures(1,new int[]{bGoutputFrame.get(index).getTextureName()},0);
                bGoutputFrame.set(index,null);
            }
        }

        private void setupDestination(int index) {
            teardownDestination(index);
            int destinationTextureId = ShaderUtil.createRgbaTexture(destinationWidth, destinationHeight);
            Log.d(
                    TAG,
                    String.format(
                            "Created output texture: %d width: %d height: %d",
                            destinationTextureId, destinationWidth, destinationHeight));
            bindFramebuffer(destinationTextureId, destinationWidth, destinationHeight);
            outputFrames.set(
                    index, new AppTextureFrame(destinationTextureId, destinationWidth, destinationHeight));
        }

        private void setbGDestination(int index){
            teardownbGDestination(index);
            int bGdestinationTextureId = ShaderUtil.createRgbaTexture(bGdestinationWidth,bGdestinationHeight);
            Log.d(
                    TAG,
                    String.format(
                            "Created output texture: %d width: %d height: %d",
                            bGdestinationTextureId, bGdestinationWidth, bGdestinationHeight));
            bGbindFramebuffer(bGdestinationTextureId,bGdestinationWidth,bGdestinationHeight);
            bGoutputFrame.set(index,new AppTextureFrame(bGdestinationTextureId,bGdestinationWidth,bGdestinationHeight));
        }

        /**
         * Gets next available frame or creates new one if next frame is not initialized
         * or cannot be used with current surface texture.
         *
         * <ul>
         *  <li>Makes sure frame width and height are same as current surface texture</li>
         *  <li>Makes sure frame is not in use (blocks thread until frame is released)</li>
         * </ul>
         *
         * NOTE: must be invoked on GL thread
         */
        private AppTextureFrame nextOutputFrame() {
            outputFrameIndex = (outputFrameIndex + 1) % outputFrames.size();
            AppTextureFrame outputFrame = outputFrames.get(outputFrameIndex);
            // Check if the size has changed.
            if (outputFrame == null
                    || outputFrame.getWidth() != destinationWidth
                    || outputFrame.getHeight() != destinationHeight) {
                // setupDestination will wait for the frame to be released before reallocating it.
                setupDestination(outputFrameIndex);
                outputFrame = outputFrames.get(outputFrameIndex);
            }
            waitUntilReleased(outputFrame);
            return outputFrame;
        }

        private AppTextureFrame nextbGoutputFrame(){

            bGOutputFrameIndex =(bGOutputFrameIndex+1) % bGoutputFrame.size();

            AppTextureFrame bgOutputframe  = bGoutputFrame.get(bGOutputFrameIndex);
            if(bgOutputframe == null
                    || bgOutputframe.getWidth() != bGdestinationWidth
                    || bgOutputframe.getHeight()!=bGdestinationHeight){
                // setupDestination will wait for the frame to be released before reallocating it.
                setbGDestination(bGOutputFrameIndex);
                bgOutputframe = bGoutputFrame.get(bGOutputFrameIndex);
            }
            waitUntilReleased(bgOutputframe);
            return bgOutputframe;
        }

        /**
         * Updates output frame with current pixels of surface texture and corresponding timestamp.
         *
         * @param outputFrame {@link AppTextureFrame} to populate.
         *
         * NOTE: must be invoked on GL thread
         */
        long fgTimeStamp = 0;

        public void setFgTimeStamp(long fgTimeStamp) {
            this.fgTimeStamp = fgTimeStamp;
        }

        private void updateOutputFrame(AppTextureFrame outputFrame) {
            // Copy surface texture's pixels to output frame
            bindFramebuffer(outputFrame.getTextureName(), destinationWidth, destinationHeight);
            //renderer.render(bGSurfaceTexture);
            renderer.render(surfaceTexture);
            // Populate frame timestamp with surface texture timestamp after render() as renderer
            // ensures that surface texture has the up-to-date timestamp. (Also adjust |timestampOffset|
            // to ensure that timestamps increase monotonically.)
            long textureTimestamp = surfaceTexture.getTimestamp() / NANOS_PER_MICRO;
            textureTimestamp = ++fgTimeStamp;
            if (previousTimestampValid && textureTimestamp + timestampOffset <= previousTimestamp) {
                timestampOffset = previousTimestamp + 1 - textureTimestamp;
            }
            Log.d("TIME_STAMP_M","Time : "+(textureTimestamp));

            Log.d("TIME_STAMP_P","Time : "+(textureTimestamp + timestampOffset));
            outputFrame.setTimestamp(textureTimestamp + timestampOffset);
            previousTimestamp = outputFrame.getTimestamp();
            previousTimestampValid = true;
        }

        // Will update bG Frame if New Frame is available
        private void updateOutpuFrame(AppTextureFrame bGoutputFrame, long timestamp){
            bGbindFramebuffer(bGoutputFrame.getTextureName(),bGdestinationWidth,bGdestinationHeight);
            //renderer.render(surfaceTexture);
            try{

                renderer.render(bGSurfaceTexture);
                bGoutputFrame.setTimestamp(timestamp);
                isbGFrameAvailable = false;

            }catch (Exception e)
            {

            }

        }


        private void waitUntilReleased(AppTextureFrame frame) {
            try {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(
                            TAG,
                            String.format(
                                    "Waiting for tex: %d width: %d height: %d",
                                    frame.getTextureName(), frame.getWidth(), frame.getHeight()));

                }
                frame.waitUntilReleased();
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(
                            TAG,
                            String.format(
                                    "Finished waiting for tex: %d width: %d height: %d",
                                    frame.getTextureName(), frame.getWidth(), frame.getHeight()));
                }
            } catch (InterruptedException ie) {
                // Someone interrupted our thread. This is not supposed to happen: we own
                // the thread, and we are not going to interrupt it. If it should somehow
                // happen that the thread is interrupted, let's set the interrupted flag
                // again, log the error, and throw a RuntimeException.
                Thread.currentThread().interrupt();
                Log.e(TAG, "thread was unexpectedly interrupted: " + ie.getMessage());
                throw new RuntimeException(ie);
            }
        }


        private boolean isbGFrameAvailable = false;
        private AppTextureFrame prevBgFrame = null;
        private SurfaceTexture.OnFrameAvailableListener bGFrameAvailable=new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                //bG Frame is Available
                Log.d("BG_FRAME","OnBG Frame");
                isbGFrameAvailable = true;
            }
        };



        long prevTime = 0;
        boolean isProcessing = false;
        TextureFrameConsumer outputConsumer = new TextureFrameConsumer() {
            @Override
            public void onNewFrame(TextureFrame frame) {
                Log.d("OUTPUT_CONSUMER","Frame procceed");
                long currentTime = System.currentTimeMillis();
                Log.d("PIPELINE_TIME", ": "+(currentTime-prevTime));
                prevTime = currentTime;
                isProcessing = false;
                frame.release();
            }
        };



    }



}
