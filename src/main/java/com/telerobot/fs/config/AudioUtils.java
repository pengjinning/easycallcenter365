package com.telerobot.fs.config;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

public class AudioUtils {

    /**
     *  get Duration of a wav file.
     * @param  wavFilePath
     * @return Return the duration of the recording file, in milliseconds.
     **/
    public  static long getWavFileDuration(String wavFilePath){
        try {
            File destFile = new File(wavFilePath);
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(destFile);
            AudioFormat format = audioInputStream.getFormat();
            long audioFileLength = destFile.length();
            int frameSize = format.getFrameSize();
            float frameRate = format.getFrameRate();
            return (long) ((audioFileLength / (frameSize * frameRate)) * 1000 );
        }catch (Throwable e){
            return 0L;
        }
    }

    public static void main(String[] args) {
       long duration =  getWavFileDuration("C:\\Users\\zhaohai\\Downloads\\zh.wav");
       System.out.println(duration);
    }

}
