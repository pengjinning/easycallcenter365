package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.robot.AbstractChatRobot;
import okhttp3.*;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;

public class QzCustomChat extends AbstractChatRobot {

    static {
        logger.info("QzCustomChat instance init.");
    }

    private String sessionId = "";

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question, Boolean... withKbResponse) {
        LlmAiphoneRes aiphoneRes = new LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);

        JSONObject requestJson = new JSONObject();
        requestJson.put("message", question);
        requestJson.put("phone", callDetail.getCaller());
        requestJson.put("session_id", sessionId);
        requestJson.put("recordings", callDetail.getWavFile());
        requestJson.put("uuid", callDetail.getUuid());

        String json = requestJson.toJSONString();
        logger.info("{} talk with robot:  {}", uuid, json);
        String url = getAccount().serverUrl;
        RequestBody requestBody = FormBody.create(MediaType.parse("application/json; charset=utf-8"), json);

        Request.Builder builder = new Request.Builder();
        Request request = builder
                .post(requestBody)
                .url(url).build();

        Response response = null;
        try {
            response = CLIENT.newCall(request).execute();
        } catch (IOException e) {
            aiphoneRes.setStatus_code(0);
            aiphoneRes.setBody("");
            logger.warn("{} request robot error {}: {}", this.getTraceId() , url,  e.toString());
            return aiphoneRes;
        }
        String responseStr = "";
        try {
            String origRes = response.body().string();
            logger.info("{} original server response : {}", uuid, origRes);
            JSONObject jsonResponse = JSON.parseObject(origRes);
            responseStr = jsonResponse.getString("reply");
            int hangup = jsonResponse.getIntValue("hangup");

            if(StringUtils.isEmpty(sessionId)){
                sessionId = jsonResponse.getString("session_id");
                if(!StringUtils.isEmpty(sessionId)) {
                    logger.info("{} successfully get session_id={}", uuid, sessionId);
                }
            }
            if(hangup == 1){
                aiphoneRes.setClose_phone(1);
                logger.info("{} hangup signal detected.", uuid);
            }
        } catch (IOException e) {
            logger.warn(String.format("%s get robot response error: %s",getTraceId(), e.toString() ));
        }
        int httpStatus = response.code();
        if(httpStatus != 200) {
            aiphoneRes.setStatus_code(0);
            aiphoneRes.setBody("");
            logger.warn(String.format("%s robot error %s: %s",
                    getTraceId(), String.valueOf(httpStatus), responseStr));
        }else {
            sendTtsRequest(responseStr);
            closeTts();
            aiphoneRes.setBody(responseStr);

            JSONObject userMessage1 = new JSONObject();
            userMessage1.put("role", "user");
            userMessage1.put("content", question);
            userMessage1.put("content_type", "text");
            llmRoundMessages.add(userMessage1);

            JSONObject responseMsg = new JSONObject();
            responseMsg.put("role", "assistant");
            responseMsg.put("content", responseStr);
            responseMsg.put("content_type", "text");
            llmRoundMessages.add(responseMsg);
        }

        return aiphoneRes;
    }


}
