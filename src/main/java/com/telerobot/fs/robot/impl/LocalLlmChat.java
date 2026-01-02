package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
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
import java.util.List;

public class LocalLlmChat extends AbstractChatRobot {

//    public static final String HANGUP = "{<conversation_end>}";
    public static final String HANGUP = "hangupCall";

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question) {
        LlmAiphoneRes aiphoneRes = new  LlmAiphoneRes();
        aiphoneRes.setStatus_code(1);
        aiphoneRes.setClose_phone(0);
        aiphoneRes.setIfcan_interrupt(0);

        // 获取随路数据
        JSONObject bizJson = JSONObject.parseObject(callDetail.getOutboundPhoneInfo().getBizJson());
        logger.info("随路数据:{}", bizJson);
        logger.info("大模型接口地址:{}", getAccount().serverUrl);

        if(firstRound) {
            firstRound = false;
//            String tips = ((LlmAccount)getAccount()).getLlmTips() + "\n" + ((LlmAccount)getAccount()).getFaqContext();
//            addDialogue(ROLE_SYSTEM, tips);

            String openingRemarks = bizJson.getString("welcomeMessage");
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
        }

        return aiphoneRes;
    }



    private  JSONObject sendStreamingRequest(LlmAiphoneRes aiphoneRes, JSONObject bizJson, String question) throws IOException {
        JSONObject requestBody = new JSONObject();
        // 默认为 0 -2.0 到 2.0 之间的数字。正值根据文本目前的存在频率惩罚新标记,降低模型重复相同行的可能性。
        requestBody.put("frequencyPenalty", 0.2);
        // 修改指定标记出现在补全中的可能性。接受一个 JSON 对象,该对象将标记(由标记器指定的标记 ID)映射到相关的偏差值(-100 到 100)。
        requestBody.put("logitBias", new JSONObject());
        // 默认为 inf 在聊天补全中生成的最大标记数。
        requestBody.put("maxTokens", 1024);
        // 至今为止对话所包含的消息列表。
        JSONObject message = new JSONObject();
        message.put("content", question);
        message.put("role", "user");
        JSONArray paramsMessages = new JSONArray();
        paramsMessages.add(message);
        requestBody.put("messages", paramsMessages);
        // 要使用的模型的 ID。有关哪些模型可与聊天 API 一起使用的详细信息,请参阅模型端点兼容性表。
        // gpt-3.5-turbo
        requestBody.put("model", ((LlmAccount)getAccount()).getModelName());
        // 默认为 1 为每个输入消息生成多少个聊天补全选择。
        requestBody.put("n", 1);
        // -2.0 和 2.0 之间的数字。正值会根据到目前为止是否出现在文本中来惩罚新标记，从而增加模型谈论新主题的可能性。
        requestBody.put("presencePenalty", 1.0);
        // 指定模型必须输出的格式的对象。将 { "type": "json_object" } 启用 JSON 模式。
        requestBody.put("responseFormat", "text");
//        // 此功能处于测试阶段。如果指定,我们的系统将尽最大努力确定性地进行采样。
//        requestBody.put("seen", 1);
//        // 默认为 null 最多 4 个序列,API 将停止进一步生成标记。
//        requestBody.put("stop", "1");
        // 默认为 false 如果设置,则像在 ChatGPT 中一样会发送部分消息增量。
        requestBody.put("stream", true);
        // 使用什么采样温度，介于 0 和 2 之间。
        requestBody.put("temperature", 1.0);
        // 控制模型调用哪个函数(如果有的话)。
        requestBody.put("toolChoice", "");
        // 模型可以调用的一组工具列表。目前,只支持作为工具的函数。
        requestBody.put("tools", new JSONArray());
        // 一种替代温度采样的方法，称为核采样。
        requestBody.put("topP", 0.95);
        // 代表您的最终用户的唯一标识符，可以帮助 OpenAI 监控和检测滥用行为。
        requestBody.put("user", "easycallcenter365");
        // 通用变量
        requestBody.put("variables", new JSONArray());
        requestBody.put("outboundType", 1);
        requestBody.put("question", question);
        logger.info("请求参数:{}", requestBody.toJSONString());

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                requestBody.toJSONString()
        );

        JSONObject bizParam = new JSONObject();
        bizParam.put("question_chain_id", bizJson.getString("questionChainId"));
        Request request = new Request.Builder()
                .url(getAccount().serverUrl)
                .post(body)
                .addHeader("Biz-Param", JSONObject.toJSONString(bizParam))
                .addHeader("User-Agent", "Apifox/1.0.0 (https://apifox.com)")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                // .addHeader("Host", "xgentapidev.health.techxgent.com")
                .addHeader("Connection", "keep-alive")
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
                // data:{"id":"chatcmpl-cd5eeb3e7d5f44ea9526f322a40b6","object":"chat.completion.chunk","created":"1763626633","model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{}}]}
                // data:{"id":"chatcmpl-ed70d3e952dc40a7a8272249aed2e","object":"chat.completion.chunk","created":"1763604699","model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{"content":"了解了，那我们就不打扰了，感谢您的接听，祝您生活愉快，再见。 "}}]}
                // event:done
                // data:hangupCall
                String line = source.readUtf8Line();
                logger.info("{}接收到的消息：{}", uuid, line);
                if (line != null && line.startsWith("data:")) {
                    String jsonData = line.substring(5).trim(); // 去掉 "data: " 前缀
                    if (jsonData.equals("[DONE]") || !jsonData.startsWith("{")) {
                        continue; // 流式响应结束
                    }
                    String speechContent = "";
                    if (jsonData.equals(HANGUP)) {
                        speechContent = speechContent + jsonData;
                        logger.info("{}接收到了挂机指令！", uuid);
                    } else {
                        JSONObject jsonResponse = JSON.parseObject(jsonData);
                        JSONObject choice = jsonResponse.getJSONArray("choices")
                                .getJSONObject(0);
//                        if ("stop".equals(choice.getString("finish_reason"))) {
//                            break; // 流式响应结束
//                        }
                        JSONObject delta = choice.getJSONObject("delta"); // 注意：流式响应中消息在 "delta" 字段中

                        if (delta.containsKey("content")) {
                            speechContent = delta.getString("content");
                        }
                    }
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

                        if (speechContent.contains(HANGUP)) {
                            aiphoneRes.setClose_phone(1);
                            logger.info("{} `HANGUP` command detected. ", getTraceId());
                        }

                        if (!StringUtils.isEmpty(speechContent)) {
                            speechContent = speechContent.replace(LlmToolRequest.TRANSFER_TO_AGENT,"")
                                    .replace(HANGUP,"")
                                    .replace("`","");
                            ttsTextCache.add(speechContent);
                            ttsTextLength += speechContent.length();
                            // 积攒足够的字数之后，才发送给tts，避免播放异常;
                            if (!StringUtils.isEmpty(speechContent) && ttsTextLength >= 10 && checkPauseFlag(speechContent)) {
                                sendToTts();
                            }
                        }
                        responseBuilder.append(speechContent);
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
