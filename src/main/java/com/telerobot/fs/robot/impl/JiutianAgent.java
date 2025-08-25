package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.CozeAccount;
import com.telerobot.fs.entity.dto.llm.JiutianAccount;
import com.telerobot.fs.entity.dto.llm.LlmAccount;
import com.telerobot.fs.robot.AbstractChatRobot;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.JiutianTokenUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.List;

public class JiutianAgent extends AbstractChatRobot {

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question) {
        LlmAiphoneRes aiphoneRes = new  LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);
        if(firstRound) {
            firstRound = false;

            String openingRemarks = llmAccountInfo.openingRemarks;
            addDialogue(ROLE_ASSISTANT, openingRemarks);

            ttsTextCache.add(openingRemarks);
            sendToTts();
            closeTts();

            aiphoneRes.setBody(openingRemarks);
            return aiphoneRes;
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
                return aiphoneRes;
            }
        }

        if(!firstRound && !StringUtils.isEmpty(question)) {
            try {
                JSONObject response = sendStreamingRequest(aiphoneRes, llmRoundMessages, question);
                logger.info(response.toJSONString());
                if(null != response) {
                    llmRoundMessages.add(response);
                }else{
                    aiphoneRes.setStatus_code(0);
                }
            } catch (Throwable throwable) {
                aiphoneRes.setStatus_code(0);
                logger.error("{} talkWithAiAgent error: {}", uuid, CommonUtils.getStackTraceString(throwable.getStackTrace()));
            }
        }

        return aiphoneRes;
    }



    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, List<JSONObject> messages, String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("stream", true);
        requestBody.put("appId", ((JiutianAccount)getAccount()).getBotId());
        requestBody.put("prompt", question);
        JSONArray history = new JSONArray();
        if (messages.size() >= 4) {
            JSONArray historyContent = new JSONArray();
            for (int i = messages.size() - 3; i < messages.size() - 1; i++) {
                JSONObject message = messages.get(i);
                String content = message.getString("content");
                historyContent.add(content);
            }
            history.add(historyContent);
        }
        requestBody.put("history", history);

        logger.info(requestBody.toJSONString());

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        String apiKey = ((JiutianAccount)getAccount()).getApiKey();
        String token = JiutianTokenUtils.getToken(apiKey);

        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
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
                if(response.code() == HttpStatus.SC_UNAUTHORIZED || response.code() >= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                    throw new IOException("Unexpected code " + response);
                }else{
                    return null;
                }
            }

            BufferedSource source = response.body().source();
            StringBuilder responseBuilder = new StringBuilder();

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                logger.info(line);
                if (line != null && line.startsWith("data:")) {
                    String jsonData = line.substring(5).trim(); // 去掉 "data: " 前缀
                    if (jsonData.equals("[DONE]")) {
                        break; // 流式响应结束
                    }

                    JSONObject jsonResponse = JSON.parseObject(jsonData);

                    // "choices": [
                    //    {
                    //      "index": 0,
                    //      "role": "assistant",
                    //      "type": "text",
                    //      "id": "3cb382ee-a8ec-4213-ad30-5e6e36fdb062",
                    //      "status": "init/finish",
                    //      "text": "北京是中国的"
                    //    }
                    //  ],
                    JSONObject delta = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("delta"); // 注意：流式响应中消息在 "delta" 字段中
                    if ("finish".equals(delta.getString("status"))) {
                        break;
                    }
                    if (delta.containsKey("text")) {
                        String speechContent = delta.getString("text");
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
                } else {
                    if (StringUtils.isNotBlank(line.trim())) {
                        JSONObject jsonResponse = JSON.parseObject(line.trim());
                        Integer rspCode = jsonResponse.getInteger("code");
                        if (null != rspCode && rspCode == 1003) {
                            JiutianTokenUtils.refreshToken(apiKey);
                            break;
                        }
                    }
                }
            }

            String answer = responseBuilder.toString();
            logger.info("{} recv llm-jiutian response end flag. answer={}", this.uuid, answer);
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

    public static void main(String[] args) {

    }
}
