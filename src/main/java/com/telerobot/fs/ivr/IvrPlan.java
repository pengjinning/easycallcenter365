package com.telerobot.fs.ivr;

import java.util.ArrayList;
import java.util.List;

/**
 * IVR Plan Entity Class
 */
public class IvrPlan {
    private String planId; // Use ID as unique identifier instead of name
    private String planName;
    private IvrNode rootNode;
    private List<IvrNode> allNodes = new ArrayList<>();
    
    public  IvrPlan(String planId, String planName, IvrNode rootNode) {
        this.planId = planId;
        this.planName = planName;
        this.rootNode = rootNode;
    }
    
    // getter and setter methods
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    
    public IvrNode getRootNode() { return rootNode; }
    public void setRootNode(IvrNode rootNode) { this.rootNode = rootNode; }
    
    public List<IvrNode> getAllNodes() { return allNodes; }
    public void setAllNodes(List<IvrNode> allNodes) { this.allNodes = allNodes; }
    
    /**
     * Find node by ID
     */
    public IvrNode findNodeById(String nodeId) {
        for (IvrNode node : allNodes) {
            if (node.getId().equalsIgnoreCase(nodeId)) {
                return node;
            }
        }
        return null;
    }
}