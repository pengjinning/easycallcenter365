package com.telerobot.fs.entity.dao;

import lombok.Data;

@Data
public class LlmAgentAccount {

    private int id;
    private String accountJson;
    private String providerClassName;
    private String accountEntity;
    private String name;
    private int interruptFlag;
    private String interruptKeywords;
    private String interruptIgnoreKeywords;
    private int concurrentNum;
    private String transferManualDigit;
}
