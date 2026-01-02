package com.telerobot.fs.robot;

public enum  TtsChannelState {

    /**
     *  TRYING_OPEN
     */
    TRYING_OPEN(1, "TRYING_OPEN"),

    /**
     *  OPENED
     */
    OPENED(2, "OPENED"),

    /**
     *  CLOSED
     */
    CLOSED(3, "CLOSED");

    TtsChannelState(Integer code, String name) {
        this.code = code;
        this.name = name;
    }
    private Integer code;
    private String name;
    public Integer getCode() {
        return code;
    }
    public String getName() {
        return name;
    }
}
