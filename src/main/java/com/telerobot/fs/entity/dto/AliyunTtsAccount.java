package com.telerobot.fs.entity.dto;

import lombok.Data;

@Data
public class AliyunTtsAccount {

    private String server_url;
    private String server_url_webapi;
    private String access_key_id;
    private String access_key_secret;
    private String app_key;
    private String voice_name;
    private int speech_rate;
    private int voice_volume;
    private int sample_rate;
}
