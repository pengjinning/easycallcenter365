package com.telerobot.fs.entity.dto;

public class AlibabaTokenEntity {
    private    String accessKeyId = "";
    private    String secret = "";
    private    String appkey= "";
    private    String token= "";
    private   long expreTime = 0L;

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAppkey() {
        return appkey;
    }

    public void setAppkey(String appkey) {
        this.appkey = appkey;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getExpreTime() {
        return expreTime;
    }

    public void setExpreTime(long expreTime) {
        this.expreTime = expreTime;
    }
}
