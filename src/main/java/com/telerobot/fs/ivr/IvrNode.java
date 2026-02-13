package com.telerobot.fs.ivr;

import com.telerobot.fs.utils.RegExp;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.wshandle.MessageResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * IVR Node Entity Class
 * (Default built-in support. To return to the previous level, press #. To re-listen, press *)
 */
public class IvrNode {
    private String id;
    private String digit;
    private String nodeName;
    private String parentNodeId;
    private String rootId;
    private String ttsText;
    private String ttsTextWav;
    private String action;
    private String aiTransferData;
    private Integer waitKeyTimeout;
    private Integer maxPressKeyFailures;
    private String pressKeyInvalidTips;
    private String pressKeyInvalidTipsWav;
    private String failedAction;
    private Boolean enabled;
    /**
     * this is regular expression
     */
    private String digitRange;
    private String ttsProvider;
    private String voiceCode;
    private int maxLen;
    private int minLen;
    private String userInputVarName;
    private String hangupTips;
    private String hangupTipsWav;
    
    // Child nodes list
    private List<IvrNode> children = new ArrayList<>();
    
    // Constructor
    public IvrNode() {}
    
    // getter and setter methods
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getDigit() { return digit; }
    public void setDigit(String digit) { this.digit = digit; }

    public String getAiTransferData() {
        return aiTransferData;
    }

    public void setAiTransferData(String aiTransferData) {
        this.aiTransferData = aiTransferData;
    }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    
    public String getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(String parentNodeId) { this.parentNodeId = parentNodeId; }

    public String getRootId() {
        return rootId;
    }

    public void setRootId(String rootId) {
        this.rootId = rootId;
    }

    public String getTtsText() { return ttsText; }
    public void setTtsText(String ttsText) { this.ttsText = ttsText; }
    
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    
    public Integer getWaitKeyTimeout() { return waitKeyTimeout; }
    public void setWaitKeyTimeout(Integer waitKeyTimeout) { this.waitKeyTimeout = waitKeyTimeout; }
    
    public Integer getMaxPressKeyFailures() { return maxPressKeyFailures; }
    public void setMaxPressKeyFailures(Integer maxPressKeyFailures) { this.maxPressKeyFailures = maxPressKeyFailures; }
    
    public String getPressKeyInvalidTips() { return pressKeyInvalidTips; }
    public void setPressKeyInvalidTips(String pressKeyInvalidTips) { this.pressKeyInvalidTips = pressKeyInvalidTips; }
    
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    
    public String getFailedAction() { return failedAction; }
    public void setFailedAction(String failedAction) { this.failedAction = failedAction; }
    
    public String getDigitRange() { return digitRange; }
    public void setDigitRange(String digitRange) { this.digitRange = digitRange; }

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

    public int getMaxLen() {
        return maxLen;
    }

    public void setMaxLen(int maxLen) {
        this.maxLen = maxLen;
    }

    public int getMinLen() {
        return minLen;
    }

    public void setMinLen(int minLen) {
        this.minLen = minLen;
    }

    public String getUserInputVarName() {
        return userInputVarName;
    }

    public void setUserInputVarName(String userInputVarName) {
        this.userInputVarName = userInputVarName;
    }

    public String getHangupTips() {
        return hangupTips;
    }

    public void setHangupTips(String hangupTips) {
        this.hangupTips = hangupTips;
    }

    public String getTtsTextWav() {
        return ttsTextWav;
    }

    public void setTtsTextWav(String ttsTextWav) {
        this.ttsTextWav = ttsTextWav;
    }

    public String getPressKeyInvalidTipsWav() {
        return pressKeyInvalidTipsWav;
    }

    public void setPressKeyInvalidTipsWav(String pressKeyInvalidTipsWav) {
        this.pressKeyInvalidTipsWav = pressKeyInvalidTipsWav;
    }

    public String getHangupTipsWav() {
        return hangupTipsWav;
    }

    public void setHangupTipsWav(String hangupTipsWav) {
        this.hangupTipsWav = hangupTipsWav;
    }

    public List<IvrNode> getChildren() { return children; }
    public void setChildren(List<IvrNode> children) { this.children = children; }
    
    public void addChild(IvrNode child) {
        this.children.add(child);
    }
    
    /**
     * Check if digit matches current node
     */
    public boolean isDigitMatch(String inputDigit) {
        if (inputDigit == null || inputDigit.length() == 0) {
            return false;
        }

        MessageResponse response = RegExp.checkDigits(
                true,
                maxLen,
                minLen,
                digitRange,
                inputDigit,
                "dtmf_digits"
        );

        return !response.checkInvalid();
    }
    
    /**
     * Check if digit is within range
     */
    private boolean isDigitInRange(String digit, String range) {
        try {
            int input = Integer.parseInt(digit);
            if (range.contains("-")) {
                String[] parts = range.split("-");
                if (parts.length == 2) {
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    return input >= start && input <= end;
                }
            } else if (range.contains(",")) {
                String[] digits = range.split(",");
                for (String d : digits) {
                    if (Integer.parseInt(d.trim()) == input) {
                        return true;
                    }
                }
            }
        } catch (NumberFormatException e) {
            // Ignore format errors
        }
        return false;
    }
    
    @Override
    public String toString() {
        return "IvrNode{" +
                "id=" + id +
                ", digit=" + digit +
                ", nodeName='" + nodeName + '\'' +
                ", parentNodeId=" + parentNodeId +
                ", action='" + action + '\'' +
                ", childrenCount=" + children.size() +
                '}';
    }
}