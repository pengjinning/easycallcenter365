package com.telerobot.fs.robot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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
import com.telerobot.fs.global.BizThreadPoolForEsl;
import com.telerobot.fs.global.CdrPush;
import com.telerobot.fs.ivr.IvrEngine;
import com.telerobot.fs.service.CallTaskService;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.RegExp;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.utils.ThreadUtil;
import com.telerobot.fs.wshandle.WebsocketThreadPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.constant.UuidKeys;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  TransferToAgent
 */
public class TransferToAgent {

    private static final Logger logger = LoggerFactory.getLogger(TransferToAgent.class);

    /**
     *  transfer to acd queue
     */
    public static final String TRANSFER_TO_ACD = "acd";

    /**
     *  transfer to external gateway
     */
    public static final String TRANSFER_TO_GATEWAY = "gateway";

    /**
     *  transfer to extension
     */
    public static final String  TRANSFER_TO_EXTENSION = "extension";


    /**
     *  transfer a call session to acd queue or an external gateway
     * @param callDetail
     */
    public  static  void transfer(InboundDetail callDetail, AccountBaseEntity account){
        String transferType = account.aiTransferType;
        String transferData = account.aiTransferData;
        String satisfSurveyIvrId = account.satisfSurveyIvrId;
        transfer(callDetail, transferType, transferData, satisfSurveyIvrId);
    }

    public  static  void transfer(InboundDetail callDetail,  String transferType,
                                  String transferData, String satisfSurveyIvrId) {
        logger.info("{} transfer-to-agent-type = {} .", callDetail.getUuid(), transferType);
        AccountBaseEntity account = new AccountBaseEntity();
        account.satisfSurveyIvrId = satisfSurveyIvrId;
        account.aiTransferData = transferData;
        account.aiTransferType = transferType;

        if(transferType.equalsIgnoreCase(TRANSFER_TO_ACD)) {
            logger.info("{} Try to add call to acd queue, aiTransferData={}.", callDetail.getUuid(),  transferData);
            String groupId = "0";
            if(transferData != null && !StringUtils.isNullOrEmpty(transferData.trim())){
                groupId = transferData.trim();
            }
            callDetail.setGroupId(groupId);
            CallHandler callHandler = new CallHandler(callDetail);
            callHandler.setSatisfSurveyIvrId(satisfSurveyIvrId);
            if (InboundGroupHandler.addCallToQueue(callHandler, groupId)) {
                logger.info("{} Successfully add call to acd queue, groupId={}.", callDetail.getUuid(), callDetail.getGroupId());
            }
        }else  if(transferType.equalsIgnoreCase(TRANSFER_TO_GATEWAY)) {
            logger.info("{} Try to bridge call to external gateway. {}", callDetail.getUuid(),  transferData);
            transferToAgentUsingGateway(callDetail, account);
        }else  if(transferType.equalsIgnoreCase(TRANSFER_TO_EXTENSION)) {
            logger.info("{} Try to bridge call to internal extension. {}", callDetail.getUuid(), transferData);
            transferToAgentUsingExtension(callDetail, account);
        }
    }


    private static void bridgeCall(InboundDetail inboundDetail, List<String> calleeList,
                                   String bridgeString, String calleeUuid, int callTimeout,
                                   AccountBaseEntity account){
        EslConnectionUtil.sendExecuteCommand(
                "set",
                "park_after_bridge=true",
                inboundDetail.getUuid(),
                EslConnectionUtil.getDefaultEslConnectionPool()
        );
        ThreadUtil.sleep(300);

        final AtomicBoolean answered = new AtomicBoolean(false);
        Semaphore continueSignal = new Semaphore(0);
        boolean playedWaitMusic = false;
        while (!inboundDetail.getHangup() && !answered.get()) {
            for (String calleeNumber : calleeList) {
                if (inboundDetail.getHangup() || answered.get()) {
                    break;
                }
                inboundDetail.setCallee(calleeNumber);
                inboundDetail.setExtnum(calleeNumber);
                inboundDetail.setOpnum(calleeNumber);

                final AtomicBoolean calleeHangup = new AtomicBoolean(false);
                TransferListener listener = new TransferListener(inboundDetail, calleeUuid,
                        account, answered, continueSignal, calleeHangup);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(calleeUuid, listener);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(inboundDetail.getUuid(), listener);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn()
                        .removeOtherListenersExcludeByUuidKeys(inboundDetail.getUuid(),
                                new String[]{UuidKeys.DEFAULT, UuidKeys.BATCH_CALL}
                );

                if(!playedWaitMusic) {
                    playedWaitMusic = true;
                    listener.playWaitMusic();
                    listener.waitForPlayBackStartSignal();
                    ThreadUtil.sleep(200);
                }

                String originateStr =  bridgeString.replace("callee_number", calleeNumber);
                String jobId = EslConnectionUtil.sendAsyncApiCommand(
                        "originate",
                        originateStr
                );
                if(!StringUtils.isNullOrEmpty(jobId)){
                    logger.info("{} get originate jobId: {} ", inboundDetail.getUuid(), jobId);
                    EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(jobId, listener);
                }

                try {
                    if (!inboundDetail.getHangup() && !answered.get()) {
                        long timeout =  (callTimeout + 5) * 1000L;
                        continueSignal.tryAcquire(1, timeout, TimeUnit.MILLISECONDS);

                        if(!answered.get() && !calleeHangup.get()){
                            logger.info("{} originate call for callee  timeout, hangup session.", inboundDetail.getUuid());
                            EslConnectionUtil.sendExecuteCommand("hangup", "", calleeUuid);
                        }
                    }
                } catch (InterruptedException e) {
                }

                ThreadUtil.sleep(3000);
            }
        }
    }


    /**
     *  For simplicity, when transferring calls to extensions, uniformly use the same queue name.
     *  There is no distinction between outbound calls and inbound calls.
     *
     * @param inboundDetail
     */
    private static void transferToAgentUsingExtension(InboundDetail inboundDetail,AccountBaseEntity account) {
        String extensions = account.aiTransferData;
        List<String> extensionList = RegExp.GetMatchFromStringByRegExp(extensions, "\\d{4}");
        if (extensionList.size() == 0) {
            logger.error("invalid extensions, can not transfer to extension.");
            return;
        }

        int callTimeout = 30;
        String calleeUuid = UuidGenerator.GetOneUuid();
        String bridgeString = String.format(
                " {absolute_codec_string=pcma,origination_uuid=%s,hangup_after_bridge=false,originate_timeout=%d,origination_caller_id_number=%s,effective_caller_id_number=%s}user/%s  &park",
                calleeUuid,
                callTimeout,
                inboundDetail.getCaller(),
                inboundDetail.getCaller(),
                "callee_number"
        );

        bridgeCall(inboundDetail, extensionList, bridgeString, calleeUuid, callTimeout, account);
    }

    /**
     *  When the external line is connected,
     *  transfer the customer's call to a manual agent
     *  while suppressing the ring-back tone during the intermediate steps.
     * @param inboundDetail
     * @param account
     */
    private static void transferToAgentUsingGateway(InboundDetail inboundDetail, AccountBaseEntity account) {
        String gatewayJson = account.aiTransferData;
        JSONObject jsonObject = JSON.parseObject(gatewayJson);
        int gatewayId = jsonObject.getInteger("gatewayId");
        String destPhone = "callee_number";
        GatewayConfig gatewayInfo = AppContextProvider.getBean(CallTaskService.class).getGatewayConfigById(gatewayId);

        String extraParams = SystemConfig.getValue("outbound-call-extra-params-for-profile-"+  gatewayInfo.getCallProfile() , "");
        String extraParamsFinal =  extraParams.length() == 0 ? "" : "," + extraParams ;

        String outboundUuid = UuidGenerator.GetOneUuid();
        int callTimeout = 50;
        String callerNumber = CommonUtils.getCallerNumberRandomly(gatewayInfo.getCallerNumber());
        String bridgeString = String.format(
                "{hangup_after_bridge=false,absolute_codec_string=%s,originate_timeout=%d,origination_uuid=%s,origination_caller_id_number=%s,effective_caller_id_number=%s%s}sofia/%s/%s%s@%s  &park",
                gatewayInfo.getAudioCodec(),
                callTimeout,
                outboundUuid,
                callerNumber,
                callerNumber,
                extraParamsFinal,
                gatewayInfo.getCallProfile(),
                gatewayInfo.getCalleePrefix(),
                destPhone,
                gatewayInfo.getGatewayAddr()
        );

        if(gatewayInfo.getRegister() == 1){
            bridgeString = String.format(
                    "{hangup_after_bridge=false,absolute_codec_string=%s,originate_timeout=%d,origination_uuid=%s,origination_caller_id_number=%s,effective_caller_id_number=%s%s}sofia/gateway/%s/%s%s  &park",
                    gatewayInfo.getAudioCodec(),
                    callTimeout,
                    outboundUuid,
                    callerNumber,
                    callerNumber,
                    extraParamsFinal,
                    gatewayInfo.getGwName(),
                    gatewayInfo.getCalleePrefix(),
                    destPhone
            );
        } else if(gatewayInfo.getRegister() == 2) {
            String authUsername = gatewayInfo.getAuthUsername();
            String dynamicGateway = CommonUtils.getDynamicGatewayAddr(authUsername, inboundDetail.getUuid());
            logger.info("{} successfully get dynamic gateway address : {}", inboundDetail.getUuid(), dynamicGateway);
            // for dynamic gateway, we must use internal profile
            bridgeString = String.format("{hangup_after_bridge=false,absolute_codec_string=%s,originate_timeout=%d,origination_uuid=%s,origination_caller_id_number=%s,effective_caller_id_number=%s%s}sofia/internal/%s%s@%s  &park",
                    gatewayInfo.getAudioCodec(),
                    callTimeout,
                    outboundUuid,
                    callerNumber,
                    callerNumber,
                    extraParamsFinal,
                    gatewayInfo.getCalleePrefix(),
                    destPhone,
                    dynamicGateway
            );
        }

        List<String> calleeList = new ArrayList<>(6);
        calleeList.add(jsonObject.getString("destNumber"));
        bridgeCall(inboundDetail, calleeList, bridgeString, outboundUuid, callTimeout, account);
    }

}
