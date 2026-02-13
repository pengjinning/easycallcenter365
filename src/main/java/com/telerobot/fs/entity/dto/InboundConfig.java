package com.telerobot.fs.entity.dto;

import lombok.Data;

@Data
public class InboundConfig {
    private int id;
    private int llmAccountId;
    private String callee;
    private String voiceCode;
    private String voiceSource;
    private String asrProvider;
    private String serviceType;
    private String aiTransferType;
    private String aiTransferData;
    private String ivrId;
    private String satisfSurveyIvrId;
}
