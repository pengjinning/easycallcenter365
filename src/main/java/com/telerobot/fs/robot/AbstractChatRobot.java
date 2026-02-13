package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.po.HangupCause;
import com.telerobot.fs.entity.pojo.LlmToolRequest;
import com.telerobot.fs.utils.RegExp;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import net.sf.json.regexp.RegexpUtils;
import okhttp3.OkHttpClient;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractChatRobot implements IChatRobot {

    protected final static Logger logger = LoggerFactory.getLogger(AbstractChatRobot.class);
    protected volatile boolean firstRound = true;
    protected static final String ROLE_USER = "user";
    protected static final String ROLE_SYSTEM = "system";
    protected static final String ROLE_ASSISTANT = "assistant";

    private ArrayBlockingQueue<String> ttsRequestQueue = new ArrayBlockingQueue<>(200);

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

    @Override
    public InboundDetail getCallDetail( ){
       return this.callDetail;
    }

    /**
     * TtsChannelState
     */
    protected volatile TtsChannelState ttsChannelState = TtsChannelState.CLOSED;

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
    public synchronized void flushTtsRequestQueue(){
       if(TtsChannelState.OPENED.getCode().equals(ttsChannelState.getCode())) {
           while (true) {
               String text = ttsRequestQueue.poll();
               if (StringUtils.isEmpty(text)) {
                   break;
               }
               EslConnectionUtil.sendExecuteCommand(ttsProvider + "_resume", text, uuid);
               logger.info("{} sendTtsRequest tts_resume text {}", uuid, text);
           }
       }else{
           logger.error("{} execute flushTtsRequestQueue error, ttsChannelState is not opened.", uuid);
       }
    }

    @Override
    public void sendTtsRequest(String textParam){
        if(StringUtils.isEmpty(textParam)){
            return;
        }
        // Replace the prompt words for manual transfer in the text with blank spaces.
        if(textParam.contains(LlmToolRequest.TRANSFER_TO_TEL)){
            List<String> matches = RegExp.GetMatchFromStringByRegExp(textParam, LlmToolRequest.TRANSFER_TO_TEL_REGEXP);
            for (String match : matches) {
                textParam = textParam.replace(match, "");
            }
        }

        if(textParam.contains(LlmToolRequest.KB_QUERY)){
            return;
        }

        String text = textParam
                .replace(LlmToolRequest.TRANSFER_TO_AGENT, "")
                .replace(LlmToolRequest.HANGUP, "")
                .replace("\\", "")
                .replace("*", " ")
                .replace("\n", ", ");

        if (StringUtils.isEmpty(text)) {
            return;
        }

        if(TtsChannelState.CLOSED.getCode().equals(ttsChannelState.getCode())) {
            EslConnectionUtil.sendExecuteCommand("speak", String.format("%s|%s|%s", ttsProvider, ttsVoiceName, text), uuid);
            ttsChannelState = TtsChannelState.TRYING_OPEN;
            logger.info("{} sendTtsRequest speak tts text {}", uuid, text);
        }else  if(TtsChannelState.OPENED.getCode().equals(ttsChannelState.getCode()))  {
            ttsRequestQueue.add(text);
            flushTtsRequestQueue();
        }else  if(TtsChannelState.TRYING_OPEN.getCode().equals(ttsChannelState.getCode()))  {
            ttsRequestQueue.add(text);
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
        String cmd = "<StopSynthesis/>";
        if(TtsChannelState.OPENED.getCode().equals(ttsChannelState.getCode()))  {
            ttsRequestQueue.add(cmd);
            flushTtsRequestQueue();
        }else  if(TtsChannelState.TRYING_OPEN.getCode().equals(ttsChannelState.getCode()))  {
            ttsRequestQueue.add(cmd);
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
    public List<JSONObject> getDialogues(){
        return llmRoundMessages;
    }

    @Override
    public void setTtsChannelState(TtsChannelState state) {
        this.ttsChannelState = state;
    }


    public String replaceParams(String speechContent, JSONObject bizJson) {
        // 定义占位符的正则表达式
        Pattern pattern = Pattern.compile("\\{(.*?)\\}");
        Matcher matcher = pattern.matcher(speechContent);
        // 替换所有匹配的占位符
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String variableValue = bizJson.getString(variableName);
            if (StringUtils.isBlank(variableValue)) {
                variableValue = "";
            }
            matcher.appendReplacement(sb, variableValue);
        }
        matcher.appendTail(sb);

        // 返回替换后的文本
        return sb.toString();
    }
}
