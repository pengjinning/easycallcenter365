package com.telerobot.fs.controller;

import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.ivr.IvrConfigLoader;
import com.telerobot.fs.ivr.IvrEngine;
import com.telerobot.fs.ivr.IvrSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpRequest;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * IVR Service REST Controller
 */
@RestController
@RequestMapping("/api/ivr")
public class IvrController {
    private static final Logger logger = LoggerFactory.getLogger(IvrController.class);

    @Lazy
    @Autowired
    private IvrEngine ivrEngine;

    @Autowired
    private IvrConfigLoader ivrConfigLoader;
    
    /**
     * Start IVR session
     */
    @GetMapping("/start")
    public String startIvrSession(HttpServletRequest request) {


        Map<String, Object> response = new HashMap<>();
        try {
            String sessionId = request.getParameter("uuid");
            String callerId = request.getParameter("caller");
            String calleeId = request.getParameter("callee");
            String ivrPlanId = request.getParameter("ivr");

            if (sessionId == null || callerId == null || calleeId == null || ivrPlanId == null) {
                response.put("success", false);
                response.put("message", "Missing required parameters");
            }

            InboundDetail callDetail = new InboundDetail(
                    UuidGenerator.GetOneUuid(),
                    callerId,
                    calleeId,
                    System.currentTimeMillis(),
                    sessionId,
                    sessionId + ".wav",
                    String.valueOf(0),
                    "0",
                    null
            );

            boolean result = ivrEngine.startIvrSession(callDetail, ivrPlanId);

            response.put("success", result);
            response.put("message", result ? "IVR session started successfully" : "Failed to start IVR session");

        } catch (Exception e) {
            logger.error("Failed to start IVR session", e);
            response.put("success", false);
            response.put("message", "System error: " + e.getMessage());
        }


        return "success";
    }
    
    /**
     * End IVR session
     */
    @PostMapping("/end")
    public Map<String, Object> endIvrSession(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String sessionId = request.get("sessionId");
            
            if (sessionId == null) {
                response.put("success", false);
                response.put("message", "Missing session ID");
                return response;
            }
            
            ivrEngine.forceEndSession(sessionId);
            
            response.put("success", true);
            response.put("message", "IVR session ended successfully");
            
        } catch (Exception e) {
            logger.error("Failed to end IVR session", e);
            response.put("success", false);
            response.put("message", "System error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Get session status
     */
    @GetMapping("/status/{sessionId}")
    public Map<String, Object> getSessionStatus(@PathVariable String sessionId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            IvrSession session = ivrEngine.getSessionStatus(sessionId);
            
            if (session != null) {
                response.put("success", true);
                response.put("session", buildSessionInfo(session));
            } else {
                response.put("success", false);
                response.put("message", "Session not found");
            }
            
        } catch (Exception e) {
            logger.error("Failed to get session status", e);
            response.put("success", false);
            response.put("message", "System error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Get all IVR plans
     */
    @GetMapping("/plans")
    public Map<String, Object> getAllIvrPlans() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, String> planMap = ivrConfigLoader.getAllPlanIdsAndNames();
            response.put("success", true);
            response.put("plans", planMap);
            
        } catch (Exception e) {
            logger.error("Failed to get IVR plan list", e);
            response.put("success", false);
            response.put("message", "System error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Reload IVR configuration
     */
    @GetMapping("/reload")
    public Map<String, Object> reloadIvrConfig() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            ivrConfigLoader.reloadIvrConfigs();
            response.put("success", true);
            response.put("message", "IVR configuration reloaded successfully");
            
        } catch (Exception e) {
            logger.error("Failed to reload IVR configuration", e);
            response.put("success", false);
            response.put("message", "System error: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Build session information
     */
    private Map<String, Object> buildSessionInfo(IvrSession session) {
        Map<String, Object> sessionInfo = new HashMap<>();
        sessionInfo.put("sessionId", session.getSessionId());
        sessionInfo.put("callerId", session.getCallerId());
        sessionInfo.put("calleeId", session.getCalleeId());
        sessionInfo.put("currentNodeId", session.getCurrentNode().getId());
        sessionInfo.put("currentNodeName", session.getCurrentNode().getNodeName());
        sessionInfo.put("pressKeyFailures", session.getPressKeyFailures());
        sessionInfo.put("createTime", session.getCreateTime());
        sessionInfo.put("lastActiveTime", session.getLastActiveTime());
        sessionInfo.put("planId", session.getCurrentPlan().getPlanId());
        sessionInfo.put("planName", session.getCurrentPlan().getPlanName());
        return sessionInfo;
    }
}