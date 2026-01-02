package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.telerobot.fs.acd.AcdSqlQueue;
import com.telerobot.fs.acd.CallHandler;
import com.telerobot.fs.acd.InboundGroupHandler;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dto.GatewayConfig;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.global.CdrPush;
import com.telerobot.fs.service.CallTaskService;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.RegExp;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.utils.ThreadUtil;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 *  TransferToAgent
 */
public class TransferToAgent {

    private static final Logger logger = LoggerFactory.getLogger(TransferToAgent.class);

    /**
     *  transfer to acd queue
     */
    private static final String TRANSFER_TO_ACD = "acd";

    /**
     *  transfer to external gateway
     */
    private static final String TRANSFER_TO_GATEWAY = "gateway";

    /**
     *  transfer to extension
     */
    private static final String  TRANSFER_TO_EXTENSION = "extension";


    /**
     *  transfer a call session to acd queue or an external gateway
     * @param callDetail
     */
    public  static  void transfer(InboundDetail callDetail, String transferType, String transferData){
        logger.info("{} transfer-to-agent-type = {} .", callDetail.getUuid(), transferType);
        if(transferType.equalsIgnoreCase(TRANSFER_TO_ACD)) {
            logger.info("{} Try to add call to acd queue .", callDetail.getUuid());
            String groupId = "0";
            if(transferData != null && StringUtils.isNullOrEmpty(transferData.trim())){
                groupId = transferData.trim();
            }
            callDetail.setGroupId(groupId);
            CallHandler callHandler = new CallHandler(callDetail);
            if (InboundGroupHandler.addCallToQueue(callHandler, callDetail.getGroupId())) {
                logger.info("{} Successfully add call to acd queue", callDetail.getUuid());
            }
        }else  if(transferType.equalsIgnoreCase(TRANSFER_TO_GATEWAY)) {
            logger.info("{} Try to bridge call to external gateway. {}", callDetail.getUuid(),  transferData);
            transferToAgentUsingGateway(callDetail, transferData);
        }else  if(transferType.equalsIgnoreCase(TRANSFER_TO_EXTENSION)) {
            logger.info("{} Try to bridge call to internal extension. {}", callDetail.getUuid(), transferData);
            transferToAgentUsingExtension(callDetail, transferData);
        }
    }

    /**
     *  For simplicity, when transferring calls to extensions, uniformly use the same queue name.
     *  There is no distinction between outbound calls and inbound calls.
     *
     *  If the extension needs to answer a call from a human,
     *  it should dial 611. To stop answering, it should dial 612
     * @param inboundDetail
     */
    private static void transferToAgentUsingExtension(InboundDetail inboundDetail, String extensions) {
        List<String> extensionList = RegExp.GetMatchFromStringByRegExp(extensions, "\\d{4}");
        if (extensionList.size() == 0) {
            logger.error("invalid extensions, can not transfer to extension.");
            return;
        }

        EslConnectionUtil.sendExecuteCommand(
                "playback",
                "$${sounds_dir}/ivr/keep.wav",
                inboundDetail.getUuid()
        );

        int callTimeout = 30;
        final boolean[] answered = {false};
        Semaphore signal = new Semaphore(0);
        while (!inboundDetail.getHangup() && !answered[0]) {
            for (String extNum : extensionList) {
                if (inboundDetail.getHangup() || answered[0]) {
                    break;
                }
                String extensionUuid = UuidGenerator.GetOneUuid();
                String bridgeString = String.format(
                        " {origination_uuid=%s,hangup_after_bridge=true,originate_timeout=%d,origination_caller_id_number=%s,effective_caller_id_number=%s}user/%s  &park",
                        extensionUuid,
                        callTimeout,
                        inboundDetail.getCaller(),
                        inboundDetail.getCaller(),
                        extNum.trim()
                );

                inboundDetail.setCallee(extNum);
                inboundDetail.setExtnum(extNum);
                inboundDetail.setOpnum(extNum);

                IEslEventListener listener = new IEslEventListener() {
                    private volatile boolean hangup = false;
                    private Semaphore parkSemaphore = new Semaphore(0);
                    @Override
                    public synchronized void eventReceived(String addr, EslEvent event) {
                        Map<String, String> headers = event.getEventHeaders();
                        String uniqueId = headers.get("Unique-ID");
                        String eventName = headers.get("Event-Name");
                        if (EventNames.CHANNEL_ANSWER.equalsIgnoreCase(eventName)) {
                            if(extensionUuid.equalsIgnoreCase(uniqueId)) {
                                try {
                                    parkSemaphore.tryAcquire(1200L, TimeUnit.MILLISECONDS);
                                }catch (Throwable e){
                                }

                                inboundDetail.setAnsweredTime(System.currentTimeMillis());
                                answered[0] = true;
                                signal.release();

                                EslConnectionUtil.sendSyncApiCommand("uuid_break" , String.format("%s all", inboundDetail.getUuid()));
                                ThreadUtil.sleep(1000);

                                logger.info("{} extension answered, try to bridge call session.", inboundDetail.getUuid());
                                String bridgeParam = extensionUuid + " " +  inboundDetail.getUuid();
                                EslMessage message = EslConnectionUtil.sendSyncApiCommand("uuid_bridge", bridgeParam);

                                logger.info("{} call session bridge result: {}.", inboundDetail.getUuid(), JSON.toJSONString(message));
                            }
                        }if(EventNames.CHANNEL_PARK.equalsIgnoreCase(eventName)){
                            if(extensionUuid.equalsIgnoreCase(uniqueId)) {
                                parkSemaphore.release();
                            }
                        }
                        else if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
                            signal.release();

                            if (uniqueId.equalsIgnoreCase(inboundDetail.getUuid())) {
                                inboundDetail.setHangup(true);
                                if (inboundDetail.getAnsweredTime() > 0L) {
                                    inboundDetail.setAnsweredTimeLen(System.currentTimeMillis() - inboundDetail.getAnsweredTime());
                                }
                                inboundDetail.setTimeLen(System.currentTimeMillis() - inboundDetail.getInboundTime());
                                inboundDetail.setHangupTime(System.currentTimeMillis());
                                AcdSqlQueue.addToSqlQueue(inboundDetail);
                                CdrDetail cdrDetail = new CdrDetail();
                                cdrDetail.setUuid(inboundDetail.getUuid());

                                if (inboundDetail.getOutboundPhoneInfo() == null) {
                                    cdrDetail.setCdrType("inbound");
                                } else {
                                    cdrDetail.setCdrType("outbound");
                                }
                                // 推送话单;
                                cdrDetail.setCdrBody(JSON.toJSONString(inboundDetail));
                                CdrPush.addCdrToQueue(cdrDetail);
                                logger.info("{} cdr has been pushed.", inboundDetail.getUuid());
                            }
                        }
                    }

                    @Override
                    public void backgroundJobResultReceived(String addr, EslEvent event) {
                    }
                };
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(extensionUuid, listener);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(inboundDetail.getUuid(), listener);
                logger.info("{} try to call extension: {}", inboundDetail.getUuid(), bridgeString);
                EslConnectionUtil.sendAsyncApiCommand(
                        "originate",
                        bridgeString
                );

                try {
                    if (!inboundDetail.getHangup() && !answered[0]) {
                        long timeout =  (callTimeout + 5) * 1000L;
                        signal.tryAcquire(1, timeout, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private static void transferToAgentUsingGateway(InboundDetail inboundDetail, String gatewayJson) {

        JSONObject jsonObject = JSON.parseObject(gatewayJson);
        int gatewayId = jsonObject.getInteger("gatewayId");
        String destPhone = jsonObject.getString("destNumber");
        GatewayConfig gatewayInfo = AppContextProvider.getBean(CallTaskService.class).getGatewayConfigById(gatewayId);

        String extraParams = SystemConfig.getValue("outbound-call-extra-params-for-profile-"+  gatewayInfo.getCallProfile() , "");
        String extraParamsFinal =  extraParams.length() == 0 ? "" : extraParams + ",";

        String outboundUuid = UuidGenerator.GetOneUuid();
        String bridgeString = String.format(
                "{origination_uuid=%s,origination_caller_id_number=%s,effective_caller_id_number=%s%s}sofia/%s/%s%s@%s ",
                outboundUuid,
                gatewayInfo.getCallerNumber(),
                gatewayInfo.getCallerNumber(),
                extraParamsFinal,
                gatewayInfo.getCallProfile(),
                gatewayInfo.getCalleePrefix(),
                destPhone,
                gatewayInfo.getGatewayAddr()
        );
        //设置bridge后挂机;
        EslConnectionUtil.sendExecuteCommand(
                "set",
                "hangup_after_bridge=true",
                inboundDetail.getUuid(),
                EslConnectionUtil.getDefaultEslConnectionPool()
        );
        inboundDetail.setCallee(destPhone);
        inboundDetail.setExtnum(destPhone);
        inboundDetail.setOpnum(destPhone);

        IEslEventListener listener = new IEslEventListener() {
            private volatile boolean hangup = false;
            @Override
            public synchronized void eventReceived(String addr, EslEvent event) {
                Map<String, String> headers = event.getEventHeaders();
                String uniqueId = headers.get("Unique-ID");
                String eventName = headers.get("Event-Name");
                if (EventNames.CHANNEL_ANSWER.equalsIgnoreCase(eventName)) {
                    inboundDetail.setAnsweredTime(System.currentTimeMillis());
                } else if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
                    if(!hangup){
                        hangup = true;
                        // 推送话单;
                        if(inboundDetail.getAnsweredTime() > 0L) {
                            inboundDetail.setAnsweredTimeLen(System.currentTimeMillis() - inboundDetail.getAnsweredTime());
                        }
                        inboundDetail.setTimeLen(System.currentTimeMillis() - inboundDetail.getInboundTime());
                        inboundDetail.setHangupTime(System.currentTimeMillis());
                        AcdSqlQueue.addToSqlQueue(inboundDetail);
                        CdrDetail cdrDetail = new CdrDetail();
                        cdrDetail.setUuid(inboundDetail.getUuid());

                        if(inboundDetail.getOutboundPhoneInfo() == null) {
                            cdrDetail.setCdrType("inbound");
                        }else{
                            cdrDetail.setCdrType("outbound");
                        }

                        cdrDetail.setCdrBody(JSON.toJSONString(inboundDetail));
                        CdrPush.addCdrToQueue(cdrDetail);
                        logger.info("{} cdr has been pushed.", inboundDetail.getUuid());
                    }
                    String uuidKill = "";
                    if(uniqueId.equals(outboundUuid)){
                        uuidKill = inboundDetail.getUuid();
                    }else{
                        uuidKill = outboundUuid;
                    }
                    EslConnectionUtil.sendAsyncApiCommand("uuid_kill", uuidKill);
                }
            }
            @Override
            public void backgroundJobResultReceived(String addr, EslEvent event) {

            }
        };

        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(outboundUuid, listener);
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(inboundDetail.getUuid(), listener);
        EslConnectionUtil.sendExecuteCommand(
                "bridge",
                bridgeString,
                inboundDetail.getUuid()
        );
    }

}
