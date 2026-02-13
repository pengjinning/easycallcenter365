package com.telerobot.fs.utils;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.acd.AcdSqlQueue;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.global.CdrPush;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.constant.UuidKeys;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class RecordingsUtil {
    protected final static Logger logger = LoggerFactory.getLogger(RecordingsUtil.class);
    public static void startRecordings(String mediaFile, String uuid){
        Semaphore recordStartSignal = new Semaphore(0);
        IEslEventListener listener = new IEslEventListener() {
            private volatile boolean hangup = false;
            @Override
            public synchronized void eventReceived(String addr, EslEvent event) {
                Map<String, String> headers = event.getEventHeaders();
                String uniqueId = headers.get("Unique-ID");
                String eventName = headers.get("Event-Name");
                if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
                    hangup = true;
                    recordStartSignal.release();
                }else if(EventNames.RECORD_START.equalsIgnoreCase(eventName)) {
                    recordStartSignal.release();
                }else if(EventNames.RECORD_STOP.equalsIgnoreCase(eventName)) {
                    recordStartSignal.release();
                }
            }
            @Override
            public void backgroundJobResultReceived(String addr, EslEvent event) {
            }

            @Override
            public String context() {
                return RecordingsUtil.class.getName();
            }
        };

        String subscriberKey = uuid + UuidKeys.RECORDINGS;
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(subscriberKey, listener);
        String recordDir = SystemConfig.getValue("recording_path", "/home/Records/");
        logger.info("{} start record_session wav/mp4 {}{}", uuid, recordDir , mediaFile);
        EslConnectionUtil.sendExecuteCommand(
                "record_session",
                recordDir + mediaFile,
                uuid
        );
        long startTime = System.currentTimeMillis();
        try {
            recordStartSignal.tryAcquire(1, 1500, TimeUnit.MILLISECONDS);
        }catch (Throwable e){
            logger.error("{} wait for recordStartSignal error! ", uuid);
        }
        logger.info("{} wait  RECORD_START event, took {} ms.", uuid, System.currentTimeMillis() -  startTime);
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().removeListener(subscriberKey);
    }
}
