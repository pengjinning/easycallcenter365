package com.telerobot.fs.entity.dto.llm;

 public class AccountBaseEntity {

    public int id;

    public String serverUrl;

    public String provider;

    /**
     * The voice prompt played when  transferring to agent tips string
     */
    public String transferToAgentTips;

    /**
     * The voice prompt played when call session hangup
     */
    public String hangupTips;

    /**
     * tips for No Voice
     */
    public String customerNoVoiceTips;

    /**
     * The opening remarks of a phone call
     */
    public String openingRemarks;


    public String kbQueryTopicNotFoundTips;

    public String  llmTryFailedTips;

    public String voiceSource;

    public String voiceCode;

    public String asrLanguageCode;
    public String ttsLanguageCode;
    public String asrModels;
    public String ttsModels;

    public String asrProvider;

    /**
     * way of transferring to manual:  acd、extension、gateway
     */
    public String aiTransferType;

    /**
     *  Data of specific manual transfer methods
     */
    public String aiTransferData;

    /**
     *   ivr id for inbound call session
     */
    public String ivrId;

    /**
     *  the IVR menu ID for satisfaction surveys
     */
    public String satisfSurveyIvrId;

    /**
     *   voice interruption supported during the robot's speech:
     *   1: Keyword interruption, 0: No interruption, 2: Interrupt if there is a sound
     */
    public int interruptFlag;

    /**
     *  List of keywords that support speech interruption.
     */
    public String interruptKeywords;

    /**
     *  List of keywords excluded from triggering speech interruption.
     */
    public String interruptIgnoreKeywords;

    public int concurrentNum;

    public String transferManualDigit;

    public int kbCatId;



    /**
     * The voice prompt played when  transferring to agent tips string (wav file)
     */
    public String transferToAgentTipsWav;

    /**
     * The voice prompt played when call session hangup (wav file)
     */
    public String hangupTipsWav;

    /**
     * tips for No Voice (wav file)
     */
    public String customerNoVoiceTipsWav;

    /**
     * The opening remarks of a phone call (wav file)
     */
    public String openingRemarksWav;
}
