package com.telerobot.fs.entity.dto.llm;

 public class AccountBaseEntity {

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


    public String voiceSource;

    public String voiceCode;

}
