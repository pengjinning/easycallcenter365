package com.telerobot.fs.robot.impl;

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
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.List;

public class LocalWavFile extends AbstractChatRobot {

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question, Boolean... withKbResponse) {
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


        if (firstRound) {
            firstRound = false;

            String llmTips = ((LlmAccount) getAccount()).getLlmTips();
            String faqContent = ((LlmAccount) getAccount()).getFaqContext();

            String tips = llmTips;
            if (StringUtils.isNotBlank(faqContent)
                    && !"-".equals(faqContent)) {
                tips = tips + "\r\n\r\n" + faqContent;
            }
            addDialogue(ROLE_SYSTEM, tips);

            String openingRemarks = replaceParams(llmAccountInfo.openingRemarks, bizJson);

            addDialogue(ROLE_ASSISTANT, openingRemarks);

            ttsTextCache.add(openingRemarks);

            if (StringUtils.isNotBlank(llmAccountInfo.openingRemarksWav)) {
                aiphoneRes.setTtsFilePathList(llmAccountInfo.openingRemarksWav);
            }
            if (StringUtils.isNotBlank(llmAccountInfo.transferToAgentTipsWav)) {
                llmAccountInfo.transferToAgentTips = llmAccountInfo.transferToAgentTipsWav;
            }
            if (StringUtils.isNotBlank(llmAccountInfo.hangupTipsWav)) {
                llmAccountInfo.hangupTips = llmAccountInfo.hangupTipsWav;
            }
            logger.info("{},openingRemarksWav:{}", this.uuid, aiphoneRes.getTtsFilePathList());
            aiphoneRes.setBody(openingRemarks);

            return aiphoneRes;
        } else {

            if (!StringUtils.isEmpty(question)) {
                addDialogue(ROLE_USER, question);
            } else {
                addDialogue(ROLE_USER, "NO_VOICE");
                String noVoiceTips = llmAccountInfo.customerNoVoiceTips;
                addDialogue(ROLE_ASSISTANT, noVoiceTips);
                ttsTextCache.add(noVoiceTips);
                if (StringUtils.isNotBlank(llmAccountInfo.customerNoVoiceTipsWav)) {
                    aiphoneRes.setTtsFilePathList(llmAccountInfo.customerNoVoiceTipsWav);
                }
                logger.info("{},customerNoVoiceTipsWav:{}", this.uuid, aiphoneRes.getTtsFilePathList());
                aiphoneRes.setBody(noVoiceTips);

                return aiphoneRes;
            }

            try {
                JSONObject response = sendNoneStreamingRequest(aiphoneRes, llmRoundMessages, bizJson, question);
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



    private  JSONObject sendNoneStreamingRequest(LlmAiphoneRes aiphoneRes, List<JSONObject> messages, JSONObject bizJson, String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        // 模型名称
        requestBody.put("model", ((LlmAccount)getAccount()).getModelName());
        // 流式响应
        requestBody.put("stream", false);
        JSONArray messagesArray = new JSONArray();
        messagesArray.addAll(messages);
        // 对话上下文（包括客户最近说的一句话）
        requestBody.put("messages", messagesArray);
        // 随路数据（即客户信息）
        requestBody.put("custInfo", bizJson);
        // 客户刚刚说的话
        requestBody.put("question", question);
        // 本通电话的唯一标识
        requestBody.put("uuid", uuid);
        logger.info("请求参数:{}", requestBody.toJSONString());

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .addHeader("Connection", "keep-alive")
                .addHeader("Authorization", "Bearer " + ((LlmAccount)getAccount()).getApiKey())
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Model api error: http-code={}, msg={}, url={}",
                        response.code(),
                        response.message(),
                        getAccount().serverUrl
                );
            }

            String chatContent = "";
            // {"code":200, "data":{"choices":[{"delta":{"content":"xxxxxxx", "wavFilePath":"/home/Records/251224101457010001/20260113161253001914.wav"}}]}}
            JSONObject result = JSONObject.parseObject(response.body().string());
            logger.info("{} recv local response：{}", uuid, result);
            String wavFiles = "";
            if (result.getInteger("code") == 200 && null != result.getJSONObject("data")) {
                JSONArray choices = result.getJSONObject("data").getJSONArray("choices");
                if (null != choices && choices.size() > 0) {
                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                    chatContent = delta.getString("content");
                    wavFiles = delta.getString("wavFilePath");

                    if (chatContent.contains(LlmToolRequest.TRANSFER_TO_AGENT)) {
                        aiphoneRes.setTransferToAgent(1);
                        logger.info("{} `TRANSFER_TO_AGENT` command detected. ", getTraceId());
                    }

                    if (chatContent.contains(LlmToolRequest.HANGUP)) {
                        aiphoneRes.setClose_phone(1);
                        logger.info("{} `HANGUP` command detected. ", getTraceId());
                    }

                    if (!StringUtils.isEmpty(chatContent)) {
                        chatContent = chatContent.replace(LlmToolRequest.TRANSFER_TO_AGENT,"")
                                .replace(LlmToolRequest.HANGUP,"")
                                .replace("`","");
                        ttsTextCache.add(chatContent);
                        ttsTextLength += chatContent.length();
                    }
                }
            }

            logger.info("{} recv llm response end flag. answer={}, wavFiles={}", this.uuid, chatContent, wavFiles);
            aiphoneRes.setTtsFilePathList(wavFiles);

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("role", "assistant");
            finalResponse.put("content", chatContent);
            aiphoneRes.setBody(chatContent);
            return finalResponse;
        }
    }
}
