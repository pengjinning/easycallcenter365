package com.telerobot.fs.entity.dao;

import lombok.Data;

@Data
public class LlmAgentAccount {

    private int id;
    private String accountJson;
    private String providerClassName;
    private String accountEntity;
    private String name;
}
