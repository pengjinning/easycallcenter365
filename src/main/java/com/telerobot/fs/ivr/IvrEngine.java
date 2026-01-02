package com.telerobot.fs.ivr;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.pojo.AsrProvider;
import com.telerobot.fs.entity.pojo.TtsProvider;
import com.telerobot.fs.robot.TransferToAgent;
import com.telerobot.fs.service.InboundDetailService;
import com.telerobot.fs.tts.aliyun.AliyunTTSWebApi;
import com.telerobot.fs.utils.ThreadUtil;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IVR Engine Core Class
 */
@Component
public class IvrEngine {
    private static final Logger logger = LoggerFactory.getLogger(IvrEngine.class);
    
    private final IvrConfigLoader ivrConfigLoader;
    
    // Store all active IVR sessions
    private final ConcurrentHashMap<String, IvrSession> activeSessions = new ConcurrentHashMap<>();
    
    // Scheduled task executor for cleaning up timeout sessions
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public IvrEngine(IvrConfigLoader ivrConfigLoader) {
        this.ivrConfigLoader = ivrConfigLoader;
        
        // Start session cleanup task
        startSessionCleanupTask();
    }

    private void startDtmf(String sessionId){
        EslConnectionUtil.sendExecuteCommand("start_dtmf", "", sessionId);
    }

    private boolean checkCallStatus(String uuid){
        EslMessage apiResponseMsg = EslConnectionUtil.sendSyncApiCommand(
                "uuid_exists",
                uuid
        );
        if (apiResponseMsg != null && apiResponseMsg.getBodyLines().size() != 0) {
            String apiResponseText = apiResponseMsg.getBodyLines().get(0);
            if ("false".equalsIgnoreCase(apiResponseText)) {
                return false;
            }
        } else {
            logger.info("{} uuid_exists check error, can not get apiResponseMsg...", uuid);
        }
        return true;
    }
    
    /**
     * Start new IVR session
     */
    public boolean  startIvrSession(InboundDetail callDetail, String ivrPlanId) {
        String sessionId = callDetail.getUuid();
        String callerId = callDetail.getCaller();
        String calleeId = callDetail.getCallee();
        IvrPlan plan = ivrConfigLoader.getIvrPlan(ivrPlanId);
        if (plan == null) {
            logger.error("IVR plan not found: ID={}", ivrPlanId);
            return false;
        }

        if(!checkCallStatus(sessionId)){
            logger.warn("{} The call session is hangup, cant not process ivr request.", sessionId);
            return false;
        }

        IvrSession session = new IvrSession(callDetail, plan);
        session.setTtsProvider(plan.getRootNode().getTtsProvider());
        session.setVoiceCode(plan.getRootNode().getVoiceCode());
        activeSessions.put(sessionId, session);
        startDtmf(session.getSessionId());

        if(session.getTtsProvider().equalsIgnoreCase(TtsProvider.ALIYUN)) {
            if ((!AliyunTTSWebApi.setAliyunTokenToFreeSWITCH(sessionId))) {
                logger.error("{}  AliyunTTSWebApi getToken error!", sessionId);
                executeHangupAction(session, "get-aliyun-token-error");
                return false;
            }
        }


        logger.info("Start IVR session: SessionID={}, PlanID={}, PlanName={}, Caller={}, Callee={}", 
                   sessionId, ivrPlanId, plan.getPlanName(), callerId, calleeId);
        
        // Start executing IVR flow
        return executeIvrNode(session);
    }
    
    /**
     * Process DTMF input
     */
    public boolean processDtmfInput(IvrSession session, String dtmfDigit) {
        String sessionId  = session.getSessionId();
        IvrNode currentNode = session.getCurrentNode();
        logger.info("Process DTMF input: SessionID={}, CurrentNodeID={}, NodeName={}, InputDigit={}", 
                   sessionId, currentNode.getId(), currentNode.getNodeName(), dtmfDigit);

        if(session.getEsl().getReListen()){
            return executeIvrNode(session);
        }

        if(session.getEsl().getReturnToPreviousNode()){
            session.backToParent();
            return executeIvrNode(session);
        }

        // Find matching child node
        IvrNode matchedChild = findMatchingChild(currentNode, dtmfDigit);

        if (matchedChild != null) {
            logger.warn("{} A matched child found for digit={}, jump to new node={},  currentNode={}.",
                    sessionId,
                    dtmfDigit,
                    matchedChild.getNodeName() + " ,id: " + matchedChild.getId(),
                    currentNode.getNodeName() + " ,id: " + currentNode.getId()
            );
            // Match successful, transfer to child node
            session.setCurrentNode(matchedChild);
            session.resetPressKeyFailures();
            return executeIvrNode(session);
        } else {
            logger.warn("{} No matched child node found for digit={}, currentNode={}.",
                    sessionId,
                    dtmfDigit,
                    currentNode.getNodeName() + " ,id: " + currentNode.getId()
            );
            // Match failed
            return executeHangupAction(session, "no-Children-found");
        }
    }
    
    /**
     * Execute current IVR node
     */
    private boolean executeIvrNode(IvrSession session) {
        if(session.getEsl().getHangup()){
            return false;
        }
        IvrNode node = session.getCurrentNode();
        logger.info("Execute IVR node: SessionID={}, NodeID={}, NodeName={}, Action={}",
                session.getSessionId(), node.getId(), node.getNodeName(), node.getAction());

        executePlayAction(session, node);
        String action = node.getAction().toLowerCase();
        try {
            switch (action) {
                case IvrAction.ACD:
                case IvrAction.GATEWAY:
                case IvrAction.EXTENSION:
                    logger.info("{} Try to transfer call session to manual.", session.getSessionId());
                    return doTransferToManualAgent(session);
                case IvrAction.HANGUP:
                    logger.info("{} Try to hangup call session.", session.getSessionId());
                    return executeHangupAction(session, "reach-hangup-node");
                case IvrAction.UP_ACTION:
                    logger.info("Return to parent, SessionID={}", session.getSessionId());
                    if (session.backToParent()) {
                        return executeIvrNode(session);
                    }
                    return executeHangupAction(session, "go-up-action-error");
                case IvrAction.FUNCTION:
                    executeFunctionAction(session, node);
            }
        } catch (Exception e) {
            logger.error("Failed to execute IVR node: SessionID={}, NodeID={}",
                    session.getSessionId(), node.getId(), e);
            return false;
        }

        startWaitForInput(session);
        while (!session.getEsl().getInputValidateSuccess() && !session.getEsl().getHangup()) {
            IvrNode currentNode = session.getCurrentNode();
            session.incrementPressKeyFailures();

            int failures = session.getPressKeyFailures();
            int maxFailures = currentNode.getMaxPressKeyFailures();

            logger.warn("DTMF input failed: SessionID={}, Failures={}/{}",
                    session.getSessionId(), failures, maxFailures);

            // Play error prompt
            if (currentNode.getPressKeyInvalidTips() != null &&
                    !currentNode.getPressKeyInvalidTips().trim().isEmpty()) {
                session.getEsl().play(currentNode.getPressKeyInvalidTips());
                session.getEsl().waitForPlaybackEnd();
            }

             if (failures < maxFailures) {
                executePlayAction(session, node);
                // Continue waiting for input
                startWaitForInput(session);
            }else{
                break;
            }
        }

        session.resetPressKeyFailures();

        if (!session.getEsl().getInputValidateSuccess()) {
            // Reach maximum failure count, execute failed action
            executeFailedAction(session);
        } else {
            if (action.equalsIgnoreCase(IvrAction.PLAY)) {
                return processDtmfInput(session, session.getEsl().getUserInput());
            }

            if (action.equalsIgnoreCase(IvrAction.PLAY_AND_GET_DIGITS)) {
                if (session.backToParent()) {
                    return executeIvrNode(session);
                }
                return executeHangupAction(session, "go-up-action-error");
            }
        }

        return true;
    }
    
    /**
     * Execute play action
     */
    private boolean executePlayAction(IvrSession session, IvrNode node) {
        String ttsText = node.getTtsText();
        if (ttsText != null && !ttsText.trim().isEmpty()) {
            session.getEsl().play(ttsText);
        }
       return true;
    }


    private boolean doTransferToManualAgent(IvrSession session){
        if(!session.getEsl().getHangup()) {
            String transferType = session.getCurrentNode().getAction();
            String transferData = session.getCurrentNode().getAiTransferData();
            TransferToAgent.transfer(session.getCallDetail(), transferType, transferData);
            return true;
        }
        return false;
    }
    
    /**
     * Execute hangup action
     */
    private boolean executeHangupAction(IvrSession session, String reason) {
        String hangupTips = session.getCurrentNode().getHangupTips();
        // If the hang-up tts-text of the current node is empty, the default hang-up tts-text of the root node will be used.
        if(StringUtils.isEmpty(hangupTips)){
           IvrNode rootNode = session.getRootNode();
           if(rootNode != null){
               hangupTips = rootNode.getHangupTips();
           }
        }

        if(!StringUtils.isEmpty(hangupTips)) {
            session.getEsl().play(hangupTips);
            session.getEsl().waitForPlaybackEnd();
        }

        ThreadUtil.sleep(1500);
        if(!session.getEsl().getHangup()) {
            String sessionId = session.getSessionId();
            logger.info("Execute hangup: SessionID={}, reason={}", sessionId, reason);
            String response = EslConnectionUtil.sendExecuteCommand("hangup", reason, sessionId);
            logger.info("Hangup result: {}", response);
            endIvrSession(session);
        }
        return true;
    }
    

    
    /**
     * Execute function action
     */
    private boolean executeFunctionAction(IvrSession session, IvrNode node) {
        String sessionId = session.getSessionId();
        String function = node.getTtsText(); // Use tts_text as function name and parameters
        
        logger.info("Execute function: SessionID={}, Function={}", sessionId, function);
        
        // Send function execution command
        String command = String.format("sched_api +0 %s %s", sessionId, function);
        String response = EslConnectionUtil.sendAsyncApiCommand("function", command);
        
        logger.info("Function execution result: {}", response);
        
        // Continue IVR flow after function execution
        if (!node.getChildren().isEmpty()) {
            startWaitForInput(session);
        } else {
            endIvrSession(session);
        }
        return true;
    }
    
    /**
     * Find matching child node
     */
    private IvrNode findMatchingChild(IvrNode parent, String dtmfDigit) {
        for (IvrNode child : parent.getChildren()) {
            if (child.getDigit().equalsIgnoreCase(dtmfDigit)) {
                return child;
            }
        }
        return null;
    }
    

    
    /**
     * Execute failed action
     */
    private boolean executeFailedAction(IvrSession session) {
        IvrNode currentNode = session.getCurrentNode();
        String failedAction = currentNode.getFailedAction();
        
        if ("hangup".equalsIgnoreCase(failedAction)) {
            logger.info("Execute failed action: Hangup, SessionID={}", session.getSessionId());
            return executeHangupAction(session, "executeFailedAction");
        } else if ("upaction".equalsIgnoreCase(failedAction)) {
            logger.info("Execute failed action: Return to parent, SessionID={}", session.getSessionId());
            if (session.backToParent()) {
                return executeIvrNode(session);
            } else {
                // Cannot return to parent, hangup
                return executeHangupAction(session, "go-up-action-error");
            }
        } else {
            // Default hangup
            logger.info("Execute default failed action: Hangup, SessionID={}", session.getSessionId());
            return executeHangupAction(session, "executeFailedAction");
        }
    }
    
    /**
     * Start waiting for user input
     */
    private void startWaitForInput(IvrSession session) {
        IvrNode node = session.getCurrentNode();
        logger.info("Start waiting for user input: SessionID={}, Timeout={} seconds.", session.getSessionId(), node.getWaitKeyTimeout());
        session.getEsl().waitUserDtmfInput();
    }

    /**
     * End IVR session
     */
    public void endIvrSession( IvrSession session) {
        String sessionId = session.getSessionId();
        activeSessions.remove(sessionId);
        logger.info("End IVR session: SessionID={}", sessionId);
    }

    /**
     * Force end IVR session
     */
    public boolean forceEndSession(String sessionId) {
        IvrSession session = activeSessions.get(sessionId);
        if (session != null) {
            endIvrSession(session);
            return true;
        }
        return false;
    }

    /**
     * Get session status
     */
    public IvrSession getSessionStatus(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * Start session cleanup task
     */
    private void startSessionCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupTimeoutSessions();
            } catch (Exception e) {
                logger.error("Failed to execute session cleanup task", e);
            }
        }, 5, 5, TimeUnit.MINUTES); // Execute every 5 minutes
    }

    /**
     * Cleanup timeout sessions
     */
    private void cleanupTimeoutSessions() {
        long currentTime = System.currentTimeMillis();
        List<IvrSession> timeoutSessions = new ArrayList<>();

        for (IvrSession session : activeSessions.values()) {
            if (session.isTimeout()) {
                timeoutSessions.add(session);
            }
        }

        for (IvrSession s : timeoutSessions) {
            logger.warn("Cleanup timeout IVR session: SessionID={}", s.getSessionId());
            endIvrSession(s);
        }

        if (!timeoutSessions.isEmpty()) {
            logger.info("Cleaned up {} timeout IVR sessions", timeoutSessions.size());
        }
    }

    /**
     * Shutdown engine
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}