package com.telerobot.fs.tts.aliyun;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.dto.AlibabaTokenEntity;
import com.telerobot.fs.entity.dto.AliyunTtsAccount;
import com.telerobot.fs.utils.StringUtils;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

public class AliyunTTSWebApi {
    private static final Logger log = LoggerFactory.getLogger(AliyunTTSWebApi.class);
    private static String ttsAccountJson = null;
    private static AliyunTtsAccount ttsAccount = null;

    private static OkHttpClient client;
    static{
        int ttsThreadNum = 20;
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(ttsThreadNum, 12L, TimeUnit.HOURS))
                .build();
    }

    private static boolean processPOSTRequest(String accessToken, String voiceCode, String text, String audioSaveFile) {
        /**
         * 设置HTTPS POST请求
         * 1.使用HTTPS协议
         * 2.语音合成服务域名：nls-gateway.cn-shanghai.aliyuncs.com
         * 3.语音合成接口请求路径：/stream/v1/tts
         * 4.设置必须请求参数：appkey、token、text、format、sample_rate
         * 5.设置可选请求参数：voice、volume、speech_rate、pitch_rate
         */

        String url = ttsAccount.getServer_url_webapi();
        JSONObject taskObject = new JSONObject();
        taskObject.put("appkey", ttsAccount.getApp_key());
        taskObject.put("token", accessToken);
        taskObject.put("text", text);
        taskObject.put("format", "wav");
        taskObject.put("voice", voiceCode);
        taskObject.put("sample_rate", 8000);
        // volume 音量，范围是0~100，可选，默认50
        taskObject.put("volume",   ttsAccount.getVoice_volume() );
        // speech_rate 语速，范围是-500~500，可选，默认是0
        taskObject.put("speech_rate",  ttsAccount.getSpeech_rate() );
        // pitch_rate 语调，范围是-500~500，可选，默认是0
        // taskObject.put("pitch_rate", 0);
        String bodyContent = taskObject.toJSONString();
        RequestBody reqBody = RequestBody.create(MediaType.parse("application/json"), bodyContent);
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(reqBody)
                .build();
        try {
            Response response = client.newCall(request).execute();
            String contentType = response.header("Content-Type");
            if ("audio/mpeg".equals(contentType)) {
                File f = new File(audioSaveFile);
                FileOutputStream fout = new FileOutputStream(f);
                fout.write(response.body().bytes());
                fout.close();
                response.close();
                return true;
            }else {
                // ContentType 为 null 或者为 "application/json"
                String errorMessage = response.body().string();
                response.close();
                log.error("Aliyun tts:  the post request failed: " + errorMessage);
                JSONObject json = JSON.parseObject(errorMessage);
                Integer status = json.getInteger("status");
                return false;
            }
        } catch (Exception e) {
            log.error("Aliyun tts合成失败：{}", e.toString());
            return false;
        }
    }

    public static boolean setAliyunTokenToFreeSWITCH(String uuid){
        AlibabaTokenEntity token = getToken();
        if (token != null) {
            log.info("{} set FreeSWITCH channel variables, aliyun_tts_token={}, aliyun_tts_app_key={} ",
                    uuid, token.getToken().substring(0, 7) + "*******", token.getAppkey()
            );
            EslConnectionUtil.sendExecuteCommand("set", "aliyun_tts_token=" + token.getToken(), uuid);
            EslConnectionUtil.sendExecuteCommand("set", "aliyun_tts_app_key=" + token.getAppkey(), uuid);
            return true;
        }
        return false;
    }

    public static AlibabaTokenEntity getToken(){
        boolean accountUpdated = !SystemConfig.getValue("aliyun-tts-account-json", "").equals(ttsAccountJson);
        if(StringUtils.isNullOrEmpty(ttsAccountJson) || accountUpdated) {
            synchronized (AliyunTTSWebApi.class) {
                accountUpdated = !SystemConfig.getValue("aliyun-tts-account-json", "").equals(ttsAccountJson);
                if (StringUtils.isNullOrEmpty(ttsAccountJson) || accountUpdated) {
                    AliAccountTokenCreator.initToken();
                    ttsAccountJson = SystemConfig.getValue("aliyun-tts-account-json", "");
                    if (!StringUtils.isNullOrEmpty(ttsAccountJson)) {
                        try {
                            ttsAccount = JSON.parseObject(ttsAccountJson, AliyunTtsAccount.class);
                        } catch (Throwable e) {
                            log.error("parse `aliyun-tts-account-json` error! ");
                            return null;
                        }
                    } else {
                        log.error("param `aliyun-tts-account-json` cant not be null.");
                        return null;
                    }
                }
            }
        }
        if(ttsAccount == null){
            return null;
        }
        return AliAccountTokenCreator.getToken(ttsAccount);
    }

    public static boolean shortTextTTSWebAPI(String voiceCode, String text, String ttsPath) {
         if (!org.apache.commons.lang.StringUtils.isNotEmpty(text.trim())) {
            log.info("tts text cant not be null, ttsPath:" + ttsPath);
            return true;
        }

        AlibabaTokenEntity token = getToken();
        if(token == null){
            log.error("ttsAccount is null, cant not process tts request.");
            return false;
        }
        return processPOSTRequest(token.getToken(), voiceCode, text, ttsPath);
    }
}