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

public class OpenRouterChat extends AbstractChatRobot {

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
                    return aiphoneRes;
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
                logger.error("{} talkWithAiAgent error: {} \n {}", uuid, throwable.toString(), CommonUtils.getStackTraceString(throwable.getStackTrace()));
            }
            return aiphoneRes;
        }
    }


    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, List<JSONObject> messages) throws IOException {

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", ((LlmAccount)getAccount()).getModelName());
        requestBody.put("stream", true);
        requestBody.put("max_tokens", 1024);
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
                .addHeader("Content-Type", "application/json")
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

//            : OPENROUTER PROCESSING
//
//            : OPENROUTER PROCESSING
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":"The question \"What is the meaning of life?\" is perhaps the oldest and deepest","role":"assistant"},"finish_reason":null,"native_finish_reason":null}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":" in human history. Because there is no single, scientifically proven answer, humanity has developed several different frameworks to address it:\n\n###","role":"assistant"},"finish_reason":null,"native_finish_reason":null}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":" 1. The Biological Perspective\nFrom a purely evolutionary standpoint, the meaning of life is **survival and continuity**.\n","role":"assistant"},"finish_reason":null,"native_finish_reason":null}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":"*   **Propagation:** Passing on genetic information to the next generation.\n*   **Adaptation:** Ensuring the species continues","role":"assistant"},"finish_reason":null,"native_finish_reason":null}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":" to thrive in a changing","role":"assistant"},"finish_reason":null,"native_finish_reason":null}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"length","native_finish_reason":"MAX_TOKENS"}]}
//
//            data: {"id":"gen-1776655591-gv8XcijGknBdKl0BnFcb","object":"chat.completion.chunk","created":1776655591,"model":"google/gemini-3-flash-preview-20251217","provider":"Google","choices":[{"index":0,"delta":{"content":"","role":"assistant"},"finish_reason":"length","native_finish_reason":"MAX_TOKENS"}],"usage":{"prompt_tokens":7,"completion_tokens":96,"total_tokens":103,"cost":0.0002915,"is_byok":false,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0,"audio_tokens":0,"video_tokens":0},"cost_details":{"upstream_inference_cost":0.0002915,"upstream_inference_prompt_cost":0.0000035,"upstream_inference_completions_cost":0.000288},"completion_tokens_details":{"reasoning_tokens":0,"image_tokens":0,"audio_tokens":0}}}
//
//            data: [DONE]

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                try {
//                    logger.info("{}-->{}", uuid, line);
                    if (line != null && line.startsWith("data: ")) {
                        String jsonData = line.substring(5).trim(); // 去掉 "data: " 前缀
                        if (jsonData.equals("[DONE]")) {
                            break; // 流式响应结束
                        }

                        JSONObject jsonResponse = JSON.parseObject(jsonData);
                        JSONObject message = jsonResponse.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta"); // 注意：流式响应中消息在 "delta" 字段中

                        if (null != message && message.containsKey("content")) {
                            String speechContent = message.getString("content");
                            logger.info("{} speechContent: {}", getTraceId(), speechContent);

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
                                    if (ttsTextLength >= 5 && checkPauseFlag(speechContent)) {
                                        sendToTts();

                                        if (!recvData) {
                                            recvData = true;
                                            long costTime = (System.currentTimeMillis() - startTime);
                                            logger.info("http request cost time : {} ms.", costTime);
                                            aiphoneRes.setCostTime(costTime);
                                        }
                                    }
                                }
                                responseBuilder.append(speechContent);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.info("{}-->{}", uuid, line);
                    logger.error("parse llm response error：{} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
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
            aiphoneRes.setBody(answer);
            return finalResponse;
        }
    }
}
