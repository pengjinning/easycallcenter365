package com.telerobot.fs.entity.dto;

import lombok.Data;

@Data
public class InboundConfig {
    private int id;
    private int llmAccountId;
    private String callee;
    private String voiceCode;
    private String voiceSource;
    private String serviceType;
    private int groupId;
}
