package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.LlmAccount;
import com.telerobot.fs.robot.AbstractChatRobot;
import com.telerobot.fs.utils.CommonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.List;

public class DeepSeekChat extends AbstractChatRobot {

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question) {
        LlmAiphoneRes aiphoneRes = new  LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);
        if(firstRound) {
            firstRound = false;
            String tips = ((LlmAccount)getAccount()).getLlmTips() + "\n" + ((LlmAccount)getAccount()).getFaqContext();
            addDialogue(ROLE_SYSTEM, tips);

            String openingRemarks = llmAccountInfo.openingRemarks;
            addDialogue(ROLE_ASSISTANT, openingRemarks);

            ttsTextCache.add(openingRemarks);
            sendToTts();
            closeTts();

            aiphoneRes.setBody(openingRemarks);
        }else{
            if(!StringUtils.isEmpty(question)) {
                addDialogue(ROLE_USER, question);
            }else{
                addDialogue(ROLE_USER, "NO_VOICE");

                String noVoiceTips = llmAccountInfo.customerNoVoiceTips;
                addDialogue(ROLE_ASSISTANT, noVoiceTips);

                ttsTextCache.add(noVoiceTips);
                sendToTts();
                closeTts();

                aiphoneRes.setBody(noVoiceTips);
            }
        }

        if(!firstRound && !StringUtils.isEmpty(question)) {
            try {
                JSONObject response = sendStreamingRequest(aiphoneRes, llmRoundMessages);
                llmRoundMessages.add(response);
            } catch (Throwable throwable) {
                logger.error("{} talkWithAiAgent error: {}", uuid, CommonUtils.getStackTraceString(throwable.getStackTrace()));
            }
        }

        return aiphoneRes;
    }



    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, List<JSONObject> messages) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", ((LlmAccount)getAccount()).getModelName());
        requestBody.put("stream", true);
        // enable stream output


        JSONArray messagesArray = new JSONArray();
        messagesArray.addAll(messages);
        requestBody.put("messages", messagesArray);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + ((LlmAccount)getAccount()).getApiKey())
                .build();

        boolean recvData = false;
        boolean jsonFormat = false;
        long startTime = System.currentTimeMillis();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Model api error: http-code={}, msg={}, url={}",
                        response.code(),
                        response.message(),
                        getAccount().serverUrl
                );
                throw new IOException("Unexpected code " + response);
            }

            BufferedSource source = response.body().source();
            StringBuilder responseBuilder = new StringBuilder();

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line != null && line.startsWith("data: ")) {
                    String jsonData = line.substring(6).trim(); // 去掉 "data: " 前缀
                    if (jsonData.equals("[DONE]")) {
                        break; // 流式响应结束
                    }

                    JSONObject jsonResponse = JSON.parseObject(jsonData);
                    JSONObject message = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("delta"); // 注意：流式响应中消息在 "delta" 字段中

                    if (message.containsKey("content")) {
                        String speechContent = message.getString("content");
                        logger.info("{} speechContent: {}", getTraceId(), speechContent);

                        if (!recvData) {
                            recvData = true;
                            long costTime = (System.currentTimeMillis() - startTime);
                            logger.info("http request cost time : {} ms.", costTime);
                            aiphoneRes.setCostTime(costTime);
                        }

                        if (!StringUtils.isEmpty(speechContent)) {
                            //  send to tts server
                            if (speechContent.startsWith("{") && !jsonFormat) {
                                logger.info("{} json response detected.", getTraceId());
                                jsonFormat = true;
                                aiphoneRes.setJsonResponse(true);
                            }

                            if (!StringUtils.isEmpty(speechContent) && !jsonFormat) {
                                ttsTextCache.add(speechContent);
                                ttsTextLength += speechContent.length();
                                // 积攒足够的字数之后，才发送给tts，避免播放异常;
                                if (ttsTextLength >= 10 && checkPauseFlag(speechContent)) {
                                    sendToTts();
                                }
                            }
                            responseBuilder.append(speechContent);
                        }
                    }
                }
            }

            String answer = responseBuilder.toString();
            logger.info("{} recv llm response end flag. answer={}", this.uuid, answer);
            if(ttsTextLength > 0 && !jsonFormat){
                sendToTts();
            }
            closeTts();

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("role", "assistant");
            finalResponse.put("content", answer);
            aiphoneRes.setBody(answer);
            return finalResponse;
        }
    }
}
