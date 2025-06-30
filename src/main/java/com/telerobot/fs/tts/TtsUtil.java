package com.telerobot.fs.tts;

import com.telerobot.fs.tts.aliyun.AliyunTTSWebApi;
import link.thingscloud.freeswitch.esl.CommonUtils;
import link.thingscloud.freeswitch.esl.util.CurrentTimeMillisClock;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;


public class TtsUtil {
    private final static Logger logger = LoggerFactory.getLogger(TtsUtil.class);


    /**
     *  Synthesize Speech
     * @param text
     * @param wavSavePath
     * @param voiceCode
     * @param voiceSource
     * @return
     */
    public static boolean synthesizeSpeech(String text, String wavSavePath, String voiceCode,  String voiceSource){
        if(!StringUtils.isNotEmpty(text)){
            logger.info("synthesizeSpeech error, text can not be null.");
            return false;
        }

        File audioFile = new File(wavSavePath);
        String destDir = audioFile.getParent();
        File destDirFile = new File(destDir);
        if(!destDirFile.exists()){
            synchronized (destDir.intern()) {
                if(!destDirFile.exists()) {
                    destDirFile.mkdirs();
                }
            }
        }

        boolean result = false;
        Long startTime = CurrentTimeMillisClock.now();
        if("aliyun_tts".equals(voiceSource)) {
            try {
                result = AliyunTTSWebApi.shortTextTTSWebAPI(voiceCode, text,  wavSavePath);
            }catch (Throwable e){
                logger.error(" AliyunTTSWebApi.shortTextTTSWebAPI error! voiceCode={}, wavSavePath={}, text={},  {} {}",
                        voiceCode, wavSavePath, text,
                        e.toString(),
                        CommonUtils.getStackTraceString(e.getStackTrace())
               );
            }
        } else
        if("azure".equals(voiceSource)) {

        }else{
            logger.error("unSupported tts source ：{}", voiceSource);
        }
        Long timeMills = CurrentTimeMillisClock.now() - startTime;

        logger.info("Synthesize Speech={}, tts engine={}, wavSavePath={}, took={} ms, success={}",
                text,
                voiceSource,
                wavSavePath,
                timeMills,
                result ? "true" : "false"
        );
        return result;
    }

}
