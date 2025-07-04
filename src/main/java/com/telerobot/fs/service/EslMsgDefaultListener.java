package com.telerobot.fs.service;

import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.po.ExtensionRegisterInfo;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.ThreadPoolCreator;
import com.telerobot.fs.wshandle.MessageHandlerEngine;
import com.telerobot.fs.wshandle.MessageHandlerEngineList;
import com.telerobot.fs.wshandle.MessageResponse;
import com.telerobot.fs.wshandle.RespStatus;
import link.thingscloud.freeswitch.esl.EslConnectionPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Component
@DependsOn({"fsEslStarter"})
public class EslMsgDefaultListener implements ApplicationListener<ApplicationReadyEvent>, IEslEventListener {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(EslMsgDefaultListener.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        startApplication();
                    }
                }
        ).start();
    }

    private static ThreadPoolExecutor asrResultListenerThread = null;
    private static EslConnectionPool eslConnectionPool = null;
    /**
     * 启动asr实时结果监听
     */
    private void startApplication() {
        asrResultListenerThread = ThreadPoolCreator.create(10, "eslMsgDefaultListenerThread", 12L, 1000);
        eslConnectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
        eslConnectionPool.getDefaultEslConn().addDefaultListener(this);
        logger.info("EslMsgDefaultListener thread is running.");
    }

    @Override
    public void eventReceived(String addr, EslEvent event) {
        asrResultListenerThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    processFsMsg(event.getEventHeaders());
                } catch (Exception e) {
                    logger.error("{} processFsMsg error : {}", e.toString(),
                            CommonUtils.getStackTraceString(e.getStackTrace()));
                }
            }
        });
    }

    private static final String SOFIA_REGISTER_EVENT = "sofia::register";
    private static final String SOFIA_UN_REGISTER_EVENT = "sofia::unregister";
    private static final String UNKNOWN = "unknown";

    public void processFsMsg(Map<String, String> headers) {
        String eventName = headers.get("Event-Name");
        if (EventNames.CUSTOM.equalsIgnoreCase(eventName)) {
            String eventSubClass = headers.get("Event-Subclass");
            if (SOFIA_REGISTER_EVENT.equalsIgnoreCase(eventSubClass)) {
                logger.info("recv extension register event : {}", eventSubClass);
                if(!UNKNOWN.equalsIgnoreCase(headers.get("username"))) {
                    sendNotice(RespStatus.EXTENSION_ON_LINE, "Extension online.", headers);
                }
            }
            if (SOFIA_UN_REGISTER_EVENT.equalsIgnoreCase(eventSubClass)) {
                logger.info("recv extension un-register event : {}", eventSubClass);
                if(!UNKNOWN.equalsIgnoreCase(headers.get("username"))) {
                    sendNotice(RespStatus.EXTENSION_OFF_LINE, "Extension offline.", headers);
                }
            }
        }
    }

    private void sendNotice(int statusCode, String msg, Map<String, String> headers){
        ExtensionRegisterInfo registerInfo  = new ExtensionRegisterInfo();
        registerInfo.setUsername(headers.get("username"));
        registerInfo.setProfile(headers.get("profile-name"));
        registerInfo.setUserAgent(headers.get("user-agent"));
        registerInfo.setNetworkIp(headers.get("network-ip"));
        registerInfo.setStatus(headers.get("status"));

        MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByExtNum(registerInfo.getUsername());
        if(engine != null) {
            engine.sendReplyToAgent(new MessageResponse(statusCode, msg, registerInfo));
        }
    }

    @Override
    public void backgroundJobResultReceived(String addr, EslEvent event) {

    }
}
