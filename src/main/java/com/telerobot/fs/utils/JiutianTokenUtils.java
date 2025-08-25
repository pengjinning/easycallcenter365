package com.telerobot.fs.utils;

import com.alibaba.fastjson.JSONObject;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;

@Slf4j
public class JiutianTokenUtils {

    private static final Long expSeconds = 24*3600L; // token有效期1天
    private static volatile Long expTime = 0L; // 生成token的时间戳
    private static volatile  String token = "";

    /**
     * 获取token
     * @param apikey
     * @return
     */
    public static synchronized String getToken(String apikey){
        // 到期了清空token
        if (expTime > 0 && expTime < new Date().getTime()) {
            token = "";
        }
        if (StringUtils.isBlank(token)) {
            refreshToken(apikey);
        }
        return token;
    }

    /**
     * 重新生成token
     * @param apikey
     * @return
     */
    public static synchronized String refreshToken(String apikey) {
        try {
            expTime = new Date().getTime() + expSeconds*1000;
            token = generateToken(apikey, expSeconds);
            log.info("重新生成token，expTime:{}, token:{}", expTime, token);
        } catch (JOSEException e) {
            e.printStackTrace();
        }
        return token;
    }

    /**
     * 生成token算法
     * @param apikey
     * @param expSeconds
     * @return
     * @throws JOSEException
     */
    private static String generateToken(String apikey,Long expSeconds) throws JOSEException {
        String[] apikeyArr=apikey.split("\\.",2);//kid.secret
        Date now = new Date();

        JSONObject payload=new JSONObject();
        payload.put("api_key",apikeyArr[0]);
        payload.put("exp",now.getTime()/1000 + expSeconds);
        payload.put("timestamp",now.getTime()/1000);

        //创建JWS对象
        JWSObject jwsObject = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                new Payload(payload.toJSONString()));
        //签名
        jwsObject.sign(new MACSigner(apikeyArr[1]));
        return jwsObject.serialize();
    }

    public static void main(String[] args) {
        String apikey = "6788d91d15e5121287ceb106.LZTDe0T10g7O/xsjv1Xt9IQaQF+C2Ocq";
        String token = getToken(apikey);
        System.out.println(token);
    }
}

