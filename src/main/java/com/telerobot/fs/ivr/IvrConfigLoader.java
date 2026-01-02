package com.telerobot.fs.ivr;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IVR Configuration Loader
 */
@Component
public class IvrConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(IvrConfigLoader.class);

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * IvrConfigLoader
     */
    private final ConcurrentHashMap<String, IvrPlan> ivrPlans = new ConcurrentHashMap<>();
    
    public IvrConfigLoader() {
    }
    
    /**
     * Load all IVR configurations when system starts
     */
    @PostConstruct
    public void loadAllIvrConfigs() {
        logger.info("Start loading all IVR configurations...");
        
        try {
            // Query all enabled IVR nodes
            String sql = "SELECT * FROM cc_ivr WHERE enabled = 1 ORDER BY parent_node_id, digit";
            List<IvrNode> allNodes = jdbcTemplate.query(sql, new IvrNodeRowMapper());
            
            // Build IVR trees
            buildIvrTrees(allNodes);
            
            logger.info("IVR configuration loading completed, loaded {} IVR plans", ivrPlans.size());
            
            // Print loaded IVR plan information
            for (Map.Entry<String, IvrPlan> entry : ivrPlans.entrySet()) {
                IvrPlan plan = entry.getValue();
                logger.info("IVR Plan: ID={}, Name={}, Total Nodes={}", 
                           plan.getPlanId(), plan.getPlanName(), plan.getAllNodes().size());
            }
            
        } catch (Exception e) {
            logger.error("Failed to load IVR configuration: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Build IVR trees
     */
    private void buildIvrTrees(List<IvrNode> allNodes) {
        // First find all root nodes (parent_node_id = 0)
        List<IvrNode> rootNodes = new ArrayList<>();
        Map<String, IvrNode> nodeMap = new HashMap<>();
        
        for (IvrNode node : allNodes) {
            nodeMap.put(node.getId(), node);
            if ("0".equalsIgnoreCase(node.getParentNodeId())) {
                rootNodes.add(node);
            }
        }
        
        // Build tree structure
        for (IvrNode node : allNodes) {
            if (!"0".equalsIgnoreCase(node.getParentNodeId())) {
                IvrNode parent = nodeMap.get(node.getParentNodeId());
                if (parent != null) {
                    parent.addChild(node);
                }
            }
        }
        
        // Create IVR plans
        for (IvrNode rootNode : rootNodes) {
            IvrPlan plan = new IvrPlan(rootNode.getId(), rootNode.getNodeName(), rootNode);

            List<IvrNode> childNodes = new ArrayList<>(20);
            for (IvrNode node : allNodes) {
                if(node.getRootId().equalsIgnoreCase(rootNode.getId())){
                    childNodes.add(node);
                }
            }
            plan.setAllNodes(childNodes);

            ivrPlans.put(rootNode.getId(), plan);
        }
    }
    
    /**
     * Recursively collect all nodes
     */
    private void collectAllNodes(IvrNode node, List<IvrNode> nodeList) {
        nodeList.add(node);
        for (IvrNode child : node.getChildren()) {
            collectAllNodes(child, nodeList);
        }
    }
    
    /**
     * Get IVR plan by plan ID
     */
    public IvrPlan getIvrPlan(String planId) {
        return ivrPlans.get(planId);
    }
    
    /**
     * Get all IVR plan IDs and names
     */
    public Map<String, String> getAllPlanIdsAndNames() {
        Map<String, String> planMap = new HashMap<>();
        for (Map.Entry<String, IvrPlan> entry : ivrPlans.entrySet()) {
            planMap.put(entry.getKey(), entry.getValue().getPlanName());
        }
        return planMap;
    }
    
    /**
     * Reload IVR configurations
     */
    public void reloadIvrConfigs() {
        ivrPlans.clear();
        loadAllIvrConfigs();
    }
    
    /**
     * IvrNode RowMapper
     */
    private static class IvrNodeRowMapper implements RowMapper<IvrNode> {
        @Override
        public IvrNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            IvrNode ivrNode = new IvrNode();

            // 映射主键及基础字段
            ivrNode.setId(rs.getString("id"));
            ivrNode.setDigit(rs.getString("digit"));
            ivrNode.setNodeName(rs.getString("node_name"));
            ivrNode.setParentNodeId(rs.getString("parent_node_id"));
            ivrNode.setRootId(rs.getString("root_id"));
            ivrNode.setTtsText(rs.getString("tts_text"));
            ivrNode.setAction(rs.getString("action"));
            ivrNode.setAiTransferData(rs.getString("ai_transfer_data"));

            // 映射整数类型字段
            ivrNode.setWaitKeyTimeout(rs.getInt("wait_key_timeout"));
            ivrNode.setMaxPressKeyFailures(rs.getInt("max_press_key_failures"));
            ivrNode.setMaxLen(rs.getInt("max_len"));
            ivrNode.setMinLen(rs.getInt("min_len"));

            // 映射字符串类型字段（处理可能为NULL的情况）
            ivrNode.setPressKeyInvalidTips(rs.getString("press_key_invalid_tips"));
            ivrNode.setFailedAction(rs.getString("failed_action"));
            ivrNode.setDigitRange(rs.getString("digit_range"));
            ivrNode.setTtsProvider(rs.getString("tts_provider"));
            ivrNode.setVoiceCode(rs.getString("voice_code"));
            ivrNode.setUserInputVarName(rs.getString("user_input_var_name"));
            ivrNode.setHangupTips(rs.getString("hangup_tips"));

            // 映射布尔/小整数类型字段（使用getBoolean或getInt判断）
            ivrNode.setEnabled(rs.getBoolean("enabled")); // 或者 rs.getInt("enabled") == 1

            return ivrNode;
        }
    }
}