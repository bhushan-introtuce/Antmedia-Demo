// Copyright 2019 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.TextureFrameConsumer;
import com.google.mediapipe.components.TextureFrameProducer;
import com.google.mediapipe.framework.AppTextureFrame;
import com.google.mediapipe.framework.TextureFrame;
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
public class MpExternalTextureConverter implements TextureFrameProducer, CustomFrameAvailableListner, BGFrameAvailableListner {
    private static final String TAG = "ExternalTextureConv"; // Max length of a tag is 23.
    private static final int DEFAULT_NUM_BUFFERS = 2; // Number of output frames allocated.
    private static final String THREAD_NAME = "MpExternalTextureConverter";
    private long bgTimeStamp = 0l;
    private long fgTimeStamp = 0l;
    private RenderThread thread;

    /**
     * Creates the MpExternalTextureConverter to create a working copy of each camera frame.
     *
     * @param numBuffers the number of camera frames that can enter processing simultaneously.
     */
    public MpExternalTextureConverter(EGLContext parentContext, int numBuffers) {
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

    /**
     * Sets vertical flipping of the texture, useful for conversion between coordinate systems with
     * top-left v.s. bottom-left origins. This should be called before {@link
     * #setSurfaceTexture(SurfaceTexture, int, int)} or {@link
     * #setSurfaceTextureAndAttachToGLContext(SurfaceTexture, int, int)}.
     */
    public void setFlipY(boolean flip) {
        thread.setFlipY(flip);
    }

    public void setBgTimeStamp(long bgTimeStamp) {
        this.bgTimeStamp = bgTimeStamp;
    }

    public void setFgTimeStamp(long fgTimeStamp) {
        this.fgTimeStamp = fgTimeStamp;
        try {
            thread.setFgTimeStamp(fgTimeStamp);
        } catch (Exception e) {
        }
    }

    public MpExternalTextureConverter(EGLContext parentContext) {
        this(parentContext, DEFAULT_NUM_BUFFERS);
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
                    "MpExternalTextureConverter: setSurfaceTexture dimensions cannot be zero");
        }
        thread.getHandler().post(() -> thread.setSurfaceTexture(texture, width, height));
    }

    // TODO: Clean up setSurfaceTexture methods.
    public void setSurfaceTextureAndAttachToGLContext(SurfaceTexture texture, int width, int height) {
        if (texture != null && (width == 0 || height == 0)) {
            throw new RuntimeException(
                    "MpExternalTextureConverter: setSurfaceTexture dimensions cannot be zero");
        }
        thread
                .getHandler()
                .post(() -> thread.setSurfaceTextureAndAttachToGLContext(texture, width, height));

        Log.d("MP_FRM", "setting up setSurfaceTextureAndAttachToGLContext");
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


    @Override
    public void onFrame(Bitmap bitmap) {
        thread.onFrame(bitmap);
    }

    @Override
    public void onBGFrame(Bitmap bitmap) {

        thread.onBGFrame(bitmap);
    }

    private static class RenderThread extends MyGLThread implements SurfaceTexture.OnFrameAvailableListener, CustomFrameAvailableListner, BGFrameAvailableListner {
        private static final long NANOS_PER_MICRO = 1000; // Nanoseconds in one microsecond.
        private volatile SurfaceTexture surfaceTexture = null;
        private final List<TextureFrameConsumer> consumers;
        private List<AppTextureFrame> outputFrames = null;
        private int outputFrameIndex = -1;
        private MpExternalTextureRender renderer = null;
        private long timestampOffset = 0;
        private long previousTimestamp = 0;
        private boolean previousTimestampValid = false;

        protected int destinationWidth = 0;
        protected int destinationHeight = 0;

        public RenderThread(EGLContext parentContext, int numBuffers) {
            super(parentContext);
            outputFrames = new ArrayList<>();
            outputFrames.addAll(Collections.nCopies(numBuffers, null));
            renderer = new MpExternalTextureRender();
            consumers = new ArrayList<>();
        }

        public void setFlipY(boolean flip) {
            renderer.setFlipY(flip);
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

        public void setSurfaceTextureAndAttachToGLContext(
                SurfaceTexture texture, int width, int height) {
            Log.d("MP_FRM", "setting up 196");
            setSurfaceTexture(texture, width, height);
            int[] textures = new int[1];
            GLES20.glGenTextures(0, textures, 0);
            surfaceTexture.attachToGLContext(textures[0]);
        }

        public void setConsumer(TextureFrameConsumer consumer) {
            synchronized (consumers) {
                consumers.clear();
                consumers.add(consumer);

                try {
                    FrameProcessor rpp = (FrameProcessor) consumer;
                    rpp.setConsumer(outputConsumer);
                    Log.d("OUTPUT_CONSUMER_SETTED", "consumer setup");

                } catch (Exception e) {

                }

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
            Log.d("FRAME_AVL", "onFrameAvailable");
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

        protected void renderNext() {
            if (isProcessing) {
                Log.d("MP_FRM", "still processing");
                Log.d("OUTPUT_CONSUMER", "still processing");
                return;
            }

            try {
                Log.d("OUTPUT_CONSUMER", "starting next process");
                Log.d("MP_FRM", "render next 246");
                synchronized (consumers) {
                    Log.d("MP_FRM", "render next");
                    boolean frameUpdated = false;
                    for (TextureFrameConsumer consumer : consumers) {
                        AppTextureFrame[] frames = nextOutputFrame(2);
                        updateOutputFrame(frames);
                        AppTextureFrame foreground = frames[0];
                        AppTextureFrame background = frames[1];
                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.


                        frameUpdated = true;

                        if (consumer != null) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(
                                        TAG,
                                        String.format(
                                                "Locking tex: %d width: %d height: %d",
                                                foreground.getTextureName(),
                                                foreground.getWidth(),
                                                foreground.getHeight()));
                            }
                            isProcessing = true;
                            prevTime = System.currentTimeMillis();
                            foreground.setInUse();
                            try {

                                MultiInputFrameProcessor processor = (MultiInputFrameProcessor) consumer;
                                processor.onNewFrame(foreground, background);
                            } catch (Exception e) {
                                Log.d("EXCEPTION_IN", e.toString());
                                e.printStackTrace();
                            }
                            //consumer.onNewFrame(foreground,background);
                        }
                    }
                    if (!frameUpdated) {  // Need to update the frame even if there are no consumers.
                        AppTextureFrame[] outputFrames = nextOutputFrame(2);
                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.
                        updateOutputFrame(outputFrames);
                    }
                }
            } finally {
            }
        }

        protected void renderNext(SurfaceTexture from) {
            if (from != surfaceTexture) {
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

                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.
                        updateOutputFrame(outputFrame);
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
                            isProcessing = true;
                            consumer.onNewFrame(outputFrame);

                        }
                    }
                    if (!frameUpdated) {  // Need to update the frame even if there are no consumers.
                        AppTextureFrame outputFrame = nextOutputFrame();
                        // TODO: Switch to ref-counted single copy instead of making additional
                        // copies blitting to separate textures each time.
                        updateOutputFrame(outputFrame);
                    }
                }
            } finally {
            }
        }

        private void teardownDestination(int index) {
            if (outputFrames.get(index) != null) {
                waitUntilReleased(outputFrames.get(index));
                GLES20.glDeleteTextures(1, new int[]{outputFrames.get(index).getTextureName()}, 0);
                // Log.d("OutputFrame ","TearDown: "+ outputFrames.get(index).getTextureName());
                outputFrames.set(index, null);
            }
        }

        private void setupBgDestination(int index) {

            teardownDestination(index);
            int destinationTextureId = createBgRgbaTexture(destinationWidth, destinationHeight);//createRgbaTexture(destinationWidth,destinationHeight);//ShaderUtil.createRgbaTexture(destinationWidth, destinationHeight);
            //int bgdestinationTextureId = createBgRgbaTexture(destinationWidth,destinationHeight);

            Log.d(
                    TAG,
                    String.format(
                            "Created output texture: %d width: %d height: %d",
                            destinationTextureId, destinationWidth, destinationHeight));
            Log.d("FRAME_BUFFER_OBJ", String.format("Created output texture: %d width: %d height: %d",
                    destinationTextureId, destinationWidth, destinationHeight));
            //bindFramebuffer(destinationTextureId, destinationWidth, destinationHeight);
            // bindFrameBuffer(destinationTextureId,destinationWidth,destinationHeight);

            outputFrames.set(
                    index, new AppTextureFrame(destinationTextureId, destinationWidth, destinationHeight));
        }

        private void setupDestination(int index) {
            teardownDestination(index);
            int destinationTextureId = createRgbaTexture(destinationWidth, destinationHeight);//ShaderUtil.createRgbaTexture(destinationWidth, destinationHeight);
            //int bgdestinationTextureId = createBgRgbaTexture(destinationWidth,destinationHeight);

            Log.d(
                    TAG,
                    String.format(
                            "Created output texture: %d width: %d height: %d",
                            destinationTextureId, destinationWidth, destinationHeight));
            Log.d("FRAME_BUFFER_OBJ", String.format("Created output texture: %d width: %d height: %d",
                    destinationTextureId, destinationWidth, destinationHeight));
            //bindFramebuffer(destinationTextureId, destinationWidth, destinationHeight);
            // bindFrameBuffer(destinationTextureId,destinationWidth,destinationHeight);

            outputFrames.set(
                    index, new AppTextureFrame(destinationTextureId, destinationWidth, destinationHeight));
        }

        public static int createRgbaTexture(int width, int height) {

            final int[] textureName = new int[]{0};

            GLES20.glGenTextures(1, textureName, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureName[0]);
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    width, height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null);

            ShaderUtil.checkGlError("glTexImage2D");
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            ShaderUtil.checkGlError("texture setup");
            Log.d("TEXT_NAME", "Name: Texture cteated " + textureName[0]);
            return textureName[0];
        }

        public static int createBgRgbaTexture(int width, int height) {

            final int[] textureName = new int[]{0};

            GLES20.glGenTextures(1, textureName, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureName[0]);
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    width, height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null);

            ShaderUtil.checkGlError("glTexImage2D");
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            ShaderUtil.checkGlError("texture setup");
            Log.d("TEXT_NAME", "Name: Texture cteated " + textureName[0]);
            return textureName[0];
        }


        /**
         * Gets next available frame or creates new one if next frame is not initialized
         * or cannot be used with current surface texture.
         *
         * <ul>
         *  <li>Makes sure frame width and height are same as current surface texture</li>
         *  <li>Makes sure frame is not in use (blocks thread until frame is released)</li>
         * </ul>
         * <p>
         * NOTE: must be invoked on GL thread
         */

        private AppTextureFrame[] nextOutputFrame(int n) {
            outputFrameIndex = (outputFrameIndex + 1) % outputFrames.size();
            AppTextureFrame[] frames = new AppTextureFrame[2];
            frames[0] = outputFrames.get(outputFrameIndex);
            int mpoutputFrameIndex = (outputFrameIndex + 1) % outputFrames.size();
            frames[1] = outputFrames.get(mpoutputFrameIndex);

            if (frames[0] == null
                    || frames[0].getWidth() != destinationWidth
                    || frames[0].getHeight() != destinationHeight) {
                // setupDestination will wait for the frame to be released before reallocating it.
                // Log.d(TAG,"371 "+outputFrame.getTimestamp());
                setupDestination(outputFrameIndex);
                frames[0] = outputFrames.get(outputFrameIndex);
            }

            if (frames[1] == null || frames[1].getWidth() != destinationWidth
                    || frames[1].getHeight() != destinationHeight) {

                setupBgDestination(mpoutputFrameIndex);
                frames[1] = outputFrames.get(mpoutputFrameIndex);
            }
//            bindFrameBuffer(frames[0].getTextureName(), frames[1].getTextureName(), destinationWidth, destinationHeight);
            bindFrameBuffer(frames[0].getTextureName(), frames[1].getTextureName(), destinationWidth, destinationHeight);
            waitUntilReleased(frames[0]);
            waitUntilReleased(frames[1]);
            outputFrameIndex = mpoutputFrameIndex;
            return frames;

        }


        private AppTextureFrame nextOutputFrame() {
            outputFrameIndex = (outputFrameIndex + 1) % outputFrames.size();
            AppTextureFrame outputFrame = outputFrames.get(outputFrameIndex);
            // Check if the size has changed.
            if (outputFrame == null
                    || outputFrame.getWidth() != destinationWidth
                    || outputFrame.getHeight() != destinationHeight) {
                // setupDestination will wait for the frame to be released before reallocating it.
                // Log.d(TAG,"371 "+outputFrame.getTimestamp());
                setupDestination(outputFrameIndex);
                outputFrame = outputFrames.get(outputFrameIndex);
            }
            waitUntilReleased(outputFrame);
            return outputFrame;
        }

        /**
         * Updates output frame with current pixels of surface texture and corresponding timestamp.
         * <p>
         * // * @param outputFrame {@link AppTextureFrame} to populate.
         * <p>
         * NOTE: must be invoked on GL thread
         */

        long fgTimeStamp = 0;

        public void setFgTimeStamp(long fgTimeStamp) {
            this.fgTimeStamp = fgTimeStamp;
        }

        private void updateOutputFrame(AppTextureFrame[] outputFrames) {

            bindFrameBuffer(outputFrames[0].getTextureName(), outputFrames[1].getTextureName(), destinationWidth, destinationHeight);

            Log.d("FRAME_BUFFER_OBJ", String.format("Created output texture: %d width: %d height: %d",
                    outputFrames[0].getTextureName(), destinationWidth, destinationHeight));


            renderer.render(outputFrames[0].getTextureName(), outputFrames[1].getTextureName());
            //renderer.render(surfaceTexture);
            long textureTimestamp = ++fgTimeStamp;
            if (previousTimestampValid && textureTimestamp + timestampOffset <= previousTimestamp) {
                timestampOffset = previousTimestamp + 1 - textureTimestamp;
            }
            Log.d("TIME_STAMP_M", "Time : " + (textureTimestamp));
            Log.d("TIME_STAMP_P", "Time : " + (textureTimestamp + timestampOffset));
            outputFrames[0].setTimestamp(textureTimestamp + timestampOffset);
            outputFrames[1].setTimestamp(textureTimestamp + timestampOffset);
            //Log.d("TIME_STAMP",""+(textureTimestamp+timestampOffset));
            previousTimestamp = outputFrames[0].getTimestamp();
            previousTimestampValid = true;
        }

        private void updateOutputFrame(AppTextureFrame outputFrame) {
            //Log.d("MDEG","updateOutputFrame Thread Name "+Thread.currentThread().getName());
            // Copy surface texture's pixels to output frame

            bindFramebuffer(outputFrame.getTextureName(), destinationWidth, destinationHeight);
            Log.d("FRAME_BUFFER_OBJ", String.format("Created output texture: %d width: %d height: %d",
                    outputFrame.getTextureName(), destinationWidth, destinationHeight));

            renderer.render(outputFrame.getTextureName(), 1);
            //renderer.onDrawFrame(outputFrame.getTextureName());
            //renderer.render(surfaceTexture);
            // Populate frame timestamp with surface texture timestamp after render() as renderer
            // ensures that surface texture has the up-to-date timestamp. (Also adjust |timestampOffset|
            // to ensure that timestamps increase monotonically.)
            if (surfaceTexture == null) {
                surfaceTexture = new SurfaceTexture(55);
            }
            long textureTimestamp = surfaceTexture.getTimestamp() / NANOS_PER_MICRO;
            textureTimestamp = ++fgTimeStamp;
            if (previousTimestampValid && textureTimestamp + timestampOffset <= previousTimestamp) {
                timestampOffset = previousTimestamp + 1 - textureTimestamp;
            }
            Log.d("TIME_STAMP_M", "Time : " + (textureTimestamp));
            Log.d("TIME_STAMP_P", "Time : " + (textureTimestamp + timestampOffset));
            outputFrame.setTimestamp(textureTimestamp + timestampOffset);
            //Log.d("TIME_STAMP",""+(textureTimestamp+timestampOffset));

            previousTimestamp = outputFrame.getTimestamp();
            previousTimestampValid = true;
            surfaceTexture.updateTexImage();
        }

        private void updateOutputFrame(CustomAppTextureFrame outputFrame) {
            //Log.d("MDEG","updateOutputFrame Thread Name "+Thread.currentThread().getName());
            //Copy surface texture's pixels to output frame
            //bindFramebuffer(outputFrame.getTextureName(), destinationWidth, destinationHeight);
            bindFrameBuffer(outputFrame.getTextureName(), outputFrame.getBGTextureName(), destinationWidth, destinationHeight);
            Log.d("FRAME_BUFFER_OBJ", String.format("Created output texture: %d width: %d height: %d at CustomAppTextureFrame",
                    outputFrame.getTextureName(), destinationWidth, destinationHeight));

            renderer.render(outputFrame.getTextureName(), outputFrame.getBGTextureName());
            //renderer.onDrawFrame(outputFrame.getTextureName());
            //renderer.render(surfaceTexture);

            // Populate frame timestamp with surface texture timestamp after render() as renderer
            // ensures that surface texture has the up-to-date timestamp. (Also adjust |timestampOffset|
            // to ensure that timestamps increase monotonically.)

            if (surfaceTexture == null) {
                surfaceTexture = new SurfaceTexture(55);

            }
            long textureTimestamp = surfaceTexture.getTimestamp() / NANOS_PER_MICRO;
            textureTimestamp = ++fgTimeStamp;
            if (previousTimestampValid && textureTimestamp + timestampOffset <= previousTimestamp) {
                timestampOffset = previousTimestamp + 1 - textureTimestamp;
            }

            Log.d("TIME_STAMP_M", "Time : " + (textureTimestamp));

            Log.d("TIME_STAMP_P", "Time : " + (textureTimestamp + timestampOffset));
            outputFrame.setTimestamp(textureTimestamp + timestampOffset);
            //Log.d("TIME_STAMP",""+(textureTimestamp+timestampOffset));

            previousTimestamp = outputFrame.getTimestamp();
            previousTimestampValid = true;
            surfaceTexture.updateTexImage();
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
                //Someone interrupted our thread. This is not supposed to happen: we own
                //the thread, and we are not going to interrupt it. If it should somehow
                //happen that the thread is interrupted, let's set the interrupted flag
                //again, log the error, and throw a RuntimeException.
                Thread.currentThread().interrupt();
                Log.e(TAG, "thread was unexpectedly interrupted: " + ie.getMessage());
                throw new RuntimeException(ie);
            }
        }


        @Override
        public void onFrame(Bitmap bitmap) {
            try {
                Log.d("FRAME_TES", "OnFrame");

                handler.post(() -> {
                    Log.d("FRAME_TES", "OnFrame inside handler");
                    try {
                        destinationWidth = bitmap.getWidth();
                        destinationHeight = bitmap.getHeight();
                        renderer.displayFrame(bitmap);
                        renderNext();
                    } catch (Exception e) {
                        Log.d(TAG, e.toString());
                    }

                });
            } catch (Exception e) {

            }
        }

        @Override
        public void onBGFrame(Bitmap bitmap) {
            try {
                handler.post(() -> {
                    renderer.updateBgFrame(bitmap);
                });
            } catch (Exception e) {

            }

        }

        long prevTime = 0;
        boolean isProcessing = false;
        TextureFrameConsumer outputConsumer = new TextureFrameConsumer() {
            @Override
            public void onNewFrame(TextureFrame frame) {
                Log.d("", "Frame procceed");
                long currentTime = System.currentTimeMillis();
                Log.d("PIPELINE_TIME", ": " + (currentTime - prevTime));
                //prevTime = currentTime;
                isProcessing = false;
                frame.release();
            }
        };

    }
}
