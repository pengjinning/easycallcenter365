package com.telerobot.fs.config;

import com.telerobot.fs.entity.pojo.TtsFileInfo;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

public class AudioUtils {

    protected final static Logger logger = LoggerFactory.getLogger(AudioUtils.class);

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

    /**
     *  Concatenate multiple WAV file paths.
     * @return
     */
    public static TtsFileInfo joinTtsFiles(String ttsFiles){
        String traceId =  ThreadLocalTraceId.getInstance().getTraceId();
        StringBuilder ttsFileUnion = new StringBuilder();
        Long ttsFileTimeLenTmp = 0L;
        int ttsFileNumber = 0;
        if(!StringUtils.isEmpty(ttsFiles)){
            if(ttsFiles.contains(";")) {
                String[] fileArrs = ttsFiles.split(";");
                ttsFileUnion.append("file_string://");
                for (int i = 0; i <= fileArrs.length - 1; i++) {
                    if (!new File(fileArrs[i]).exists()) {
                        logger.error("{} The tts file {} does not exist and will be skipped. ", traceId, fileArrs[i]);
                    }else{
                        ttsFileNumber ++;
                        if(i != fileArrs.length - 1){
                            ttsFileUnion.append(fileArrs[i]).append("!");
                        }else{
                            ttsFileUnion.append(fileArrs[i]);
                        }
                        ttsFileTimeLenTmp += getWavFileDuration(fileArrs[i]);
                    }
                }
            }else{
                // only one single file
                if (!new File(ttsFiles).exists()) {
                    logger.error("{} The tts file {} does not exist and will be skipped. ", traceId, ttsFiles);
                }else {
                    ttsFileNumber ++;
                    ttsFileUnion.append(ttsFiles);
                    ttsFileTimeLenTmp += getWavFileDuration(ttsFiles);
                }
            }
        }
        if(ttsFileNumber > 0) {
            logger.info("{} Multiple WAV files have been successfully concatenated. The total duration of the merged file is {} milliseconds ",
                    traceId, ttsFileTimeLenTmp
            );
        }
        return new TtsFileInfo(ttsFileUnion.toString(), ttsFileTimeLenTmp, ttsFileNumber);
    }

    public static void main(String[] args) {
       long duration =  getWavFileDuration("C:\\Users\\zhaohai\\Downloads\\zh.wav");
       System.out.println(duration);
    }

}
