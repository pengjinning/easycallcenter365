package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.CozeAccount;
import com.telerobot.fs.entity.dto.llm.LlmAccount;
import com.telerobot.fs.entity.pojo.LlmToolRequest;
import com.telerobot.fs.robot.AbstractChatRobot;
import com.telerobot.fs.utils.CommonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;

public class LocalNlpChat extends AbstractChatRobot {

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question) {
        LlmAiphoneRes aiphoneRes = new  LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);

        // 获取随路数据
        JSONObject bizJson = new JSONObject();
        if (null != callDetail.getOutboundPhoneInfo()) {
            if (null != callDetail.getOutboundPhoneInfo().getBizJson()) {
                bizJson = JSONObject.parseObject(callDetail.getOutboundPhoneInfo().getBizJson());
            }
        }
        logger.info("随路数据:{}", bizJson);
        logger.info("模型接口地址:{}", getAccount().serverUrl);

        try {
            JSONObject response = sendStreamingRequest(aiphoneRes, bizJson, question);
            if(null != response) {
                llmRoundMessages.add(response);
            }else{
                aiphoneRes.setStatus_code(0);
            }
        } catch (Throwable throwable) {
            aiphoneRes.setStatus_code(0);
            logger.error("{} talkWithAiAgent error: {}", uuid, CommonUtils.getStackTraceString(throwable.getStackTrace()));
        }

        return aiphoneRes;
    }



    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, JSONObject bizJson, String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("callId", uuid);
        requestBody.put("projectId", ((CozeAccount)getAccount()).getBotId());
        requestBody.put("bizJson", bizJson);
        requestBody.put("asrResult", question);
        logger.info("请求参数:{}", requestBody.toJSONString());

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("User-Agent", "Apifox/1.0.0 (https://apifox.com)")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .addHeader("Connection", "keep-alive")
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Model api error: http-code={}, msg={}, url={}",
                        response.code(),
                        response.message(),
                        getAccount().serverUrl
                );
                if(response.code() == HttpStatus.SC_UNAUTHORIZED || response.code() >= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                    throw new IOException("Unexpected code " + response);
                }else{
                    return null;
                }
            }

            String chatContent = "";
            String callCode = "";
            JSONObject result = JSONObject.parseObject(response.body().string());

            if (result.getInteger("code") == 200) {
                chatContent = result.getJSONObject("result").getString("chatContent");
                callCode = result.getJSONObject("result").getString("callCode");
                String ttsContent = result.getJSONObject("result").getString("ttsContent");
                ttsTextCache.add(ttsContent);
                ttsTextLength += ttsContent.length();
            }

            logger.info("{} recv llm response end flag. answer={}", this.uuid, chatContent);

            sendToTts();
            closeTts();

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("role", "assistant");
            finalResponse.put("content", chatContent);
            finalResponse.put("callCode", callCode);
            aiphoneRes.setBody(chatContent);
            return finalResponse;
        }
    }
}
