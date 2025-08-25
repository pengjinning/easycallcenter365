package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class AbstractChatRobot implements IChatRobot {

    protected final static Logger logger = LoggerFactory.getLogger(AbstractChatRobot.class);
    protected volatile boolean firstRound = true;
    protected static final String ROLE_USER = "user";
    protected static final String ROLE_SYSTEM = "system";
    protected static final String ROLE_ASSISTANT = "assistant";

    protected volatile InboundDetail callDetail;

    protected AccountBaseEntity llmAccountInfo;

    protected String ttsProvider = "";

    protected String ttsVoiceName = "";

    private static int requestTimeout = Integer.parseInt(SystemConfig.getValue("llm-conn-timeout", "3100"));

    protected static final OkHttpClient CLIENT =  new OkHttpClient.Builder()
            .connectTimeout(requestTimeout,
                    TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeout, TimeUnit.MILLISECONDS)
            .build();

    protected String uuid;

    @Override
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public void setCallDetail(InboundDetail callDetail){
        this.callDetail = callDetail;
    }

    /**
     *  tts通道已关闭
     */
    protected volatile boolean ttsChannelClosed = true;

    protected String getTraceId(){
        return uuid;
    }

    protected List<JSONObject> llmRoundMessages = new ArrayList<>();
    protected int ttsTextLength = 0;
    protected ArrayBlockingQueue<String> ttsTextCache = new ArrayBlockingQueue<String>(2000);
    private final  String[] pauseFlags = new String[]{
            "？", "?",
            "，", ",",
            "；", ";",
            "。", ".",
            "、",
            "！", "!",
            "：", ":"
    };
    protected boolean checkPauseFlag(String input){
        String lastChar = input.substring(input.length() - 1);
        for (String flag : pauseFlags) {
            if(flag.equalsIgnoreCase(lastChar)){
                return true;
            }
        }
        return false;
    }
    protected void sendToTts() {
        StringBuilder tmpText = new StringBuilder("");
        while (ttsTextCache.peek() != null) {
            tmpText.append(ttsTextCache.poll());
        }
        String text = tmpText.toString();
        sendTtsRequest(text);
        ttsTextLength = 0;
    }

    @Override
    public  void setAccount(AccountBaseEntity llmAccount){
      this.llmAccountInfo = llmAccount;
    }
    @Override
    public AccountBaseEntity getAccount(){
       return llmAccountInfo;
    }

    @Override
    public void sendTtsRequest(String textParam){
        if(StringUtils.isEmpty(textParam)){
            return;
        }
        String text = textParam
                .replace("\\", "")
                .replace("*", " ")
                .replace("\n", " ");

        if(ttsChannelClosed) {
            EslConnectionUtil.sendExecuteCommand("speak", String.format("%s|%s|%s", ttsProvider, ttsVoiceName, text), uuid);
            ttsChannelClosed = false;
            logger.info("{} sendTtsRequest speak tts text {}", uuid, text);
        }else{
            EslConnectionUtil.sendExecuteCommand(ttsProvider + "_resume", text, uuid);
            logger.info("{} sendTtsRequest tts_resume text {}", uuid, text);
        }
    }

    @Override
    public void setTtsProvider(String provider){
        this.ttsProvider = provider;
    }

    @Override
    public void setTtsVoiceName(String voiceName){
      this.ttsVoiceName = voiceName;
    }

    /**
     *  关闭tts通道
     */
    @Override
    public  void closeTts(){
        if(!ttsChannelClosed) {
            EslConnectionUtil.sendExecuteCommand(ttsProvider + "_resume", "<StopSynthesis/>", uuid);
        }
    }

    protected  void addDialogue(String role, String content){
        // 避免重试时重复记录
        if (llmRoundMessages.size() > 0) {
            JSONObject lastMessage = llmRoundMessages.get(llmRoundMessages.size() - 1);
            if (role.equals(lastMessage.getString("role"))
                    && StringUtils.isNotBlank(content)
                    && content.equals(lastMessage.getString("content"))) {
                return;
            }
        }
        JSONObject userMessage = new JSONObject();
        userMessage.put("role",  role);
        userMessage.put("content",  content);
        userMessage.put("content_type", "text");
        llmRoundMessages.add(userMessage);
    }

    @Override
    public String getDialogues(){
        return JSON.toJSONString(llmRoundMessages);
    }

    @Override
    public void setTtsChannelState(boolean closed) {
       this.ttsChannelClosed = closed;
    }

    @Override
    public abstract LlmAiphoneRes talkWithAiAgent(String question);
}
