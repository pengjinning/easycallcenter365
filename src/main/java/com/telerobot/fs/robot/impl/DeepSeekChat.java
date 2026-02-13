package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.entity.dao.LlmKb;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.LlmAccount;
import com.telerobot.fs.entity.po.HangupCause;
import com.telerobot.fs.entity.pojo.LlmToolRequest;
import com.telerobot.fs.robot.AbstractChatRobot;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.utils.CommonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.List;

public class DeepSeekChat extends AbstractChatRobot {

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question, Boolean... withKbResponse) {
        LlmAiphoneRes aiphoneRes = new LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);
        if (firstRound) {
            firstRound = false;

            String llmTips = ((LlmAccount) getAccount()).getLlmTips();
            int catId = llmAccountInfo.kbCatId;
            String topicsVar = "${kbTopicList}";
            if(catId != -1) {
                if (llmTips.contains(topicsVar)) {
                    List<LlmKb> kbList = AppContextProvider.getBean(SysService.class).getKbListByCatId(catId);
                    StringBuilder topics = new StringBuilder();
                    for (LlmKb llmKb : kbList) {
                        topics.append("*").append(llmKb.getTitle()).append("\r\n");
                    }
                    topics.append("\r\n");
                    llmTips = llmTips.replace(topicsVar, topics.toString());
                    logger.info("{} topic list: {}", uuid, topics.toString());
                } else {
                    logger.warn("{} {} tag not found, the knowledge base function will be unavailable. ", uuid, topicsVar);
                }
            }else {
                logger.warn("{} The current model {} doesn’t have a knowledge base linked to it. ", uuid, ((LlmAccount) getAccount()).getModelName());
            }

            String tips = llmTips  + "\r\n\r\n" + ((LlmAccount) getAccount()).getFaqContext();
            JSONObject bizJson = new JSONObject();
            if (null != callDetail && null != callDetail.getOutboundPhoneInfo() && StringUtils.isNotBlank(callDetail.getOutboundPhoneInfo().getBizJson())) {
                tips += "\n bizJson:" + callDetail.getOutboundPhoneInfo().getBizJson();
                bizJson = JSONObject.parseObject(callDetail.getOutboundPhoneInfo().getBizJson());
            }
            addDialogue(ROLE_SYSTEM, tips);

            String openingRemarks = replaceParams(llmAccountInfo.openingRemarks, bizJson);

            addDialogue(ROLE_ASSISTANT, openingRemarks);

            ttsTextCache.add(openingRemarks);
            sendToTts();
            closeTts();

            aiphoneRes.setBody(openingRemarks);
            return aiphoneRes;
        } else {
            if (withKbResponse.length > 0 && !withKbResponse[0]) {
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

            try {
                JSONObject response = sendStreamingRequest(aiphoneRes, llmRoundMessages);
                if (null != response) {
                    llmRoundMessages.add(response);
                } else {
                    aiphoneRes.setStatus_code(0);
                }
            } catch (Throwable throwable) {
                aiphoneRes.setStatus_code(0);
                logger.error("{} talkWithAiAgent error: {}", uuid, CommonUtils.getStackTraceString(throwable.getStackTrace()));
            }
            return aiphoneRes;
        }
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
        long startTime = System.currentTimeMillis();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Model api error: http-code={}, msg={}, url={}",
                        response.code(),
                        response.message(),
                        getAccount().serverUrl
                );
                if(response.code() == HttpStatus.SC_UNAUTHORIZED) {
                   CommonUtils.setHangupCauseDetail(
                           callDetail,
                           HangupCause.LLM_API_KEY_INCORRECT,
                           "http-status-code=" + response.code()
                   );
                  CommonUtils.hangupCallSession(uuid,  HangupCause.LLM_API_KEY_INCORRECT.getCode());
                  return null;
                }else{
                    CommonUtils.setHangupCauseDetail(
                            callDetail,
                            HangupCause.LLM_API_SERVER_ERROR,
                            "http-status-code=" + response.code()
                    );
                }

                if(response.code() == HttpStatus.SC_UNAUTHORIZED || response.code() >= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                    throw new IOException("Unexpected code " + response);
                }else{
                    return null;
                }
            }

            BufferedSource source = response.body().source();
            StringBuilder responseBuilder = new StringBuilder();

            Integer completionTokens = 0; // 模型生成回复转换为 Token 后的长度。
            Integer promptTokens = 0; // 用户的输入转换成 Token 后的长度。

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

                            if (speechContent.contains(LlmToolRequest.TRANSFER_TO_AGENT)) {
                                aiphoneRes.setTransferToAgent(1);
                                logger.info("{} `TRANSFER_TO_AGENT` command detected. ", getTraceId());
                            }

                            if (speechContent.contains(LlmToolRequest.HANGUP)) {
                                aiphoneRes.setClose_phone(1);
                                logger.info("{} `HANGUP` command detected. ", getTraceId());
                            }

                            if (!StringUtils.isEmpty(speechContent)) {
                                speechContent = speechContent.replace(LlmToolRequest.TRANSFER_TO_AGENT,"")
                                        .replace(LlmToolRequest.HANGUP,"")
                                        .replace("`","");
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

                    if (null != jsonResponse.get("usage")) {
                        JSONObject usage = jsonResponse.getJSONObject("usage");
                        completionTokens = usage.getInteger("completion_tokens");
                        promptTokens = usage.getInteger("prompt_tokens");
                    }
                }
            }

            String answer = responseBuilder.toString();
            logger.info("{} recv llm response end flag. answer={}", this.uuid, answer);
            if(ttsTextLength > 0){
                sendToTts();
            }
            closeTts();

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("role", "assistant");
            finalResponse.put("content", answer);
            finalResponse.put("completionTokens", completionTokens); // 模型生成回复转换为 Token 后的长度。
            finalResponse.put("promptTokens", promptTokens); // 用户的输入转换成 Token 后的长度。
            aiphoneRes.setBody(answer);
            return finalResponse;
        }
    }
}
