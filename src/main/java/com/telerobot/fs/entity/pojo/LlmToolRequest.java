package com.telerobot.fs.entity.pojo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.utils.RegExp;

import java.util.List;

public class LlmToolRequest {

    public static final String TRANSFER_TO_AGENT = "transferToAgent";
    public static final String TRANSFER_TO_TEL = "transferToTel";
    public static final String TRANSFER_TO_TEL_REGEXP = "transferToTel\\D{1,4}\\d{1,12}";
    public static final String HANGUP = "hangupCall";
    public static final String KB_QUERY = "kbQuery";
    public static final String KB_QUERY_NOT_FOUND = "no-topics-found";

    private String tool;
    private JSONObject arguments;
    private String content;

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public JSONObject getArguments() {
        return arguments;
    }

    public void setArguments(JSONObject arguments) {
        this.arguments = arguments;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }


}
