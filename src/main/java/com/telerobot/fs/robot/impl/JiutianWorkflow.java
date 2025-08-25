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

public class JiutianWorkflow extends AbstractChatRobot {

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
            }
        }

        if(!firstRound && !StringUtils.isEmpty(question)) {
            try {
                JSONObject response = sendStreamingRequest(aiphoneRes, llmRoundMessages, question);
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
        requestBody.put("type", 1);
        requestBody.put("id", ((JiutianAccount)getAccount()).getBotId());
        JSONObject input = new JSONObject();
        input.put("BOT_USER_INPUT", question);
        input.put("input", question);
        requestBody.put("input", input);

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

            StringBuilder responseBuilder = new StringBuilder();
            String speechContent = "";
            JSONObject result = JSONObject.parseObject(response.body().string());


            logger.info("jiutian response :{}", result);
            if (null != result) {
                Integer rspCode = result.getInteger("code");
                if (null != rspCode && rspCode == 1003) {
                    JiutianTokenUtils.refreshToken(apiKey);
                } else {
                    JSONObject data = result.getJSONObject("data");
                    if (null != data) {
                        String output = data.getString("output");
                        logger.info(output);
                        if (StringUtils.isNotBlank(output)){
                            JSONObject outputObj = JSONObject.parseObject(output);
                            speechContent = outputObj.getJSONArray("output").getString(0);
                        }
                    }
                }
            }


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
