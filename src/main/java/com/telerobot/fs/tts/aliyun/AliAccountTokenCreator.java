package com.telerobot.fs.tts.aliyun;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;
import com.telerobot.fs.config.ThreadLocalTraceId;
import com.telerobot.fs.entity.dto.AlibabaTokenEntity;
import com.telerobot.fs.entity.dto.AliyunTtsAccount;
import com.telerobot.fs.entity.po.HangupCause;
import com.telerobot.fs.robot.RobotBase;
import com.telerobot.fs.utils.StringUtils;
import link.thingscloud.freeswitch.esl.util.CurrentTimeMillisClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AliAccountTokenCreator {

    private static AlibabaTokenEntity aliToken = new AlibabaTokenEntity();
    private static final Logger log = LoggerFactory.getLogger(AliAccountTokenCreator.class);

    public static void initToken(){
        aliToken.setAccessKeyId("");
        aliToken.setSecret("");
        aliToken.setAppkey("");
        aliToken.setToken("");
        aliToken.setExpreTime(0L);
    }

    static {
        initToken();
    }

    private static JSONObject generateToken(String accessKeyId, String accessKeySecret) throws ClientException {
        String REGIONID = "cn-shanghai";
        String DOMAIN = "nls-meta.cn-shanghai.aliyuncs.com";
        String API_VERSION = "2019-02-28";
        String REQUEST_ACTION = "CreateToken";
        // 创建DefaultAcsClient实例并初始化
        DefaultProfile profile = DefaultProfile.getProfile(REGIONID, accessKeyId, accessKeySecret);
        IAcsClient client = new DefaultAcsClient(profile);
        CommonRequest request = new CommonRequest();
        request.setDomain(DOMAIN);
        request.setVersion(API_VERSION);
        request.setAction(REQUEST_ACTION);
        request.setMethod(MethodType.POST);
        request.setProtocol(ProtocolType.HTTP);
        CommonResponse response = client.getCommonResponse(request);
        log.info(response.getData());
        if (response.getHttpStatus() == 200) {
            JSONObject result = JSON.parseObject(response.getData());
            return result;
        } else {
            log.info("aliyun tts generateToke error!");
            return null;
        }
    }

    public static AlibabaTokenEntity getToken(AliyunTtsAccount account){
        Long expiredMillsLeft = aliToken.getExpreTime() - CurrentTimeMillisClock.now();
        //过期前1小时
        if (StringUtils.isNullOrEmpty(aliToken.getToken()) || expiredMillsLeft < 3600000 || aliToken.getExpreTime() == 0) {
            synchronized (aliToken.getAccessKeyId().intern()) {
                if (StringUtils.isNullOrEmpty(aliToken.getToken()) ||
                        expiredMillsLeft < 3600000 || aliToken.getExpreTime() == 0) {
                    try {
                        log.info("Token for the account is not exists or expired. Initiating token renewal. AccessKeyId={} ", aliToken.getAccessKeyId());
                        aliToken.setAccessKeyId(account.getAccess_key_id());
                        aliToken.setSecret(account.getAccess_key_secret());
                        aliToken.setAppkey(account.getApp_key());

                        JSONObject result = generateToken(account.getAccess_key_id(), account.getAccess_key_secret());
                        if(null != result) {
                            String token = result.getJSONObject("Token").getString("Id");
                            long expireTime = result.getJSONObject("Token").getLongValue("ExpireTime") * 1000;
                            aliToken.setToken(token);
                            aliToken.setExpreTime(expireTime);
                            log.info("alibabaAccount, accessId={}, appKey={}, token={}, expiredTime={}",
                                    aliToken.getAccessKeyId(),
                                    aliToken.getAppkey(),
                                    token,
                                    expireTime
                            );
                        }else{
                            log.error("aliShortTextTTSWebAPI error: cant not get token." );
                            return null;
                        }
                    } catch (Exception e) {
                        String traceId =  ThreadLocalTraceId.getInstance().getTraceId();
                        if(!StringUtils.isNullOrEmpty(traceId)) {
                            log.error("{} RobotBase.setHangupCauseByUuid = {}", traceId, HangupCause.TTS_SERVER_CONNECTED_FAILED.getCode());
                            RobotBase.setHangupCauseByUuid(traceId, HangupCause.TTS_SERVER_CONNECTED_FAILED, e.toString());
                        }
                        log.error("aliShortTextTTSWebAPI error: " + e.toString());
                        return null;
                    }
                }
            }
        }
        return  aliToken;
    }

}
