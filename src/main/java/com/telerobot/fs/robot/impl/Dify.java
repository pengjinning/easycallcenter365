package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;

public class Dify extends AbstractChatRobot {
    private String chat_id = "";

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question, Boolean... withKbResponse) {
        LlmAiphoneRes aiphoneRes = new LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);
        if(firstRound) {
            firstRound = false;

            JSONObject bizJson = new JSONObject();
            if (null != callDetail && null != callDetail.getOutboundPhoneInfo() && StringUtils.isNotBlank( callDetail.getOutboundPhoneInfo().getBizJson())) {
                bizJson = JSONObject.parseObject(callDetail.getOutboundPhoneInfo().getBizJson());
            }
            String openingRemarks = replaceParams(llmAccountInfo.openingRemarks, bizJson);
            addDialogue(ROLE_ASSISTANT, openingRemarks);

            ttsTextCache.add(openingRemarks);
            sendToTts();
            closeTts();

            aiphoneRes.setBody(openingRemarks);
            return aiphoneRes;
        }else{
            if (!StringUtils.isEmpty(question)) {
                addDialogue(ROLE_USER, question);
            } else {
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
                JSONObject response = sendStreamingRequest(aiphoneRes, question);
                if(null != response) {
                    llmRoundMessages.add(response);
                }else{
                    aiphoneRes.setStatus_code(0);
                }
            } catch (Throwable throwable) {
                aiphoneRes.setStatus_code(0);
                logger.error("{} talkWith Dify error: {}", uuid, CommonUtils.getStackTraceString(throwable.getStackTrace()));
            }
        }

        return aiphoneRes;
    }

    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        CustmInfoEntity custmInfoEntity= callDetail.getOutboundPhoneInfo();
        JSONObject inputs = new JSONObject();
        if (null != custmInfoEntity) {
            if (StringUtils.isNotBlank(custmInfoEntity.getBizJson())) {
                JSONObject bizJson = JSONObject.parseObject(custmInfoEntity.getBizJson());
                for (String k: bizJson.keySet()) {
                    inputs.put(k, bizJson.get(k));
                }
            }
        }
        requestBody.put("inputs", inputs);
        requestBody.put("query", question);
        requestBody.put("response_mode", "streaming");
        if (!StringUtils.isEmpty(chat_id)) {
            requestBody.put("conversation_id", chat_id);
            logger.info("{} set Dify chat_id = {}", uuid, chat_id);
        }
        requestBody.put("user", "abc-123");

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " +  ((LlmAccount)getAccount()).getApiKey())
                .build();

        boolean recvData = false;
        boolean jsonFormat = false;
        long startTime = System.currentTimeMillis();
        logger.info("{} http request startTime: {} ms.", uuid, startTime);
        logger.info("{} http request url: {}.", uuid, getAccount().serverUrl);
        logger.info("{} http request Authorization: {}.", uuid, ((LlmAccount)getAccount()).getApiKey());
        logger.info("{} http request requestBody: {}.", uuid, requestBody.toJSONString());

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Dify api error: http-code={}, msg={}, url={}",
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

//   data: {"event": "message", "message_id": "5ad4cb98-f0c7-4085-b384-88c403be6290", "conversation_id": "45701982-8118-4bc5-8e9b-64562b4555f2", "answer": " I", "created_at": 1679586595}
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                logger.info(line);
                if (line != null && line.startsWith("data: ")) {
                    String jsonData = line.substring(6).trim(); // 去掉 "data: " 前缀
                    JSONObject jsonResponse = JSON.parseObject(jsonData);
                    if ("message".equalsIgnoreCase(jsonResponse.getString("event"))) {
                        String conversation_id = jsonResponse.getString("conversation_id");
                        if (!StringUtils.isEmpty(conversation_id)) {
                            chat_id = conversation_id;
                            logger.info("{} successfully get Dify chat_id = {}", uuid, chat_id);
                        }
                        String speechContent = jsonResponse.getString("answer");
                        if (StringUtils.isEmpty(speechContent)) {
                            continue;
                        }
                        if (!recvData) {
                            recvData = true;
                            long costTime = (System.currentTimeMillis() - startTime);
                            logger.info("http request cost time : {} ms.", costTime);
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
            logger.info("{} recv Dify response, answer={}", this.uuid, answer);
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
        String question = "就是不满意";
        String chat_id = "";
        String uuid = "";
        String serverUrl = "http://192.168.67.228:9997/v1/chat-messages";
//        String apiKey = "app-ZbTK2aN54wrQVhfIVqh3CleE";
        String apiKey = "app-Onkj6oMgv6JhRbRIFODPzzFn";
        JSONObject requestBody = new JSONObject();
        requestBody.put("inputs", new JSONObject());
        requestBody.put("query", question);
        requestBody.put("response_mode", "streaming");
        if (!StringUtils.isEmpty(chat_id)) {
            requestBody.put("conversation_id", chat_id);
            logger.info("{} set Dify chat_id = {}", uuid, chat_id);
        }
        requestBody.put("user", "abc-123");

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        Request request = new Request.Builder()
                .url(serverUrl)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        boolean recvData = false;
        boolean jsonFormat = false;
        long startTime = System.currentTimeMillis();
        logger.info("{} http request startTime: {} ms.", uuid, startTime);

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Dify api error: http-code={}, msg={}, url={}",
                        response.code(),
                        response.message(),
                        serverUrl
                );
                throw new IOException("Unexpected code " + response);
            }

            BufferedSource source = response.body().source();
            StringBuilder responseBuilder = new StringBuilder();

            //   data: {"event": "message", "message_id": "5ad4cb98-f0c7-4085-b384-88c403be6290", "conversation_id": "45701982-8118-4bc5-8e9b-64562b4555f2", "answer": " I", "created_at": 1679586595}
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                logger.info(line);
                if (line != null && line.startsWith("data: ")) {
                    String jsonData = line.substring(6).trim(); // 去掉 "data: " 前缀
                    JSONObject jsonResponse = JSON.parseObject(jsonData);
                    if ("message".equalsIgnoreCase(jsonResponse.getString("event"))) {
                        String conversation_id = jsonResponse.getString("conversation_id");
                        if (!StringUtils.isEmpty(conversation_id)) {
                            chat_id = conversation_id;
                            logger.info("{} successfully get Dify chat_id = {}", uuid, chat_id);
                        }
                        String speechContent = jsonResponse.getString("answer");
                        if (StringUtils.isEmpty(speechContent)) {
                            continue;
                        }
                        if (!recvData) {
                            recvData = true;
                            long costTime = (System.currentTimeMillis() - startTime);
                            logger.info("http request cost time : {} ms.", costTime);
                        }

                        if (!StringUtils.isEmpty(speechContent)) {
                            //  send to tts server
                            String tmpText = speechContent.trim().replace(" ", "").replace(" ", "");
                            if (tmpText.startsWith("{") && !jsonFormat) {
                                jsonFormat = true;
                            }

                            if (!StringUtils.isEmpty(tmpText) && !jsonFormat) {
                                logger.info(tmpText);
                            }
                            responseBuilder.append(tmpText);
                        }
                    }
                }
            }

            String answer = responseBuilder.toString();
            logger.info("{} recv Dify response, answer={}", uuid, answer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
