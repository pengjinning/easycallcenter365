package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.acd.AcdSqlQueue;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.global.BizThreadPoolForEsl;
import com.telerobot.fs.global.CdrPush;
import com.telerobot.fs.ivr.IvrEngine;
import com.telerobot.fs.service.AsrResultListener;
import com.telerobot.fs.utils.StringUtils;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferListener implements  IEslEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TransferListener.class);
    private InboundDetail inboundDetail;
    private String calleeUuid;
    private AccountBaseEntity account;
    private AtomicBoolean answered;
    private Semaphore continueSignal;
    private AtomicBoolean calleeHangup;
    private Semaphore playKeepMusicSignal = new Semaphore(1);

    public boolean getPlayKeepMusicSignal() {
        try {
          return playKeepMusicSignal.tryAcquire(2000, TimeUnit.MILLISECONDS);
        }catch (Throwable e){
        }
        return false;
    }

    public void releasePlayKeepMusicSignal() {
        playKeepMusicSignal.release();
    }


    public TransferListener(InboundDetail inboundDetail, String calleeUuid, AccountBaseEntity account,
                            AtomicBoolean answered, Semaphore continueSignal, AtomicBoolean calleeHangup) {
        this.inboundDetail = inboundDetail;
        this.calleeUuid = calleeUuid;
        this.account = account;
        this.answered = answered;
        this.continueSignal = continueSignal;
        this.calleeHangup = calleeHangup;
    }

    private Semaphore parkSemaphore = new Semaphore(0);
    private Semaphore callerParkSemaphore = new Semaphore(0);

    protected final String WAIT_WAV_FILE = "ivr/llm_wait.wav";
    private Semaphore playBackStartSignal = new Semaphore(0);
    private Semaphore playBackStoppedSignal = new Semaphore(0);
    public void playWaitMusic(){
        logger.info("{} playWaitMusic for call session. ", inboundDetail.getUuid());
        EslConnectionUtil.sendExecuteCommand(
                "playback",
                "$${sounds_dir}/" + WAIT_WAV_FILE,
                inboundDetail.getUuid()
        );
    }

    public boolean waitForPlayBackStoppedSignal() {
        try {
          return   playBackStoppedSignal.tryAcquire(1, 2000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
        }
        return false;
    }

    public boolean waitForPlayBackStartSignal() {
        try {
            return  playBackStartSignal.tryAcquire(1, 2000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
        }
        return false;
    }

    private void releasePlayBackStoppedSignal() {
        playBackStoppedSignal.release();
    }

    @Override
    public void eventReceived(String addr, EslEvent event) {
        logger.info("{} submitTask eventReceived: eventName={}, uuid={}", inboundDetail.getUuid(),
                event.getEventHeaders().get("Event-Name"), event.getEventHeaders().get("Unique-ID"));

        BizThreadPoolForEsl.submitTask(new Runnable() {
            @Override
            public void run() {
                processEslCallBack(addr, event);
            }
        });
    }

    private void breakWaitMusic(){
        boolean getAllowSignal = getPlayKeepMusicSignal();
        if(getAllowSignal) {
            long startTime = System.currentTimeMillis();
            int maxTry = 3;
            int tryCounter = 0;
            while (tryCounter < maxTry) {
                tryCounter++;
                EslConnectionUtil.sendSyncApiCommand("uuid_break", String.format("%s all", inboundDetail.getUuid()));
                if(waitForPlayBackStoppedSignal()){
                    break;
                }
            }
            logger.info("{} waitForPlayBackStoppedSignal took {} ms. ", inboundDetail.getUuid(), System.currentTimeMillis() - startTime);
            releasePlayKeepMusicSignal();
        }else{
            logger.error("{} getPlayKeepMusicSignal error!", inboundDetail.getUuid());
        }
    }

    public void processEslCallBack(String addr, EslEvent event) {
        Map<String, String> headers = event.getEventHeaders();
        String uniqueId = headers.get("Unique-ID");
        String eventName = headers.get("Event-Name");
        logger.info("{} eventName={}, uuid={}", inboundDetail.getUuid(), eventName, uniqueId);

        String playbackFilePath = headers.get("Playback-File-Path");
        if (EventNames.PLAYBACK_START.equalsIgnoreCase(eventName)) {
            if (playbackFilePath != null && playbackFilePath.endsWith(WAIT_WAV_FILE)) {
                playBackStartSignal.release();
            }
        }else if (EventNames.PLAYBACK_STOP.equalsIgnoreCase(eventName)) {
            if(!inboundDetail.getUuid().equalsIgnoreCase(uniqueId)) {
                return;
            }
            if (playbackFilePath != null && playbackFilePath.endsWith(WAIT_WAV_FILE)) {
                releasePlayBackStoppedSignal();
                logger.info("{} recv PLAYBACK_STOP event for wav file {}. ", inboundDetail.getUuid(), playbackFilePath);
                boolean getAllowSignal = getPlayKeepMusicSignal();
                if(getAllowSignal) {
                    if (inboundDetail.getManualAnsweredTime() == 0L) {
                        playWaitMusic();
                        waitForPlayBackStartSignal();
                    }
                    releasePlayKeepMusicSignal();
                }else{
                    logger.error("{} getPlayKeepMusicSignal error!", inboundDetail.getUuid());
                }
                return;
            }

        } else if (EventNames.CHANNEL_ANSWER.equalsIgnoreCase(eventName)) {
            if (calleeUuid.equalsIgnoreCase(uniqueId)) {
                try {
                    parkSemaphore.tryAcquire(1200L, TimeUnit.MILLISECONDS);
                } catch (Throwable e) {
                }

                inboundDetail.setManualAnsweredTime(System.currentTimeMillis());
                answered.set(true);
                continueSignal.release();

                breakWaitMusic();

                logger.info("{} callee answered, try to bridge call session.", inboundDetail.getUuid());
                String bridgeParam = calleeUuid + " " + inboundDetail.getUuid();
                EslMessage message = EslConnectionUtil.sendSyncApiCommand("uuid_bridge", bridgeParam);
                logger.info("{} call session bridge result: {}.", inboundDetail.getUuid(), JSON.toJSONString(message));
            }
        }
        if (EventNames.CHANNEL_PARK.equalsIgnoreCase(eventName)) {
            if (calleeUuid.equalsIgnoreCase(uniqueId)) {
                logger.info("{} recv callee park event={}", uniqueId, eventName);
                parkSemaphore.release();
            }
            if (inboundDetail.getUuid().equalsIgnoreCase(uniqueId)) {
                logger.info("{} recv caller park event={}", uniqueId, eventName);
                callerParkSemaphore.release();
            }
        } else if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
            if (uniqueId.equalsIgnoreCase(calleeUuid)) {
                logger.info("{} recv CHANNEL_HANGUP,  session {} hangup.", inboundDetail.getUuid(), calleeUuid);
                calleeHangup.set(true);

                // transfer call session to the IVR menu for satisfaction surveys
                if (answered.get()) {
                    String ivrPlanId = account.satisfSurveyIvrId;
                    if (!StringUtils.isNullOrEmpty(ivrPlanId)) {
                        logger.info("{} Try to start ivr process for satisfaction survey. ivrId={}.", inboundDetail.getUuid(), ivrPlanId);
                        try {
                            callerParkSemaphore.tryAcquire(2100L, TimeUnit.MILLISECONDS);
                        } catch (Throwable e) {
                        }
                        AppContextProvider.getBean(IvrEngine.class).startIvrSession(inboundDetail, ivrPlanId);
                    } else {
                        hangupCallSession(inboundDetail.getUuid(), "callee-hangup");
                    }
                }
            }

            continueSignal.release();

            if (uniqueId.equalsIgnoreCase(inboundDetail.getUuid())) {
                hangupCallSession(calleeUuid, "caller-hangup");
                inboundDetail.setHangup(true);
                if (inboundDetail.getAnsweredTime() > 0L) {
                    inboundDetail.setAnsweredTimeLen(System.currentTimeMillis() - inboundDetail.getAnsweredTime());
                }
                if (inboundDetail.getManualAnsweredTime() > 0L) {
                    inboundDetail.setManualAnsweredTimeLen(System.currentTimeMillis() - inboundDetail.getManualAnsweredTime());
                }
                inboundDetail.setTimeLen(System.currentTimeMillis() - inboundDetail.getInboundTime());
                inboundDetail.setHangupTime(System.currentTimeMillis());

                List<JSONObject> origDialogueList = inboundDetail.getChatContent();
                origDialogueList.addAll(AsrResultListener.getDialogueByUuid(inboundDetail.getUuid()));
                inboundDetail.setChatContent(origDialogueList);
                AcdSqlQueue.addToSqlQueue(inboundDetail);
                CdrDetail cdrDetail = new CdrDetail();
                cdrDetail.setUuid(inboundDetail.getUuid());

                if (inboundDetail.getOutboundPhoneInfo() == null) {
                    cdrDetail.setCdrType("inbound");
                } else {
                    cdrDetail.setCdrType("outbound");
                }
                // push cdr
                cdrDetail.setCdrBody(JSON.toJSONString(inboundDetail));
                CdrPush.addCdrToQueue(cdrDetail);
                logger.info("{} cdr has been pushed.", inboundDetail.getUuid());
            }
        }
    }

    @Override
    public void backgroundJobResultReceived(String addr, EslEvent event) {
    }

    @Override
    public String context() {
        return this.getClass().getName();
    }

    private static void hangupCallSession(String uuid, String reason) {
        EslConnectionUtil.sendExecuteCommand("hangup", reason, uuid);
    }
}
