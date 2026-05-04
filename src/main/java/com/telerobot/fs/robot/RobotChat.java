package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.acd.AcdSqlQueue;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.AudioUtils;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.ThreadLocalTraceId;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.bo.LlmConsumer;
import com.telerobot.fs.entity.dao.LlmKb;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.entity.po.HangupCause;
import com.telerobot.fs.entity.pojo.*;
import com.telerobot.fs.global.CdrPush;
import com.telerobot.fs.robot.impl.LocalWebApiTest;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.tts.aliyun.AliyunTTSWebApi;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.RegExp;
import com.telerobot.fs.utils.ThreadUtil;
import io.netty.util.internal.StringUtil;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.apache.commons.lang.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;

/**
 * @author easycallcenter365@126.com 
 */
public class RobotChat extends RobotBase {
    private static int initApp = init();
    private static int init() {
        int maxRobotNumber = Integer.parseInt(SystemConfig.getValue("max-call-concurrency")) ;
        startRobotMainThreadPool(maxRobotNumber);
        startFsEsNotifyThreadPool(maxRobotNumber);
        startRobotStatThread();
        return 1;
    }
    public static int getInit() {
        return initApp;
    }

    public RobotChat(InboundDetail callDetail, AccountBaseEntity llmAccountInfo) {
        this.uuid = callDetail.getUuid();
        ThreadLocalTraceId.getInstance().setTraceId(uuid);
        this.setAsrModelType(SystemConfig.getValue("robot-asr-type", ASR_TYPE_MRCP));
        logger.info("{} current robot_asr_type={}.",
                getTraceId(),  this.getAsrModelType()
        );
        logger.info("{} robot allow interrupt={}, interrupt_keywords={}, interrupt_ignore_keywords={}",
                getTraceId(),  llmAccountInfo.interruptFlag == 1, llmAccountInfo.interruptKeywords, llmAccountInfo.interruptIgnoreKeywords
        );
        this.callDetail = callDetail;

        getEslConnectionPool(
                uuid,
                SystemConfig.getValue("event-socket-ip"),
                Integer.parseInt(SystemConfig.getValue("event-socket-port"))
        );
        callTaskList.put(uuid, this);
        createChatBot(llmAccountInfo.provider);
        chatRobot.setAccount(llmAccountInfo);
        chatRobot.setCallDetail(callDetail);
        chatRobot.setTtsProvider(llmAccountInfo.voiceSource);
        chatRobot.setTtsVoiceName(llmAccountInfo.voiceCode);
        if(chatRobot instanceof LocalWebApiTest){
            ((LocalWebApiTest)chatRobot).makeMockData();
        }

        if(getAllowInterrupt() && ASR_TYPE_MRCP.equalsIgnoreCase(this.getAsrModelType())){
            logger.error("{} `robot-speech-interrupt-allowed`  is not effective in the mrcp speech recognition mode.", uuid);
        }

        EslConnectionUtil.sendExecuteCommand(
                "playback",
                "$${sounds_dir}/" + LLM_WAIT_WAV_FILE ,
                uuid
        );
        waitForPlayBackStartSignalForLlmConcurrency();

        long startTime = System.currentTimeMillis();
        logger.info("{} Try to acquire llm permit, llmAccountInfo.id={}, llmAccountInfo.concurrentNum={}.",
                getTraceId(),  llmAccountInfo.id, llmAccountInfo.concurrentNum);
        llmConsumer = new LlmConsumer(uuid, llmAccountInfo.id, llmAccountInfo.concurrentNum);
        LlmThreadManager.acquire(llmConsumer);
        logger.info("{} Acquire llm permit successfully, took {} milliseconds. ", getTraceId(),
                  System.currentTimeMillis() - startTime
        );
        int maxTry = 3;
        int tryCounter = 0;
        while(!checkSignalForLlmConcurrency() && tryCounter <= maxTry) {
            EslConnectionUtil.sendSyncApiCommand("uuid_break", uuid + " all");
            waitForPlayBackStoppedSignalForLlmConcurrency();
            tryCounter++;
        }
        callDetail.setAnsweredTime(System.currentTimeMillis());
        AcdSqlQueue.addToSqlQueue(callDetail);
    }



    public void startProcess(String uniqueID) {
        if(callDetail.getOutboundPhoneInfo() != null) {
            // In the outbound call scenario, solve the problem that the first few words of the first sentence
            // cannot be heard clearly, because it takes about 2 seconds for the customer to transfer from
            // the receiver to the headphones after answering the call.
            String key = "outbound-call-pause-time-mills-after-connected";
            int outboundCallPauseTimeMillsAfterConnected = Integer.parseInt(SystemConfig.getValue(
                    key, "200")
            );
            logger.info("{} wait {} mills after call connected. The value of this parameter comes from '{}' param.",
                    getTraceId(), outboundCallPauseTimeMillsAfterConnected, key);
            ThreadUtil.sleep(outboundCallPauseTimeMillsAfterConnected);
            if (isHangup) {
                return;
            }
        }

        String ttsProvider =  chatRobot.getAccount().voiceSource;
        String asrProvider =  chatRobot.getAccount().asrProvider;

        if(StringUtils.isEmpty(ttsProvider)){
            logger.warn("{} No TTS voice is currently configured for the robot. Sound playback will be handled by playing pre-synthesized TTS files.",
                    getTraceId());
        }
        if(StringUtils.isEmpty(asrProvider)){
            logger.error("{} asrProvider cant not be null, please check your speech-to-text configuration. ", getTraceId());
            hangupAndCloseConn("asrProvider-can-not-be-null");
            return;
        }

        if(ttsProvider.equalsIgnoreCase(TtsProvider.ALIYUN) || asrProvider.equalsIgnoreCase(AsrProvider.ALIYUN)) {
             if((!AliyunTTSWebApi.setAliyunTokenToFreeSWITCH(uuid))) {
                 String errMsg = "AliyunTTSWebApi getToken error!";
                logger.error("{} {} ", getTraceId(), errMsg);
                CommonUtils.setHangupCauseDetail(
                         callDetail,
                         HangupCause.TTS_ACCOUNT_INFO_INCORRECT,
                         "error msg:" + errMsg
                 );
                hangupAndCloseConn("AliyunTTSWebApi-getToken-error");
                return;
            }
        }

        if(ttsProvider.equalsIgnoreCase(TtsProvider.DOUBAO)) {
            String ttsModels = chatRobot.getAccount().ttsModels;
            logger.info("{}  Current tts provider is doubao, set ttsModels={}", getTraceId(), ttsModels);
        }
        if(ttsProvider.equalsIgnoreCase(TtsProvider.MICROSOFT)) {
            logger.info("{}  Current tts provider is microsoft!", getTraceId());
        }
        if(ttsProvider.equalsIgnoreCase(TtsProvider.CHINA_TELECOM)) {
            logger.info("{}  Current tts provider is china_telecom!", getTraceId());
        }

        String ttsModels = chatRobot.getAccount().ttsModels;
        String asrModels = chatRobot.getAccount().asrModels;
        String asrLanguageCode = chatRobot.getAccount().asrLanguageCode;
        String ttsLanguageCode = chatRobot.getAccount().ttsLanguageCode;
        logger.info("asr/tts info: ttsProvider={}, asrProvider={}, ttsModels={}, asrModels={}, asrLanguageCode={},  ttsLanguageCode={}",
                 ttsProvider, asrProvider, ttsModels, asrModels, asrLanguageCode, ttsLanguageCode
        );
        // set tts_models、asr_models、asr_language_code、tts_language_code
        EslConnectionUtil.sendExecuteCommand("set",
                "ecc365_tts_models=" + ttsModels,
                uuid
        );
        EslConnectionUtil.sendExecuteCommand("set",
                "ecc365_asr_models=" +  asrModels,
                uuid
        );
        EslConnectionUtil.sendExecuteCommand("set",
                "ecc365_asr_language_code=" + asrLanguageCode,
                uuid
        );
        EslConnectionUtil.sendExecuteCommand("set",
                "ecc365_tts_language_code=" + ttsLanguageCode,
                uuid
        );

        EslMessage apiResponseMsg = EslConnectionUtil.sendSyncApiCommand(
                "uuid_exists",
                uniqueID,
                this.eslConnectionPool
        );
        if (apiResponseMsg != null && apiResponseMsg.getBodyLines().size() != 0) {
            String apiResponseText = apiResponseMsg.getBodyLines().get(0);
            if ("false".equalsIgnoreCase(apiResponseText)) {
                logger.info("{} session is hangup, try to stop robot process.", getTraceId());
                this.processFsMsg(this.generateHangupEvent("hangup-before-robot-process"));
                return;
            }
        } else {
            logger.info("{} uuid_exists check error, can not get apiResponseMsg...", getTraceId());
        }

        logger.info("{} start robot Process...", getTraceId());
        startAsrProcess(getAsrModelType(), false);
        interactWithRobot();
    }



    protected void processFsMsgEx(Map<String, String> headers) {
        String eventName = headers.get("Event-Name");
        String eventDateTimestamp = headers.get("Event-Date-Timestamp");
        if(null != eventDateTimestamp) {
            // esl消息从产生到被处理的延迟时间; 毫秒数
            long eventTime = Long.parseLong(eventDateTimestamp) / 1000L;
            long timeDelay = System.currentTimeMillis() - eventTime;
            logger.info("{} The [{}] event takes {} ms from generation to processing.", getTraceId(), eventName, timeDelay);
        }

        String eventSubClass = headers.get("Event-Subclass");
        String playbackFilePath = headers.get("Playback-File-Path");
        String detail = headers.get("Ecc365-Event-Detail");
        logger.info("{}  Event-Name：{} ", getTraceId(), eventName);
        if(EventNames.PLAYBACK_START.equalsIgnoreCase(eventName)){

            if(EventNames.PLAYBACK_START.equalsIgnoreCase(detail)) {
                chatRobot.setTtsChannelState(TtsChannelState.OPENED);
                chatRobot.flushTtsRequestQueue();
                long timeSpent = System.currentTimeMillis() - playbackStartTime;
                logger.info("{} PLAYBACK_START event,  time cost = {} ms. ", getTraceId(), timeSpent);
            }

            if(StringUtils.isNotEmpty(playbackFilePath)){
                if(playbackFilePath.endsWith(LLM_WAIT_WAV_FILE)) {
                    releasePlayBackStartSignalForLlmConcurrency();
                    logger.info("{} recv PLAYBACK_START event for wav file {}. ", getTraceId(), playbackFilePath);
                }else{
                    logger.info("{} The currently playing file is {} . ", getTraceId(), playbackFilePath);
                }
            }
        }else if(EventNames.CHANNEL_PARK.equalsIgnoreCase(eventName))
        {
            logger.info("{} recv CHANNEL_PARK event. ", uuid);
        }
        else if (EventNames.PLAYBACK_STOP.equalsIgnoreCase(eventName)) {
            if(StringUtils.isNotEmpty(playbackFilePath)) {
                if (playbackFilePath.endsWith(LLM_WAIT_WAV_FILE)) {
                    releasePlayBackStoppedSignalForLlmConcurrency();
                    logger.info("{} recv PLAYBACK_STOP event for wav file {}. ", getTraceId(), playbackFilePath);
                }else {
                    logger.info("{} The playback of the file {} has completed. ", getTraceId(), playbackFilePath);
                    recvPlayBackEndEvent = true;
                    playbackEndTime = System.currentTimeMillis();
                    releasePlayBackFinishedSignal();
                }
            }

            if(EventNames.PLAYBACK_STOP.equalsIgnoreCase(detail)) {
                logger.info("{} ************ streaming tts playback finished.", getTraceId());
                if(!checkTtsLongConnection()) {
                    chatRobot.setTtsChannelState(TtsChannelState.CLOSED);
                }
                recvPlayBackEndEvent = true;
                playbackEndTime = System.currentTimeMillis();
                releasePlayBackFinishedSignal();
            }

            if(recvHangupSignal){
                 logger.info("{} The hang signal was received in the previous interaction process, and the call is about to hang up.",
                         getTraceId());
                hangupAndCloseConn("recvHangupSignal");
             }
        }else if (EventNames.DTMF.equalsIgnoreCase(eventName)) {
            // get the dtmf key to check if its value is the same as
            // the key configured for manual transferring in the system.
            String digit = headers.get("DTMF-Digit");
             logger.info("{} recv DTMF event, digit = {}", getTraceId(), digit);
             String transferManualDigit = chatRobot.getAccount().transferManualDigit;
             if(transferManualDigit.equalsIgnoreCase(digit)){
                 logger.info("{} DTMF digit equals transferManualDigit.", getTraceId());

                 if (recvPlayBackEndEvent || getAllowInterrupt()) {

                     if(!setTransferState()){
                         return;
                     }
                     logger.info("{} The digit-key during call have successfully activated the condition " +
                                        "for transferring to a human operator. recvPlayBackEndEvent={}, getAllowInterrupt()={} ",
                                 getTraceId(), recvPlayBackEndEvent, getAllowInterrupt()
                             );
                     if(getAllowInterrupt() && !recvPlayBackEndEvent) {
                         interruptRobotSpeech();
                         releasePlayBackFinishedSignal();
                         ThreadUtil.sleep(100);
                         closeTtsChannel("customer-interrupt");
                         if(StringUtils.isEmpty(chatRobot.getAccount().voiceSource)) {
                             ttsChannelClosed = true;
                         }

                         // wait for tts closed
                         int step = 50;
                         int maxWaitMills = 2000;
                         int counter = 0;
                         logger.info("{} wait for tts channel closed.", getTraceId());
                         while (!ttsChannelClosed && !isHangup && counter <= maxWaitMills) {
                             ThreadUtil.sleep(step);
                             counter += step;
                         }
                         if(!ttsChannelClosed){
                             ttsChannelClosed = true;
                             chatRobot.setTtsChannelState(TtsChannelState.CLOSED);
                             logger.warn("{}  We haven't received the event of the TTS channel being closed within two seconds, we consider it to have been closed.  .", getTraceId());
                         }else{
                             logger.info("{}  tts channel is closed.", getTraceId());
                         }
                     }

                     releaseSignal();

                     getRobotMainThreadPool().execute(new Runnable() {
                         @Override
                         public void run() {
                             doTransferToManualAgent(null);
                         }
                     });

                 }else {
                     logger.info("{} The digit-key during call have been failed to activate the condition " +
                                     "for transferring to a human operator. recvPlayBackEndEvent={}, getAllowInterrupt()={} ",
                             getTraceId(), recvPlayBackEndEvent, getAllowInterrupt()
                     );
                 }
             }
        }else if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
            if(isHangup){
                return;
            }
            releasePlayBackFinishedSignal();
            releaseSignal();
            isHangup = true;
            // 挂机后立即释放通道
            releaseThreadNum();
            releaseDtmf();
            displayNoVoiceNum();

            // 保存催记内容
            try {
                saveCdr(headers);
            } catch (Throwable e) {
                logger.info("{}  save cdr error: {}, {} ", getTraceId(), e.toString(),
                        CommonUtils.getStackTraceString(e.getStackTrace()));
            }

            if(!transferToAgent){
                CdrDetail cdrRecord = new CdrDetail();
                cdrRecord.setUuid(uuid);
                if(callDetail.getOutboundPhoneInfo() == null) {
                    cdrRecord.setCdrType("inbound");
                }else{
                    cdrRecord.setCdrType("outbound");
                }
                cdrRecord.setCdrBody(JSON.toJSONString(callDetail));
                CdrPush.addCdrToQueue(cdrRecord);
            }

        } else if ("CUSTOM".equalsIgnoreCase(eventName) && (
                "TtsEvent".equalsIgnoreCase(eventSubClass)
        )) {
           String event = headers.get("Tts-Event-Detail");
           if("Speech-Closed".equalsIgnoreCase(event)){
               chatRobot.setTtsChannelState(TtsChannelState.CLOSED);
               logger.info("{}  TtsChannelClosed = true.", getTraceId());
               ttsChannelClosed = true;
               releasePlayBackFinishedSignal();
           }
           if("Speech-Open".equalsIgnoreCase(event)){
               chatRobot.setTtsChannelState(TtsChannelState.OPENED);
               chatRobot.flushTtsRequestQueue();
               long timeSpent = System.currentTimeMillis() - playbackStartTime;
               logger.info("{} Speech-Open event,  time cost = {} ms. ", getTraceId(), timeSpent);
           }

            if ("NetworkError".equalsIgnoreCase(event)) {
                CommonUtils.setHangupCauseDetail(
                        callDetail,
                        HangupCause.TTS_SERVER_CONNECTED_FAILED,
                        headers.get("Error-Details")
                );
                logger.info("{} recv NetworkError event, hangup call session.", getTraceId());
                hangupAndCloseConn("Asr-TTs-NetworkError");
            }
        }
        else if ("CUSTOM".equalsIgnoreCase(eventName) && (
                "AsrEvent".equalsIgnoreCase(eventSubClass)
         )) {
            String speechEvent = headers.get("ASR-Event-Detail");
            String asrResponse = headers.get("Detect-Speech-Result");
            if (null != asrResponse) {
                asrResponse = headers.get("Detect-Speech-Result").trim();
            }

            if ("NetworkError".equalsIgnoreCase(speechEvent)) {
                CommonUtils.setHangupCauseDetail(
                        callDetail,
                        HangupCause.ASR_SERVER_CONNECTED_FAILED,
                        asrResponse
                );
                hangupAndCloseConn("Asr-Tts-NetworkError");
                return;
            }


            lastTalkTime = System.currentTimeMillis();

            if (isHangup || interactiveParam.checkInHangupState() ||  transferToAgent) {
                logger.info("{} Session is going to be hangup or is already being transferred to human operator, drop asr result: {}", getTraceId(), asrResponse);
                return;
            }

            if (!getAllowInterrupt() && !recvPlayBackEndEvent) {
                if ("vad".equalsIgnoreCase(speechEvent)) {
                    dropAsrCounter.incrementAndGet();
                    logger.info("{} (vad event) drop asr result: {}", getTraceId(), asrResponse);
                } else {
                    logger.info("{} (vad event) drop asr result: {}", getTraceId(), asrResponse);
                }
                return;
            }

            if ("middle".equalsIgnoreCase(speechEvent)) {
                logger.info("{}  ** asr-websocket, begin-speaking **  {}", getTraceId(), asrResponse);
                logger.info("{} recv asr middle result event, recvPlayBackEndEvent={}, allowInterrupt={}, !checkSpeaking={}",
                        getTraceId(),
                        recvPlayBackEndEvent,
                        getAllowInterrupt(),
                        !interactiveParam.checkInSpeaking()
                );
                if (recvPlayBackEndEvent || getAllowInterrupt()) {
                    if (!interactiveParam.checkInSpeaking()) {
                        String lockerKey = String.format("%s%s", uuid, "checkInSpeaking");
                        synchronized (lockerKey.intern()) {
                            if (!interactiveParam.checkInSpeaking()) {
                                interactiveParam.setInSpeaking(true);
                                // Main thread awakened to extend customer speaking time beyond 6 seconds.
                                logger.info("{} customer speech detected. ", getTraceId());

                                if (chatRobot.getAccount().interruptFlag == 2) {
                                    interruptRobotSpeech();
                                    releasePlayBackFinishedSignal();
                                    ThreadUtil.sleep(100);
                                }
                            }
                        }
                    }
                }
            } else if ("vad".equalsIgnoreCase(speechEvent)) {
                logger.info("{}  ** vad end-speaking:  {}", getTraceId(), asrResponse);
                interactiveParam.setInSpeaking(false);

                if(StringUtils.isEmpty(asrResponse)){
                    logger.error("{} error, vad result is null.", getTraceId());
                    return;
                }

                if (!StringUtil.isNullOrEmpty(asrResponse)) {
                    asrResultEx.add(asrResponse);
                }

                if(chatRobot.getAccount().interruptFlag == 1 && !recvPlayBackEndEvent) {
                    if (checkSpeechInterrupt(asrResponse)) {
                        interruptRobotSpeech();
                        releasePlayBackFinishedSignal();
                        ThreadUtil.sleep(100);
                    }else{
                        return;
                    }
                }

                if(recvPlayBackEndEvent || getAllowInterrupt()){
                    logger.info("{} releaseSignal for vad event.", getTraceId());
                    releaseSignal();
                }

            }
        }
    }

    private boolean setTransferState() {
        String lockerKey = String.format("%s%s", uuid, "setTransferState");
        synchronized (lockerKey.intern()) {
            if (transferToAgent) {
                logger.info("{} transferring to a human operator is already being handled. skip...", getTraceId());
                return false;
            }
            transferToAgent = true;
            return true;
        }
    }

    @Override
    protected void processFsMsg(Map<String, String> headers) {
        try {
            processFsMsgEx(headers);
        } catch (Throwable e) {
            logger.error("{} processFsMsg error: {}, {}", getTraceId(), e.toString(),
                    CommonUtils.getStackTraceString(e.getStackTrace()));
        }
    }

    protected void processFsMsg2(EslEvent event) {
        Map<String, String> headers = event.getEventHeaders();
        String eventName = headers.get("Event-Name");
        if (eventName.equalsIgnoreCase("DETECTED_SPEECH")) {
            String speechEvent = headers.get("Speech-Type");
            lastTalkTime = System.currentTimeMillis();

            if (isHangup || interactiveParam.checkInHangupState()) {
                logger.info("{} Session is going to be hangup, drop mrcp asr result: {}", getTraceId(),
                        headers.get("detect_speech_result"));
                return;
            }

            if ("begin-speaking".equalsIgnoreCase(speechEvent)) {
                if (!interactiveParam.checkInSpeaking()) {
                    logger.info(getTraceId() + " ** customer  begin-speaking detected. **");
                    // 用户开始讲话标识
                    interactiveParam.setInSpeaking(true);
                    releaseSignal(); // 唤醒主线程，让主线程可以超出6秒限制;
                }
            } else  if ("detected-partial-speech".equalsIgnoreCase(speechEvent)) {
                synchronized (getTraceId().intern()) {
                    if (!interactiveParam.checkInSpeaking()) {
                        // 用户开始讲话标识
                        interactiveParam.setInSpeaking(true);
                        // 唤醒主线程，让主线程可以超出6秒限制;
                        releaseSignal();
                    }
                }
                // 语音识别的中间结果;
                logger.info("{} detected-partial-speech = {}", getTraceId(), headers.get("detect_speech_result"));

            } else if ("detected-speech".equalsIgnoreCase(speechEvent)) {
                if (!interactiveParam.checkInSpeaking()) {
                    logger.info(getTraceId() + " mrcp return, no asr result got. isInSpeaking=false.");
                } else {
                    logger.info(getTraceId() + " ****** customer stop-speaking detected. ******");
                }
                interactiveParam.setInSpeaking(false);
                String speechResult = headers.get("detect_speech_result");
                if(StringUtils.isEmpty(speechResult)){
                    speechResult = CommonUtils.ListToString(event.getEventBodyLines(), false);
                }
                if (StringUtils.isEmpty(speechResult) || "Completion-Cause: 002".equalsIgnoreCase(speechResult.trim())) {
                    logger.info(getTraceId() + "  mrcp return, no asr result got...");
                    releaseSignal();
                    return;
                }

                if(speechResult.startsWith("<?xml")){
                    parseMrcpResultXmlStr(speechResult);
                    logger.info("{} detect_speech_result: {}", getTraceId(), speechResult);
                }else{
                    try {
                        String tmpResult = URLDecoder.decode(speechResult,"utf-8").replace(" ","");
                        asrResultEx.add(tmpResult);
                        logger.info("{} kaldi asr response: {}",getTraceId(), tmpResult);
                    } catch (Throwable e) {
                        logger.error("{} URLDecoder.decode Error: {}", getTraceId(), speechResult);
                    }
                }
                releaseSignal();
            }
        }
    }

    /**
     *  解析unimrcp-client接收到的xml字符串;
     * @param xmlStr
     */
    private void parseMrcpResultXmlStr(String xmlStr){
        SpeechResultEntity resultEntity = null;
        try {
            String input = URLDecoder.decode(xmlStr, "utf-8");
            resultEntity = getSpeechResult(input);
        } catch (UnsupportedEncodingException e) {
        }
        if (null != resultEntity) {
            if (!StringUtils.isEmpty(resultEntity.getResult())) {
                String asrResult = resultEntity.getResult();
                if(!StringUtil.isNullOrEmpty(asrResult)) {
                    asrResultEx.add(asrResult);
                }
                logger.info(getTraceId() + " mrcp asr result: {}, requestid: {}", resultEntity.getResult(), resultEntity.getRequestId());
            }
        } else {
            logger.info(getTraceId() + " cant not parse variable  detect_speech_result.");
        }
    }

    @Override
    public void eventReceived(String addr, EslEvent event) {
        fsEsNotifyThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, String> headers = event.getEventHeaders();
                boolean mrcpResult = EventNames.DETECTED_SPEECH.equalsIgnoreCase(headers.get("Event-Name"));
                if (getAsrModelType().equalsIgnoreCase(ASR_TYPE_MRCP) && mrcpResult) {
                    processFsMsg2(event);
                } else {
                    // output of FreeSWITCH websocket asr module
                    processFsMsg(headers);
                }
            }
        });
    }

    @Override
    public String context() {
        return this.getClass().getName();
    }

    @Override
    public void backgroundJobResultReceived(String addr, EslEvent event) {
    }


    /**
     * interactWithRobot
     **/
    private void interactWithRobot() {
        if (checkCallSession()) {
            return;
        }
        interactiveParam.setAllowInterrupt(0);
        recvPlayBackEndEvent = false;
        firstSpeak = false;
        interactiveParam.setInSpeaking(false);

        if(getAsrModelType().equalsIgnoreCase(ASR_TYPE_WEBSOCKET)) {
            pauseAsr();
        }

        // 送robot理解客户意图，返回合成后的语音文件路径
        StringBuilder asrStr = new StringBuilder();
        for (String result : asrResultEx) {
            asrStr.append(result);
        }

        // 清空 asrResultEx; 重新初始化字段;
        asrResultEx.clear();

        // 识别开始时间
        Long startTime = System.currentTimeMillis();
        LlmAiphoneRes aiphoneRes;

            try {
                String question = asrStr.toString();
                if (StringUtils.isEmpty(question)) {
                    int counter = noVoiceCounter.incrementAndGet();
                    if (counter > MAX_CONSECUTIVE_NO_VOICE_NUMBER) {
                        logger.info("{} There has been no sound for {} consecutive times. Play hangupTips and then hangup call.",
                                getTraceId(), MAX_CONSECUTIVE_NO_VOICE_NUMBER);
                        playSound(chatRobot.getAccount().hangupTips);
                        recvHangupSignal = true;
                        return;
                    }
                }else{
                    if(noVoiceCounter.get() > 1) {
                        noVoiceCounter.set(0);
                    }
                }

                boolean topicNotFound = kbQueryExecuted && LlmToolRequest.KB_QUERY_NOT_FOUND.equalsIgnoreCase(
                        chatRobot.getDialogues().get(chatRobot.getDialogues().size() - 1).getString("content")
                );
                if(!kbQueryExecuted  || (kbQueryExecuted && !topicNotFound) ) {
                    logger.info("{} send question to chatRobot: {}", getTraceId(), question);
                    aiphoneRes = chatRobot.talkWithAiAgent(question, kbQueryExecuted);
                    while ((aiphoneRes == null || aiphoneRes.getStatus_code() == 0)
                            && Llm_max_try_counter.get() < LLM_MAX_TRY) {
                        logger.error("{} llm api error, retry to send question to chatRobot: {}", getTraceId(), question);
                        aiphoneRes = chatRobot.talkWithAiAgent(question, kbQueryExecuted);
                        Llm_max_try_counter.incrementAndGet();
                        if (checkCallSession()) {
                            return;
                        }
                    }
                }else{
                    String topicNotFoundTips = chatRobot.getAccount().kbQueryTopicNotFoundTips;
                    if(StringUtils.isEmpty(topicNotFoundTips)){
                        topicNotFoundTips = "抱歉这个问题目前我无法回答，如果您有其他问题，我可以为您解答。";
                    }
                    aiphoneRes = new LlmAiphoneRes();
                    aiphoneRes.setStatus_code(1);
                    aiphoneRes.setClose_phone(0);
                    aiphoneRes.setIfcan_interrupt(0);
                    aiphoneRes.setStreamTtsText(topicNotFoundTips);
                    chatRobot.sendTtsRequest(topicNotFoundTips);
                    chatRobot.closeTts();
                }


                Llm_max_try_counter.set(0);
                kbQueryExecuted = false;

                if (aiphoneRes == null || aiphoneRes.getStatus_code() == 0) {
                    String llmTryFailedTips = chatRobot.getAccount().llmTryFailedTips;
                    if (!StringUtils.isEmpty(llmTryFailedTips)) {
                        playSound(llmTryFailedTips);
                    } else {
                        CommonUtils.setHangupCauseDetail(
                                callDetail,
                                HangupCause.LLM_API_SERVER_ERROR,
                                String.format("The large model failed to access successfully despite more than %d connection attempts.", LLM_MAX_TRY)
                        );
                        hangupAndCloseConn("reach-llm-max-try-error");
                    }
                    return;
                }

                talkRound.increment();
                Long spentCost = System.currentTimeMillis() - startTime;
                logger.info("{}  talkWithLargeModel spent time:  {}  ms, aiphoneRes = {}",
                        getTraceId(), spentCost, JSON.toJSONString(aiphoneRes)
                );


                if(aiphoneRes.getStatus_code() == 1) {
                    ttsChannelClosed = false;
                    String body = aiphoneRes.getBody();
                    if(!StringUtils.isEmpty(body)){
                        if(body.contains(LlmToolRequest.TRANSFER_TO_AGENT)){
                            aiphoneRes.setTransferToAgent(1);
                            body = body.replace(LlmToolRequest.TRANSFER_TO_AGENT, "");
                        }
                        if(body.contains(LlmToolRequest.HANGUP)){
                            aiphoneRes.setClose_phone(1);
                            body = body.replace(LlmToolRequest.HANGUP, "");
                        }
                        if(body.contains(LlmToolRequest.TRANSFER_TO_TEL)){
                            aiphoneRes.setTransferToAgent(1);
                        }
                        if(body.contains(LlmToolRequest.KB_QUERY + "=")){
                            kbQueryExecuted = true;
                            int catId =  chatRobot.getAccount().kbCatId;
                            String title = body.replace(LlmToolRequest.KB_QUERY + "=", "").replace(" ","");
                            LlmKb kb = AppContextProvider.getBean(SysService.class).getKbContentByCat(catId, title);
                            String response = LlmToolRequest.KB_QUERY_NOT_FOUND;
                            JSONObject userMessage = new JSONObject();
                            userMessage.put("role",  "system");

                            if(kb != null){
                                response = kb.getContent();
                                logger.info("{} 1 relevant topics {} were found: {} ", getTraceId(), title, response.substring(0, 10));
                                userMessage.put("content",  "kbQuery response=" + response);
                            }else{
                                logger.info("{} sorry, no topics '{}' found. ", getTraceId(), title);
                                userMessage.put("content",  response);
                            }

                            userMessage.put("content_type", "text");
                            chatRobot.getDialogues().add(userMessage);
                            interactWithRobot();
                            return;
                        }
                    }

                    if (checkCallSession()) {
                        return;
                    }

                    if (aiphoneRes.getTransferToAgent() == 1) {
                        if(!setTransferState()){
                            return;
                        }
                        doTransferToManualAgent(aiphoneRes);
                        return;
                    }

                    if (aiphoneRes.getClose_phone() == 1) {
                        if(StringUtils.isEmpty(body)){
                            playSound(chatRobot.getAccount().hangupTips);
                        }else{
                            playResponse(aiphoneRes);
                        }
                        waitForPlayBackFinished(11000);
                        closeTtsChannel("system-hangup");

                        long startTimeTick = System.currentTimeMillis();
                        if(StringUtils.isEmpty(chatRobot.getAccount().voiceSource)){
                            ttsChannelClosed = true;
                            ThreadUtil.sleep(2000);
                        }
                        while (!ttsChannelClosed && !isHangup) {
                            ThreadUtil.sleep(1000);
                            if(System.currentTimeMillis() - startTimeTick > 11000){
                                break;
                            }
                        }
                        hangupAndCloseConn("system-hangup");
                        return;
                    }

                    // play wav file
                    playResponse(aiphoneRes);
                }

            } catch (Throwable e) {
                logger.error("{} talkWithLargeModel error! {} {} ",
                        getTraceId(), e.toString(), CommonUtils.getStackTraceString(e.getStackTrace())
                );
                CommonUtils.setHangupCauseDetail(
                        callDetail,
                        HangupCause.SYSTEM_INTERNAL_ERROR,
                        String.format("server error: %s", e.toString())
                );
                hangupAndCloseConn(HangupCause.SYSTEM_INTERNAL_ERROR.getCode());
                return;
            }


        if(aiphoneRes.getIfcan_interrupt() == 1) {
            interactiveParam.setAllowInterrupt(1);
            logger.info("{} allowSpeechInterrupt={}", getTraceId(), 1);
        }

        if (!interactiveParam.checkInHangupState()) {
            if (aiphoneRes.getClose_phone() == 1) {
                logger.info(getTraceId() + " hangup signal is detected. ");
                interactiveParam.setInHangUpState(true);
                recvHangupSignal = true;
            } else {
                waitForCustomerSpeakEx();
            }
        }
    }

    private void  waitAndDetectSpeaking(){
        if (interactiveParam.checkInSpeaking()){
            logger.info("{} Speaking is detected, Wait for customer to finish speaking. Timeout: {} ",
                    getTraceId(),
                    maxWaitTimeMills
            );
            acquire(maxWaitTimeMills);
        }
    }

    private void playResponse(LlmAiphoneRes aiphoneRes){
        String ttsFilePathList = aiphoneRes.getTtsFilePathList();
        if(!StringUtils.isEmpty(ttsFilePathList)){
            TtsFileInfo ttsFileInfo = AudioUtils.joinTtsFiles(ttsFilePathList);
            logger.info("{} try to play wav file for text {}.", getTraceId(), aiphoneRes.getBody());
            startPlayback(ttsFileInfo);
        }
    }

    private  void doTransferToManualAgent(LlmAiphoneRes aiphoneRes){
        String audioTipsText = null;
        if(aiphoneRes != null) {
            audioTipsText = aiphoneRes.getBody();
        }
        String lockerKey = String.format("%s%s", uuid, "doTransferToManualAgent");
        synchronized (lockerKey.intern()) {
            if (transferToAgentExecuted) {
                logger.warn("{} The call transfer to a human agent has already been processed.", getTraceId());
                return;
            }
            transferToAgentExecuted = true;
        }

        callDetail.setChatContent(chatRobot.getDialogues());
        // Replace the prompt words for manual transfer in the text with blank spaces.
        String transferToTel = "";
        if(!StringUtils.isEmpty(audioTipsText) && audioTipsText.contains(LlmToolRequest.TRANSFER_TO_TEL)){
            if(!TransferToAgent.TRANSFER_TO_GATEWAY.equalsIgnoreCase(chatRobot.getAccount().aiTransferType)){
                logger.error("{} instruction `{}`  is only applicable when an external gateway is used to transfer to a manual agent.",
                        uuid, LlmToolRequest.TRANSFER_TO_TEL);
                hangupAndCloseConn("llm-instruction-error");
                return;
            }
            List<String> matches = RegExp.GetMatchFromStringByRegExp(audioTipsText, LlmToolRequest.TRANSFER_TO_TEL_REGEXP);
            for (String match : matches) {
                audioTipsText = audioTipsText.replace(match, "");

                List<String> tmp = RegExp.GetMatchFromStringByRegExp(match, "\\d{1,12}");
                transferToTel = tmp.get(0);
                logger.info("{} Successfully retrieved transferToTel number {}", uuid, transferToTel);

                JSONObject jsonObject = JSON.parseObject(chatRobot.getAccount().aiTransferData);
                jsonObject.put("destNumber", transferToTel);
                chatRobot.getAccount().aiTransferData = JSON.toJSONString(jsonObject);
                logger.info("{} Successfully update aiTransferData: {} ", uuid, chatRobot.getAccount().aiTransferData);
            }
        }

        if(StringUtils.isEmpty(audioTipsText) || audioTipsText.equalsIgnoreCase(LlmToolRequest.TRANSFER_TO_AGENT)){
            String tips = chatRobot.getAccount().transferToAgentTips;
            logger.info("{} Try to play tts  transferToAgentTips {}", getTraceId(), tips);
            if(!StringUtils.isEmpty(tips)) {
                playSound(tips);
                waitForPlayBackFinished(9000);
            }
            // wait for tips playback finished
        }else {
            playResponse(aiphoneRes);
            waitForPlayBackFinished(9000);
        }

        // stop_asr 的顺序很重要，需要放在播放tts之后，否则不起作用；会被uuid_break清空指令;
        logger.info("{} Try to stop asr {}", getTraceId(), chatRobot.getAccount().asrProvider);
        EslConnectionUtil.sendExecuteCommand(
                String.format("stop_%s_asr",  chatRobot.getAccount().asrProvider), "", uuid);

        ThreadUtil.sleep(200);

        if(!isHangup) {
            releaseThreadNum();
            TransferToAgent.transfer(callDetail, chatRobot.getAccount());
        }
    }

    /**
     * Check if the call has been hung up or has been transferred to a human handler.
     * @return
     */
    private boolean checkCallSession(){
        return isHangup || transferToAgent;
    }

    /**
     * Play TTS and wait for the customer to speak.
     */
    private void waitForCustomerSpeakEx() {
        if (checkCallSession()) {
            return;
        }

        logger.info("{} enter into waitForCustomerSpeak  ...", getTraceId());

        // The duration of streaming TTS playback should not exceed 181 seconds.
        if (!recvPlayBackEndEvent) {
            logger.info("{} enter into waitForPlayBackFinished  ...", getTraceId());
            waitForPlayBackFinished();
        }

        if (checkCallSession()) {
            return;
        }

        if (!recvPlayBackEndEvent) {
            logger.info("{} robot speech interrupt detected. ", getTraceId());
        } else {
            logger.info("{} robot speech playback finished. ", getTraceId());
        }

        if (getAsrModelType().equalsIgnoreCase(ASR_TYPE_WEBSOCKET)) {
            resumeAsr();
        }
        if (getAsrModelType().equalsIgnoreCase(ASR_TYPE_MRCP)) {
            startMrcp();
        }

        long startWaitTimeMills = System.currentTimeMillis();
        logger.info("{} wait for customer speaking  ...", getTraceId());

        Integer maxWaitTimeCustomerSpeaking = Integer.parseInt(SystemConfig.getValue("max-wait-time-customer-speaking", "7000")) ;
        acquire(maxWaitTimeCustomerSpeaking);

        logger.info("{} wait for customer speaking, time passed = {}ms.  ...",
                getTraceId(),
                System.currentTimeMillis() - startWaitTimeMills
        );

        if (checkCallSession()) {
            return;
        }


        logger.info("{} enter into waitAndDetectSpeaking  ...", getTraceId());
        // If speech is detected within 7 seconds, continue waiting.
        waitAndDetectSpeaking();

        logger.info(getTraceId() + " Robot main thread has woken up.");

        if (checkCallSession()) {
            return;
        }

        if (!interactiveParam.checkInSpeaking()) {
            // 前面的流程都正常，客户讲话有中间结果，且有最终的vad结果;
            // 根据vad结果产生不同的时间段，计算不同的应继续等待时间;
            long waitMills = calcWaitSecsDuration6Secs();
            if (waitMills > 100L) {
                logger.info("{} Wait another {} milliseconds to ensure the customer is finished speaking. ",
                        getTraceId(),
                        waitMills
                );
                acquire(waitMills);
                logger.info("{} enter into waitAndDetectSpeaking  ...", getTraceId());
                waitAndDetectSpeaking();
            }
        }else{
            // The customer's speech is not over yet.
            logger.info("{} Oh, it seems that the customer might still be speaking. ", uuid);
            while (interactiveParam.checkInSpeaking()){
               acquire(100);
            }
        }

        if (checkCallSession()) {
            return;
        }

        //如果没有接收到asr识别结果，则延迟下，继续等待0.5秒钟：
        if (asrResultEx.size() == 0) {
            acquire(500);
        }

        if (checkCallSession()) {
            return;
        }

        if (asrResultEx.size() == 0) {
            logger.info("{} No asr result got: NO_VOICE ", getTraceId());
        } else {
            calleeSpeakNumber.incrementAndGet();
        }
        interactRounds.incrementAndGet();

        int muteTimeLong = (int) (System.currentTimeMillis() - startWaitTimeMills);
        logger.info("{} The time spent waiting for the customer to finish speaking is {} ms.", getTraceId(), muteTimeLong);
        interactWithRobot();
    }


    /**
     * 显示本通电话的 No_Voice轮次，打印 语音识别连接是否成功信息;
     */
    private void displayNoVoiceNum() {
        String tips = "";
        if (calleeSpeakNumber.get() == 0) {
            tips = "No speech was detected all over the session.";
        }
        logger.info("{} {} calleeSpeakNumber:{}, interactRounds:{}, NO_VOICE_NUMBER:{}, dropAsrCounter:{}, wavFile: {}, recordings file exists：{}",
                getTraceId(),
                tips,
                calleeSpeakNumber.get(),
                interactRounds.get(),
                interactRounds.get() - calleeSpeakNumber.get(),
                dropAsrCounter.get(),
                this.recordingsFileName,
                interactRounds.get() != 0 ? new java.io.File(this.recordingsFileName).exists() : "No talk interaction."
        );
    }

    /**
     * saveCdr
     */
    private void saveCdr(Map<String, String> headers) {
        String hangupCause = headers.get("Hangup-Cause");
        String sipCode = headers.get("variable_proto_specific_hangup_cause");
        logger.info("{} session is hangup, hangupCause={}, sipCode={}", getTraceId(), hangupCause, sipCode);
        callDetail.setExtnum("robot");
        callDetail.setOpnum("robot");
        callDetail.setHangupTime(System.currentTimeMillis());
        callDetail.setChatContent(chatRobot.getDialogues());
        long timeLen = System.currentTimeMillis() - callDetail.getInboundTime();
        long answeredTimeLen = System.currentTimeMillis() - callDetail.getAnsweredTime();
        callDetail.setTimeLen(timeLen);
        callDetail.setAnsweredTimeLen(answeredTimeLen);
        if(StringUtils.isEmpty(chatRobot.getCallDetail().getHangupCause())){
            CommonUtils.setHangupCauseDetail(
                    callDetail,
                    hangupCause,
                    "sip-code=" + sipCode
            );
        }
        AcdSqlQueue.addToSqlQueue(callDetail);
    }

}
