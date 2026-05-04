package com.telerobot.fs.entity.pojo;

public class TtsFileInfo {
    private String filesString;
    private Long timeLength = 0L;
    private int ttsFileNumber = 0;

    public TtsFileInfo(String filesString, Long timeLength, int ttsFileNumber) {
        this.filesString = filesString;
        this.timeLength = timeLength;
        this.ttsFileNumber = ttsFileNumber;
    }

    public String getFilesString() {
        return filesString;
    }

    public int getTtsFileNumber() {
        return ttsFileNumber;
    }

    public Long getTimeLength() {
        return timeLength;
    }

}
