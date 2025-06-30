package com.telerobot.fs.entity.dto.llm;

import lombok.Data;

@Data
public class CozeAccount extends  AccountBaseEntity {

    private String botId;

    /**
     *  available tokenType enums:
     *  oauth、pat
     */
    private String tokenType;

    private String patToken;

    private String oauthClientId;
    private String oauthPrivateKey;
    private String oauthPublicKeyId;

}
