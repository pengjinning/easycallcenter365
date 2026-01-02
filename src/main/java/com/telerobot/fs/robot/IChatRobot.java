package com.telerobot.fs.robot;

import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

public interface IChatRobot {

    Semaphore concurrentNum = new Semaphore(0);

    /**
     * Account parameter information for accessing the large model or ai-agent
     * @param llmAccount
     */
    void setAccount(AccountBaseEntity llmAccount);

    AccountBaseEntity getAccount();

    void flushTtsRequestQueue();

    /**
     *  talk with llm or ai-agent.
     * @param question
     * @return LlmAiphoneRe
     */
    LlmAiphoneRes talkWithAiAgent(String question);

    /**
     *  set uuid of call session
     * @param uuid
     */
    void setUuid(String uuid);


    /**
     *  通话记录信息
     */
    void setCallDetail(InboundDetail callDetail);

    /**
     *  获取对话内容
     * @return
     */
    String getDialogues();

    /**
     *  sendTtsRequest
     * @param text
     */
    void sendTtsRequest(String text);

    /**
     *  close tts channel
     */
    void closeTts();

    /**
     *  标记语音合成的tts通道状态
     */
    void setTtsChannelState(TtsChannelState state);

    void setTtsProvider(String provider);

    void setTtsVoiceName(String voiceName);
}
