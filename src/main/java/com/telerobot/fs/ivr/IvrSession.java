package com.telerobot.fs.ivr;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.utils.RegExp;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.utils.ThreadUtil;
import com.telerobot.fs.wshandle.MessageResponse;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IVR Session Class, manages IVR state for a single call
 * (Default built-in support. To return to the previous level, press #. To re-listen, press *)
 */
public class IvrSession {
    private String sessionId;
    private String callerId;
    private String calleeId;
    private IvrPlan currentPlan;
    private IvrNode currentNode;
    private AtomicInteger pressKeyFailures;
    private ConcurrentHashMap<String, Object> sessionData;
    private long createTime;
    private long lastActiveTime;
    private String ttsProvider;
    private String voiceCode;
    private InboundDetail callDetail;

    private final static Logger logger = LoggerFactory.getLogger(IvrSession.class);
    private String getTraceId(){
        return sessionId;
    }

    public InboundDetail getCallDetail() {
        return callDetail;
    }

    protected class EslListener implements IEslEventListener{
        private volatile boolean playBackInterrupted = false;
        private volatile boolean ivrProcessStarted = false;
        private volatile boolean streamTtsChannelOpened = false;
        private volatile boolean inputFinished = false;
        private volatile boolean inputValidateSuccess = false;
        private volatile String lastPlaybackType = "";
        private volatile boolean  returnToPreviousNode = false;
        private volatile boolean  reListen = false;

        private Semaphore dtmfInputCompletedSignal = new Semaphore(0);
        private Semaphore ttsChannelOpenSignal = new Semaphore(0);
        private Semaphore ttsChannelCloseSignal = new Semaphore(0);
        private Semaphore wavPlaybackFinishedSignal = new Semaphore(0);
        private Semaphore[] signalList = {
                dtmfInputCompletedSignal,
                ttsChannelOpenSignal,
                ttsChannelCloseSignal,
                wavPlaybackFinishedSignal
        };
        private ArrayList<String> dtmfDigitCache = new ArrayList<>(30);
        private volatile String userInput = "";
        public String getUserInput(){
            return userInput;
        }

        public boolean getReturnToPreviousNode() {
            return returnToPreviousNode;
        }

        public boolean getReListen() {
            return reListen;
        }

        /**
         *  A variable for storing the user's dtmf keys history.
         */
        private ArrayList<String> userInputHistory = new ArrayList<>(10);
        public ArrayList<String> getUserInputHistory(){
            return userInputHistory;
        }
        private volatile boolean hangup = false;
        public boolean getHangup(){
            return hangup;
        }

        public boolean getInputValidateSuccess() {
            return inputValidateSuccess;
        }

        public void waitForTtsChannelOpen(){
            logger.info("{} wait for stream tts channel opened.", getTraceId());
            try {
                ttsChannelOpenSignal.tryAcquire(1, 5, TimeUnit.SECONDS);
            }catch (Throwable e){
            }
        }

        public void waitForStreamTtsPlaybackFinished(){
            logger.info("{} wait for stream tts playback finished.", getTraceId());
            try {
                ttsChannelCloseSignal.tryAcquire(1, 35, TimeUnit.SECONDS);
            }catch (Throwable e){
            }
        }

        public void waitForWavPlaybackFinished(){
            logger.info("{} wait for wav file playback finished.", getTraceId());
            try {
                wavPlaybackFinishedSignal.tryAcquire(1, 35, TimeUnit.SECONDS);
            }catch (Throwable e){
            }
        }

        public void waitForPlaybackEnd(){
            if(lastPlaybackType.equalsIgnoreCase(PlaybackType.PLAYBACK_TEXT)){
                waitForStreamTtsPlaybackFinished();
            }else if(lastPlaybackType.equalsIgnoreCase(PlaybackType.PLAYBACK_WAV)){
                waitForWavPlaybackFinished();
            }
        }

        public void waitForDtmfInputCompleted(){
            logger.info("{} wait for wav file playback finished, timeout={}",
                    getTraceId(),
                    currentNode.getWaitKeyTimeout()
             );
            int timeout = currentNode.getWaitKeyTimeout();
            if(timeout == 0){
                timeout = 10;
            }
            try {
                dtmfInputCompletedSignal.tryAcquire(1, timeout, TimeUnit.SECONDS);
            }catch (Throwable e){
            }
        }

        private void resetAllSignal(){
            for (Semaphore signal : signalList) {
                try {
                    if(signal.availablePermits() > 0) {
                        signal.acquire(signal.availablePermits());
                    }
                } catch (Throwable e) {
                }
            }
        }

        private void resetDTMF() {
            inputFinished = false;
            playBackInterrupted = false;
            inputValidateSuccess = false;
            ivrProcessStarted = false;
            streamTtsChannelOpened = false;
            dtmfDigitCache.clear();
            userInput = "";
            reListen = false;
            returnToPreviousNode = false;
            resetAllSignal();
        }
        public void waitUserDtmfInput(){
            resetDTMF();

            ivrProcessStarted = true;
            logger.info("{} Wait for the user to press keys for dtmf input. ", getTraceId());

            waitForPlaybackEnd();

            if(hangup){
                return;
            }

            if(!inputFinished){
                logger.info("{} Continue to wait for the user to press keys for input until the input is completed or a timeout occurs. ",
                        getTraceId());
                waitForDtmfInputCompleted();
            }
            logger.info("{} The final user dtmf input is {}. ",  getTraceId(), userInput);
        }

        public void play(String ttsText){
            // Play TTS voice
            if (ttsText.endsWith(".wav") || ttsText.endsWith(".mp3")) {
                File audioFile = new File(ttsText);
                if(audioFile.exists()) {
                    // Play audio file
                    playAudio(ttsText);
                }else{
                    logger.error("{} audio file '{}' not exists, can not play it.", getTraceId(), ttsText);
                }
            } else {
                // TTS text to speech
                playTtsText(ttsText);
            }
        }



        /**
         * Play TTS voice
         */
        public void playTtsText(String text) {
            lastPlaybackType = PlaybackType.PLAYBACK_TEXT;
            if (text != null && !text.trim().isEmpty()) {
                String args = String.format("%s|%s|%s", ttsProvider, voiceCode, text);
                logger.info("Play TTS: SessionID={}, Text={}", sessionId, args);
                EslConnectionUtil.sendExecuteCommand("speak",
                        args,
                        sessionId
                );

                waitForTtsChannelOpen();

                if(streamTtsChannelOpened) {
                    EslConnectionUtil.sendExecuteCommand(ttsProvider + "_resume", "<StopSynthesis/>", sessionId);
                }else{
                    logger.warn("{} stream tts channel has not opened yet. ", getTraceId());
                }
            }
        }

        /**
         * Play audio file
         */
        public void playAudio(String filePath) {
            lastPlaybackType = PlaybackType.PLAYBACK_WAV;
            EslConnectionUtil.sendExecuteCommand("playback", filePath, sessionId);
            logger.info("Play audio: SessionID={}, File={}", sessionId, filePath);
        }

        @Override
        public void eventReceived(String addr, EslEvent event) {
            Map<String, String> headers = event.getEventHeaders();
            String eventName = headers.get("Event-Name");
            String eventDateTimestamp = headers.get("Event-Date-Timestamp");
            String eventSubClass = headers.get("Event-Subclass");
            String appData = headers.get("variable_current_application_data");
            if(null != eventDateTimestamp) {
                // esl消息从产生到被处理的延迟时间; 毫秒数
                long eventTime = Long.parseLong(eventDateTimestamp) / 1000L;
                long timeDelay = System.currentTimeMillis() - eventTime;
                logger.info("{} The [{}] event takes {} ms from generation to processing.", getTraceId(), eventName, timeDelay);
            }

            logger.info("{}  Event-Name：{} ", getTraceId(), eventName);
            if(EventNames.PLAYBACK_START.equalsIgnoreCase(eventName)){
                playBackInterrupted = false;

                if(!StringUtils.isNullOrEmpty(appData) && !appData.contains(".wav")) {
                    logger.info("{} streaming tts playback has been started.", getTraceId());
                    streamTtsChannelOpened = true;
                    ttsChannelOpenSignal.release();
                }else {
                    logger.info("{} wav file playback has been started.", getTraceId());
                }
            }else if(EventNames.PLAYBACK_STOP.equalsIgnoreCase(eventName)){

                if(!StringUtils.isNullOrEmpty(appData) && !appData.contains(".wav")) {
                    logger.info("{} streaming tts playback has been finished.", getTraceId());
                }else {
                    logger.info("{} wav file playback has been finished.", getTraceId());
                    wavPlaybackFinishedSignal.release();
                }

            }else if(EventNames.DTMF.equalsIgnoreCase(eventName)){
                if(!ivrProcessStarted){
                    return;
                }
                String digit = headers.get("DTMF-Digit");
                logger.info("{} recv DTMF event, digit={}, currentNode info: max={}, min={}, range={}, timeout={}, nodeId={}, rootId={}.",
                        getTraceId(),
                        digit,
                        currentNode.getMaxLen(),
                        currentNode.getMinLen(),
                        currentNode.getDigitRange(),
                        currentNode.getWaitKeyTimeout(),
                        currentNode.getId(),
                        currentNode.getRootId()
                );

                if(inputFinished){
                    logger.info("{} inputFinished, skip input dtmf {}.", getTraceId(), digit);
                    return;
                }

                if(!playBackInterrupted){
                    logger.info("{} The IVR prompt has not finished playing when the user pressed a key to interrupt it.", getTraceId());
                    playBackInterrupted = true;
                    EslConnectionUtil.sendSyncApiCommand("uuid_break", sessionId + " all");
                    ThreadUtil.sleep(200);
                    if(lastPlaybackType.equalsIgnoreCase(PlaybackType.PLAYBACK_WAV)) {
                        wavPlaybackFinishedSignal.release();
                    }
                }

                if(dtmfDigitCache.size() == 0){
                    if("#".equalsIgnoreCase(digit)){
                        userInput = "#";
                        userInputHistory.add(userInput);
                        returnToPreviousNode = true;
                        inputValidateSuccess = true;
                        dtmfInputCompletedSignal.release();
                        logger.warn("{} The user pressed # , to return to previous node. ", sessionId);
                        return;
                    }

                    if("*".equalsIgnoreCase(digit)){
                        userInput = "*";
                        userInputHistory.add(userInput);
                        reListen = true;
                        inputValidateSuccess = true;
                        dtmfInputCompletedSignal.release();
                        logger.warn("{} The user pressed * , to listen again. ", sessionId);
                        return;
                    }
                }

                if(!"#".equalsIgnoreCase(digit)) {
                    dtmfDigitCache.add(digit);
                }

                logger.info("{} check dtmfDigitCache = {}. ",sessionId, JSON.toJSONString(dtmfDigitCache));
                if(dtmfDigitCache.size() == currentNode.getMaxLen() || "#".equalsIgnoreCase(digit)){
                    inputFinished = true;
                    logger.info("{} check dtmfDigitCache, set inputFinished=true. ",sessionId);
                }

                if(inputFinished){
                    StringBuilder tmpValue = new StringBuilder();
                    for (String s : dtmfDigitCache) {
                        tmpValue.append(s);
                    }
                    MessageResponse response = RegExp.checkDigits(
                            true,
                             currentNode.getMaxLen(),
                            currentNode.getMinLen(),
                            currentNode.getDigitRange(),
                            tmpValue.toString(),
                            StringUtils.isNullOrEmpty(currentNode.getUserInputVarName()) ? "dtmf_input" : currentNode.getUserInputVarName()
                    );
                    if(!response.checkInvalid()){
                        userInput = tmpValue.toString();
                        String varName = currentNode.getUserInputVarName();
                        String action = currentNode.getAction();
                        logger.info("{} **** user dtmf input validation passed. userInput={}", sessionId, userInput);
                        if(action.equalsIgnoreCase(IvrAction.PLAY_AND_GET_DIGITS) &&
                                varName != null && varName.length() > 0){
                             String args = String.format("custom_var_%s=%s",  varName, userInput);
                             logger.info("{} **** user dtmf input set to freeSWITCH variable {}", sessionId, args);
                             EslConnectionUtil.sendExecuteCommand("set", args, sessionId);
                        }
                        userInputHistory.add(userInput);
                        inputValidateSuccess = true;
                        getCallDetail().setIvrDtmfDigits(JSON.toJSONString(userInputHistory));
                        dtmfInputCompletedSignal.release();
                    }
                }
            }else if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
                logger.info("{} CHANNEL_HANGUP. User dtmf input history = {}. ", sessionId, getCallDetail().getIvrDtmfDigits());
                hangup = true;
                dtmfInputCompletedSignal.release();
                ttsChannelOpenSignal.release();
                ttsChannelCloseSignal.release();
                wavPlaybackFinishedSignal.release();
            } else if ("CUSTOM".equalsIgnoreCase(eventName) && (
                    "TtsEvent".equalsIgnoreCase(eventSubClass)
            )) {
                String eventDetail = headers.get("Tts-Event-Detail");
                if("Speech-Closed".equalsIgnoreCase(eventDetail)){
                    logger.info("{} stream tts channel closed = true.", getTraceId());
                    streamTtsChannelOpened = false;
                    ttsChannelCloseSignal.release();
                }
            }
        }

        @Override
        public void backgroundJobResultReceived(java.lang.String addr, EslEvent event) {
        }
    };
    private EslListener eslListener;
    public EslListener getEsl(){
        return  eslListener;
    }

    public IvrSession(InboundDetail callDetail, IvrPlan plan) {
        this.callDetail = callDetail;
        this.sessionId = callDetail.getUuid();
        this.callerId = callDetail.getCaller();
        this.calleeId = callDetail.getCallee();
        this.currentPlan = plan;
        this.currentNode = plan.getRootNode();
        this.pressKeyFailures = new AtomicInteger(0);
        this.sessionData = new ConcurrentHashMap<>();
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = createTime;
        eslListener  = new EslListener();
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(
                sessionId + "-ivr",  eslListener
        );
    }

    // getter and setter methods
    public String getSessionId() { return sessionId; }
    public String getCallerId() { return callerId; }
    public String getCalleeId() { return calleeId; }
    public IvrPlan getCurrentPlan() { return currentPlan; }

    public IvrNode getCurrentNode() { return currentNode; }

    public IvrNode getRootNode() {
        return currentPlan.findNodeById(currentNode.getRootId());
    }

    public void setCurrentNode(IvrNode currentNode) { 
        this.currentNode = currentNode; 
        this.lastActiveTime = System.currentTimeMillis();
    }
    
    public int getPressKeyFailures() { return pressKeyFailures.get(); }
    public void incrementPressKeyFailures() { 
        pressKeyFailures.incrementAndGet(); 
        this.lastActiveTime = System.currentTimeMillis();
    }
    public void resetPressKeyFailures() { 
        pressKeyFailures.set(0); 
        this.lastActiveTime = System.currentTimeMillis();
    }

    public String getTtsProvider() {
        return ttsProvider;
    }

    public void setTtsProvider(String ttsProvider) {
        this.ttsProvider = ttsProvider;
    }

    public String getVoiceCode() {
        return voiceCode;
    }

    public void setVoiceCode(String voiceCode) {
        this.voiceCode = voiceCode;
    }

    public ConcurrentHashMap<String, Object> getSessionData() { return sessionData; }
    public void setSessionData(String key, Object value) { 
        sessionData.put(key, value); 
        this.lastActiveTime = System.currentTimeMillis();
    }
    public Object getSessionData(String key) { 
        this.lastActiveTime = System.currentTimeMillis();
        return sessionData.get(key); 
    }
    
    public long getCreateTime() { return createTime; }
    public long getLastActiveTime() { return lastActiveTime; }
    
    /**
     * Check if session is timed out (30 minutes inactive)
     */
    public boolean isTimeout() {
        return (System.currentTimeMillis() - lastActiveTime) > 30 * 60 * 1000;
    }
    
    /**
     * Transfer to specified node
     */
    public boolean transferToNode(String nodeId) {
        IvrNode targetNode = currentPlan.findNodeById(nodeId);
        if (targetNode != null) {
            this.currentNode = targetNode;
            this.resetPressKeyFailures();
            return true;
        }
        return false;
    }
    
    /**
     * Return to parent node
     */
    public boolean backToParent() {
        if (currentNode.getParentNodeId() != null && !"0".equalsIgnoreCase(currentNode.getParentNodeId())) {
            IvrNode parentNode = currentPlan.findNodeById(currentNode.getParentNodeId());
            if (parentNode != null) {
                this.currentNode = parentNode;
                this.resetPressKeyFailures();
                return true;
            }
        }
        return false;
    }
}