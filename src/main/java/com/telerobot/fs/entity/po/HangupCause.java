package com.telerobot.fs.entity.po;


public enum HangupCause {

    LLM_API_SERVER_ERROR("llm_api_server_error", "llm server response error."),

    LLM_API_KEY_INCORRECT("llm_api_key_incorrect", "api key for llm is incorrect"),

    LLM_API_NETWORK_ERROR( "llm_api_network_error", "network error, request llm api error."),

    SYSTEM_INTERNAL_ERROR( "system_internal_error", "system internal error, cant not process request."),

    ASR_ACCOUNT_INFO_INCORRECT("asr_account_info_incorrect", "Asr account info is incorrect."),

    TTS_ACCOUNT_INFO_INCORRECT("tts_account_info_incorrect", "TTS account info is incorrect."),

    TTS_SERVER_CONNECTED_FAILED("tts_server_connected_failed", "can not connect to tts server."),

    ASR_SERVER_CONNECTED_FAILED("asr_server_connected_failed", "can not connect to asr server.");

    private String code;

    private String description;

    HangupCause(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }


}
