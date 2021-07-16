package io.antmedia.webrtc_android_sample_app.mediapipe;

import android.media.MediaPlayer;

import java.lang.ref.WeakReference;

public class NewDefaultPlayer extends MediaPlayer {
   public interface  BufferEventInfoListner{
            void onBufferStart();
            void onBufferEnd();
    }

    public NewDefaultPlayer() {
       handleBufferingWorkaround(true);
    }

    private BufferingThread bThread;
   private BufferEventInfoListner bufferEventInfoListner;

    public void setBufferEventInfoListner(BufferEventInfoListner bufferEventInfoListner) {
        this.bufferEventInfoListner = bufferEventInfoListner;
    }

    public void OnEvent(int buffering_event) {
        if(bufferEventInfoListner==null){
            return;
        }
        if(buffering_event == PLAYER_EVENT.buffering_start){
            bufferEventInfoListner.onBufferStart();
        }
        else {
            bufferEventInfoListner.onBufferEnd();
        }
    }

    public int getPlayerState() {
        if(isPlaying()){
            return PLAYER_STATES.IS_PLAYING;
        }
        return 0;
    }
    private class BufferingThread extends Thread{

        WeakReference<NewDefaultPlayer> player;
        long currentPos, lastSecond;
        boolean isBuffering, alreadyBuffering;
        private Object locker = new Object();

        public BufferingThread(NewDefaultPlayer player) {
            this.player = new WeakReference<NewDefaultPlayer>(player);
        }

        private Runnable BufferStart = new Runnable() {
            public void run() {
                player.get().OnEvent(PLAYER_EVENT.buffering_start);
            }
        };
        private Runnable BufferStop = new Runnable() {
            public void run() {
                player.get().OnEvent(PLAYER_EVENT.buffering_end);
            }
        };
        @Override
        public void run() {
            super.run();

            try{

                while(true)
                {
                    synchronized (locker) {
                        currentPos = player.get().getCurrentPosition();
                        isBuffering = currentPos == 0 ? true : currentPos <= lastSecond;
                        lastSecond = (int) currentPos;
                        if(alreadyBuffering){
                            if(!isBuffering)
                            {
                                if(player.get().getPlayerState()==(PLAYER_STATES.IS_PLAYING))
                                {
                                    alreadyBuffering = isBuffering;
                                    player.get().OnEvent(PLAYER_EVENT.buffering_end);
                                    //TvinciSDK.getMainHandler().post(BufferStop);
                                }
                            }
                        } else {
                            if(isBuffering){
                                alreadyBuffering = isBuffering;
                                player.get().OnEvent(PLAYER_EVENT.buffering_start);
                                //TvinciSDK.getMainHandler().post(BufferStart);
                            }
                        }
                        try {
                            sleep(1500);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }

            }catch(Exception e)
            {

            }

        }
    }
    //Stop the thread if the user has stopped the video
    private void handleBufferingWorkaround(boolean work){
        if(bThread == null)
            bThread = new BufferingThread(this);
        if(work){
            if(!bThread.isAlive())
                bThread.start();
        }
        else if(bThread.isAlive())
        {
            bThread.interrupt();
            bThread = null;
        }
    }
}
