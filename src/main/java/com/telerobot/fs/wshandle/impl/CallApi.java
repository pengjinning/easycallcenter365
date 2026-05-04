package com.telerobot.fs.wshandle.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.telerobot.fs.config.CallConfig;
import com.telerobot.fs.entity.bo.ChanneState;
import com.telerobot.fs.entity.bo.ChannelFlag;
import com.telerobot.fs.entity.bo.ConfMember;
import com.telerobot.fs.entity.dto.CallMonitorInfo;
import com.telerobot.fs.entity.dto.GatewayConfig;
import com.telerobot.fs.entity.pojo.AgentStatus;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.utils.*;
import com.telerobot.fs.wshandle.*;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.UuidGenerator;
//import com.telerobot.fs.predictiveCall.CallConfig;
import link.thingscloud.freeswitch.esl.EslConnectionPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  外呼控制;
 *  控制电话外呼、挂机;
 * @author easycallcenter365@gmail.com
 */
public class CallApi extends MsgHandlerBase {

	protected CallListener listener = null;

    /**
     *  转接座席超时时间
     */
    private static int transferAgentTimeOut = Integer.parseInt(
            SystemConfig.getValue("inbound-transfer-agent-timeout", "30")
    );

    private static  boolean hidePhoneNumber = Boolean.parseBoolean(
            SystemConfig.getValue("hide-inbound-number", "true")
    );

    private static boolean callMonitorEnabled =  Boolean.parseBoolean(
            SystemConfig.getValue("call_monitor_enabled", "false")
    );

	private CallApi thisRef = this;

	@Override
	public void processTask(MsgStruct data) {
		MessageResponse msg = new MessageResponse();
		CallArgs callArgs = null;
		try {
			callArgs = JSON.parseObject(data.getBody(), CallArgs.class);
		} catch (Throwable e) {
			msg.setStatus(400);
			msg.setMsg("invalid json format.");
			sendReplyToAgent(msg);
			return;
		}
		if (callArgs == null) {
			return;
		}
		logger.info("{} recv msg: CallApi: {}", getTraceId(), data.getBody());
		String cmd = callArgs.getCmd();
		if (cmd == null || cmd.length() == 0) {
			Utils.processArgsError("cmd param error", thisRef);
			return;
		}
		switch (cmd) {
			case "startSession":
				startCall(callArgs);
				break;
            case "endSession":
                endCall();
                break;
            case "transferCall":
                transferCall(callArgs);
                break;
            case "transferCallWait":
                transferCallWait(callArgs);
                break;
            case "consultation":
                consultation(callArgs);
                break;
            case "reInviteVideo":
                reInviteVideo();
                break;
            case "playMp4File":
                playMp4File(callArgs);
                break;
            // 从普通通话转接到多人会议
            case "transferToConference":
                transferToConference(callArgs);
                break;
			default:
				msg.setStatus(400);
				msg.setMsg(String.format("method not support :%s", cmd));
				sendReplyToAgent(msg);
				break;
		}
	}

    private synchronized void transferToConference(CallArgs callArgs){
        if (this.getIsDisposed()) {
            return;
        }
        if (this.listener != null) {
            String currentVideoLayOut = callArgs.getArgs().getString("layOut");
            String currentCallType = callArgs.getArgs().getString("callType");
            String currentConfTemplate = callArgs.getArgs().getString("confTemplate");
            MessageResponse checkResp = VideoConfigs.checkVideoConferenceParameters(currentVideoLayOut, currentCallType,  currentConfTemplate);
            if(checkResp != null){
                sendReplyToAgent(checkResp);
                return;
            }
            this.listener.transferToConference(callArgs, this.getSessionInfo());
        }else{
            Utils.processArgsError("通话不存在.", thisRef);
        }
    }

	private void sendCustomerCallToCallWaitHandle(){
        CallWait callWait =  ((CallWait) this.msgHandlerEngine.getMessageHandleByName("callWait"));
        callWait.startCallWait();
        ThreadUtil.sleep(200);
    }

    /**
     * The internal consultation function of the call center is a common one:
     * novices consult experienced employees.
     */
    private void consultation(CallArgs callArgs) {

        if(this.listener == null || this.listener.getCustomerChannel() == null){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "no inbound call session found."
            ));
            return;
        }

        // Use the callWait processor to keep the current customer call  on hold with background music.
        sendCustomerCallToCallWaitHandle();

        String from = this.getSessionInfo().getOpNum();
        String to = callArgs.getArgs().getString("to");
        String transferType = callArgs.getArgs().getString("transferType");
        if (from.equalsIgnoreCase(to)) {
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "can not consultation yourself."
            ));
            return;
        }
        if (StringUtils.isNullOrEmpty(to)) {
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "'to' argument is null in consultation."
            ));
            return;
        }


        String uuidInner = UuidGenerator.GetOneUuid();
        String uuidOuter = UuidGenerator.GetOneUuid();

        SwitchChannel consultationChannel = new SwitchChannel(uuidOuter, uuidInner, PhoneCallType.AUDIO_CALL, CallDirection.INBOUND);
        SwitchChannel agentChannel = new SwitchChannel(uuidInner, uuidOuter, PhoneCallType.AUDIO_CALL, CallDirection.INBOUND);

        consultationChannel.setPhoneNumber(to);
        consultationChannel.setBridgeCallAfterPark(true);
        consultationChannel.setSendChannelStatusToWsClient(true);
        agentChannel.setPhoneNumber(getExtNum());
        agentChannel.setBridgeCallAfterPark(false);
        agentChannel.setSendChannelStatusToWsClient(true);

        this.connectExtension(agentChannel, consultationChannel);
        EslConnectionPool connectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
        connectionPool.getDefaultEslConn().addListener(agentChannel.getUuid(), listener);
        connectionPool.getDefaultEslConn().addListener(consultationChannel.getUuid(), listener);

        if (agentChannel.getAnsweredTime() > 0) {

            if(transferType.equalsIgnoreCase("outer")){
                consultationChannel.setFlag(ChannelFlag.EXTERNAL_LINE);
                this.bridgeAgentToExternalLineForConsultation(consultationChannel, agentChannel, from, to);
                return;
            }

            MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(to);
            if (null != engine) {

                if (engine.getSessionInfo() == null || !engine.getSessionInfo().tryLock()) {
                    sendReplyToAgent(new MessageResponse(
                            RespStatus.LOCK_AGENT_FAIL,
                            "lock agent failed."
                    ));
                    return;
                }

                consultationChannel.setPhoneNumber(engine.getSessionInfo().getExtNum());

                JSONObject jsonObject = new JSONObject();
                jsonObject.put("status", AgentStatus.busy.getIndex());
                // 座席置忙
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.STATUS_CHANGED, "Agent is currently in a busy state.", jsonObject)
                );

                JSONObject jsonBody = new JSONObject();
                jsonBody.put("caller", from);
                jsonBody.put("callee", to);

                // Send screen pop-up message.
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.INNER_CONSULTATION_REQUEST, "Internal consultation request from :" + from,
                        jsonBody)
                );

                logger.info("{} Set the agent to a locked busy state. userId={}, extNum={}",
                        getTraceId(),
                        to,
                        engine.getSessionInfo().getExtNum()
                );
                AppContextProvider.getBean(SysService.class).setAgentStatusWithBusyLock(
                        to, AgentStatus.busy.getIndex()
                );

                CallApi callApi = ((CallApi) engine.getMessageHandleByName("call"));
                if (null == callApi) {
                    sendReplyToAgent(new MessageResponse(
                            RespStatus.SERVER_ERROR,
                            "Cant not get CallApi."
                    ));
                    return;
                }

                consultationChannel.setAnsweredHook(new IOnAnsweredHook() {
                    @Override
                    public void onAnswered(Map<String, String> eventHeaders, String traceId) {
                        String tips = "The call consultation has started.";
                        // send msg to 'consultation requester'
                        sendReplyToAgent(new MessageResponse(
                                RespStatus.INNER_CONSULTATION_START,
                                tips,
                                jsonBody
                        ));
                        // send msg to 'consultation recipient'
                        engine.sendReplyToAgent(new MessageResponse(
                                RespStatus.INNER_CONSULTATION_START,
                                tips,
                                jsonBody
                        ));
                        AppContextProvider.getBean(SysService.class).resetAgentBusyLockTime(to);
                        logger.info("{} The person being consulted has been answered.", callApi.getTraceId());
                    }
                });

                agentChannel.setHangupHook(new IOnHangupHook() {
                    @Override
                    public void onHangup(Map<String, String> eventHeaders, String traceId) {
                        String tips = "The call consultation has stopped.";
                        // send msg to 'consultation requester'
                        sendReplyToAgent(new MessageResponse(
                                RespStatus.INNER_CONSULTATION_STOP,
                                tips,
                                jsonBody
                        ));
                        // send msg to 'consultation recipient'
                        engine.sendReplyToAgent(new MessageResponse(
                                RespStatus.INNER_CONSULTATION_STOP,
                                tips,
                                jsonBody
                        ));
                        logger.info("{} {}", callApi.getTraceId(), tips);
                    }
                });

                consultationChannel.setAnsweredTime(0L);
                callApi.connectExtension(consultationChannel, agentChannel);
                if (consultationChannel.getAnsweredTime() <= 0) {
                    logger.warn("{} The person being consulted has no answer, terminate call session. ", getTraceId());
                    this.listener.endCall("Consultation-call-failed.");
                }
            }
        } else {
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    String.format("user %s is offline.", to)
            ));
        }
    }

	private void playMp4File(CallArgs callArgs){
	    String filePath = callArgs.getArgs().getString("mp4FilePath");
        if (this.listener != null && !StringUtils.isNullOrEmpty(filePath)) {
            this.listener.playMp4File(filePath);
        }
    }

    /**
     *  执行重邀请: reInvite 实现语音通话转视频通话
     */
	private void reInviteVideo(){
        if (this.getIsDisposed()) {
            return;
        }
        if (this.listener != null) {
            this.listener.reInviteVideo(this.getSessionInfo());
        }else{
            Utils.processArgsError("call session not exists.", thisRef);
        }
    }

    /**
     *  为咨询通话转接外线
     * @param externalChannel
     * @param agentChannel
     * @param fromOpNum
     */
    public void bridgeAgentToExternalLineForConsultation(SwitchChannel externalChannel, SwitchChannel agentChannel, String fromOpNum, String to) {
        String caller = SystemConfig.getValue("conference_gateway_caller");
        String gatewayAddr = SystemConfig.getValue("conference_gateway_addr");
        String profile = SystemConfig.getValue("conference_outboud_profile");

        externalChannel.setRecvMediaHook(new IOnRecvMediaHook() {
            @Override
            public void onRecvMedia(Map<String, String> eventHeaders, String traceId) {
                agentChannel.clearFlag(ChannelFlag.HOLD_CALL);
            }
        });

        externalChannel.setAnsweredHook(new IOnAnsweredHook() {
            @Override
            public void onAnswered(Map<String, String> eventHeaders, String traceId) {
                // notify the sender
                MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(fromOpNum);
                if (null != engine) {
                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("caller", fromOpNum);
                    jsonBody.put("callee", to);
                    String tips = "The call consultation has started.";
                    engine.sendReplyToAgent(new MessageResponse(
                            RespStatus.INNER_CONSULTATION_START,
                            tips,
                            jsonBody
                    ));
                }
            }
        });

        agentChannel.setHangupHook(new IOnHangupHook() {
            @Override
            public void onHangup(Map<String, String> eventHeaders, String traceId) {
                // notify the sender
                MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(fromOpNum);
                if (null != engine) {
                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("caller", fromOpNum);
                    jsonBody.put("callee", to);
                    String tips = "The call consultation has stopped.";
                    engine.sendReplyToAgent(new MessageResponse(
                            RespStatus.INNER_CONSULTATION_STOP,
                            tips,
                            jsonBody
                    ));
                }
            }
        });

        String destPhone = externalChannel.getPhoneNumber();
        String uuid = externalChannel.getUuid();
        CallListener  listenerAnonymous = new CallListener(this, agentChannel, externalChannel);
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(uuid, listenerAnonymous);

        StringBuilder callParam = new StringBuilder();
        callParam.append(String.format("{hangup_after_bridge=true,origination_uuid=%s", uuid));
        callParam.append(",absolute_codec_string=pcma");
        callParam.append(String.format(",origination_caller_id_number=%s,origination_caller_id_name=%s", caller, caller));
        callParam.append(String.format(",effective_caller_id_number=%s,effective_caller_id_name=%s", caller, caller));
        callParam.append(String.format("}sofia/%s/%s@%s", profile, destPhone, gatewayAddr));
        callParam.append(" &park ");

        String callParams = callParam.toString();
        String msg = EslConnectionUtil.sendAsyncApiCommand("originate", callParams);
        logger.info("{} bridgeInboundCallToExternalLine phone: {}, response: {}", getTraceId(), destPhone, msg);
    }


    public void bridgeInboundCallToExternalLine(SwitchChannel externalChannel, SwitchChannel inboundChannel, String fromOpNum) {
        String caller = SystemConfig.getValue("conference_gateway_caller");
        String gatewayAddr = SystemConfig.getValue("conference_gateway_addr");
        String profile = SystemConfig.getValue("conference_outboud_profile");

        externalChannel.setRecvMediaHook(new IOnRecvMediaHook() {
            @Override
            public void onRecvMedia(Map<String, String> eventHeaders, String traceId) {
                inboundChannel.clearFlag(ChannelFlag.HOLD_CALL);
            }
        });

        CallListener listenerRef = this.listener;

        externalChannel.setAnsweredHook(new IOnAnsweredHook() {
            @Override
            public void onAnswered(Map<String, String> eventHeaders, String traceId) {
                // notify the sender, call is transferred successfully
                MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(fromOpNum);
                if (null != engine) {
                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("caller", fromOpNum);
                    jsonBody.put("callee", externalChannel.getPhoneNumber());
                    jsonBody.put("customerInfo", inboundChannel);
                    engine.sendReplyToAgent(new MessageResponse(
                            RespStatus.TRANSFER_CALL_SUCCESS,
                            "call transferred successfully.",
                            jsonBody
                    ));

                    if(listenerRef != null) {
                        listenerRef.setCustomerChannel(null);
                        listenerRef.setAgentChannel(null);
                    }
                }
            }
        });

        String destPhone = externalChannel.getPhoneNumber();
        String uuid = externalChannel.getUuid();
        CallListener  listenerAnonymous = new CallListener(this, inboundChannel, externalChannel);
        EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(uuid, listenerAnonymous);

        StringBuilder callParam = new StringBuilder();
        callParam.append(String.format("{hangup_after_bridge=true,origination_uuid=%s", uuid));
        callParam.append(",absolute_codec_string=pcma");
        callParam.append(String.format(",origination_caller_id_number=%s,origination_caller_id_name=%s", caller, caller));
        callParam.append(String.format(",effective_caller_id_number=%s,effective_caller_id_name=%s", caller, caller));
        callParam.append(String.format("}sofia/%s/%s@%s", profile, destPhone, gatewayAddr));
        callParam.append(" &park ");

        String callParams = callParam.toString();
        String msg = EslConnectionUtil.sendAsyncApiCommand("originate", callParams);
        logger.info("{} bridgeInboundCallToExternalLine phone: {}, response: {}", getTraceId(), destPhone, msg);
    }


    /**
     *  把呼入通话转接到外线上;
     * @param fromOpNum
     * @param toPhoneNum
     * @param customerChannel
     */
    private void doTransferCallToExternalLine(String fromOpNum, String toPhoneNum, SwitchChannel customerChannel) {
        SwitchChannel externalLineChannel = new SwitchChannel(
                UuidGenerator.GetOneUuid(),
                customerChannel.getUuid(),
                customerChannel.getCallType(),
                CallDirection.OUTBOUND
        );

        externalLineChannel.setPhoneNumber(toPhoneNum);
        externalLineChannel.setBridgeCallAfterPark(true);
        externalLineChannel.setSendChannelStatusToWsClient(false);

        this.bridgeInboundCallToExternalLine(externalLineChannel, customerChannel, fromOpNum);
    }

    private void doTransferCall(String fromOpNum, String toOpNun, SwitchChannel customerChannel) {
        CallListener listenerRef = this.listener;
        SwitchChannel agentChannel = new SwitchChannel(
                "",
                customerChannel.getUuid(),
                customerChannel.getCallType(),
                customerChannel.getCallDirection()
        );
        agentChannel.setPhoneNumber(customerChannel.getPhoneNumber());
        agentChannel.setBridgeCallAfterPark(true);
        agentChannel.setFlag(ChannelFlag.TRANSFER_CALL_RECV);
        agentChannel.setSendChannelStatusToWsClient(true);
        agentChannel.setAnsweredHook(new IOnAnsweredHook() {
            @Override
            public void onAnswered(Map<String, String> eventHeaders, String traceId) {
                AppContextProvider.getBean(SysService.class).resetAgentBusyLockTime(toOpNun);
                if(listenerRef != null) {
                    listenerRef.setCustomerChannel(null);
                    listenerRef.setAgentChannel(null);
                }
            }
        });

        this.connectExtension(agentChannel, customerChannel);

        if(agentChannel.getAnsweredTime() > 0){
          // notify the sender, call is transferred successfully
            MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(fromOpNum);
            if (null != engine) {
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("caller", fromOpNum);
                jsonBody.put("callee", toOpNun);
                jsonBody.put("customerInfo", customerChannel);
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.TRANSFER_CALL_SUCCESS,
                        "call transferred successfully.",
                        jsonBody
                ));
            }
        }
    }

    /**
     * 尝试接通分机，直到超时
     * @param agentChannel
     * @param customerChannel
     */
    protected void connectExtension(SwitchChannel agentChannel, SwitchChannel customerChannel){
        if(customerChannel.getCallDirection().equalsIgnoreCase(CallDirection.OUTBOUND)){
            // disable customerChannel park event.
            customerChannel.setBridgeCallAfterPark(false);
        }

        listener = new CallListener(this, agentChannel, customerChannel);
        CallMonitorInfo callMonitorInfo = new CallMonitorInfo(
                agentChannel.getUuidBLeg(),
                agentChannel.getUuid(),
                getExtNum(),
                customerChannel.getPhoneNumber(),
                agentChannel.getCallType(),
                customerChannel.getCallDirection(),
                this.getSessionInfo().getGroupId(),
                System.currentTimeMillis()
        );
        if(callMonitorEnabled) {
            agentChannel.setCallMonitorEnabled(true);
            agentChannel.setCallMonitorInfo(callMonitorInfo);
        }

        EslConnectionPool connectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
        boolean videoCall = agentChannel.getCallType().equals(PhoneCallType.VIDEO_CALL);
        String originationParam = String.format("originate_timeout=%s,hangup_after_bridge=false,origination_uuid={uuid},%s,ignore_early_media=true,not_save_record_flag=1",
                String.valueOf(transferAgentTimeOut),
                videoCall ?  "rtp_force_video_fmtp='profile-level-id=42e01e;packetization-mode=1',record_concat_video=true" : "absolute_codec_string=pcma"
        );
        String displayNumber = hidePhoneNumber ?
                CommonUtils.hiddenPhoneNumber(customerChannel.getPhoneNumber()) : customerChannel.getPhoneNumber();
        String callerInfo = String.format(
                "extnum=%s,origination_caller_id_number=%s,origination_caller_id_name=%s,effective_caller_id_number=%s,effective_caller_id_name=%s",
                this.getExtNum(),
                displayNumber,
                displayNumber,
                displayNumber,
                displayNumber
        );

        // 确保接通分机
        long startTime = System.currentTimeMillis();
        AtomicInteger bLegIndex = new AtomicInteger(0);
        while (agentChannel.getAnsweredTime() == 0L && customerChannel.getHangupTime() == 0L && !getIsDisposed()) {
            long passedTime = System.currentTimeMillis() - startTime;
            if(passedTime > transferAgentTimeOut * 1000){
                logger.info("{}  transfer call {} to extension {} timeout.",
                        getTraceId(), customerChannel.getUuid(), getExtNum());
                break;
            }
            logger.info("{} try to connect extension {}, tryTimePassed={}",
                    getTraceId(), this.getExtNum(), passedTime);

            int index = bLegIndex.incrementAndGet();
            agentChannel.setHangupTime(0L);
            agentChannel.setUuid(String.format("%s-%s-bleg-%d",
                    customerChannel.getUuid(), getExtNum(), index));
            customerChannel.setUuidBLeg(agentChannel.getUuid());
            connectionPool.getDefaultEslConn().addListener(agentChannel.getUuid() + "-ex", listener);
            connectionPool.getDefaultEslConn().addListener(customerChannel.getUuid() + "-ex", listener);
            String bgApiUuid = EslConnectionUtil.sendAsyncApiCommand(
                    "originate",
                    String.format("{%s,%s}user/%s &park()",
                            originationParam.replace("{uuid}", agentChannel.getUuid()),
                            callerInfo,
                            this.getExtNum()
                    ),
                    connectionPool
            );
            if(!StringUtils.isNullOrEmpty(bgApiUuid)) {
                logger.info("{} doTransferCall connect extension {}, async jobId={}",
                        getTraceId(), getExtNum(),bgApiUuid);
                connectionPool.getDefaultEslConn().addListener(bgApiUuid.trim(), listener);
                this.listener.setBackgroundJobUuid(bgApiUuid.trim());
            }

            listener.waitForSignal();
            if (agentChannel.getAnsweredTime() == 0L
                    || agentChannel.getHangupTime() > 0L ) {
                logger.error("{} extension no answer, try again.", getTraceId());
                ThreadUtil.sleep(1000);
            }
        }
    }

    /**
     * 在咨询成功的情况下使用该按钮，把电话转接给专家坐席或者外线号码。
     * @param callArgs
     */
    private void transferCallWait(CallArgs callArgs) {
        if(this.listener == null){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "no call session found."
            ));
            return;
        }

        CallWait callWait = ((CallWait) this.msgHandlerEngine.getMessageHandleByName("callWait"));
        SwitchChannel customerChannel = callWait.getCustomerChannel();
        if(customerChannel == null || customerChannel.getHangupTime() > 0L){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "customer is hangup already."
            ));
            return;
        }

        SwitchChannel agentChannel = listener.getAgentChannel();
        SwitchChannel professionalChannel = listener.getCustomerChannel();
        if(professionalChannel.getHangupTime() > 0L || professionalChannel.getAnsweredTime() == 0L ){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "professional agent is hangup or not ready."
            ));
            return;
        }

        if(professionalChannel.testFlag(ChannelFlag.EXTERNAL_LINE)){
            // HOLD professional agent's call session
            EslConnectionUtil.sendExecuteCommand(
                    "set",
                    "park_after_bridge=true",
                    professionalChannel.getUuid()
            );
            professionalChannel.setFlag(ChannelFlag.HOLD_CALL);
            ThreadUtil.sleep(200);

            logger.info("{} try to hangup extension {} of user {}.", getTraceId(), getExtNum(), getSessionInfo().getOpNum());
            //挂断转出电话的坐席分机
            this.listener.endCall("call_transferred.");
            ThreadUtil.sleep(200);
            EslMessage message = EslConnectionUtil.sendSyncApiCommand("uuid_bridge",
                    professionalChannel.getUuid() + " " +
                            customerChannel.getUuid()
            );

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("caller", professionalChannel.getPhoneNumber());
            jsonBody.put("callee", customerChannel.getPhoneNumber());
            jsonBody.put("customerInfo", customerChannel);

            boolean success = JSON.toJSONString(message).contains("+OK");
            if(success) {
                CallListener  listenerAnonymous = new CallListener(this, customerChannel, professionalChannel);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(professionalChannel.getUuid() + "-ex", listenerAnonymous);
                EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(customerChannel.getUuid() + "-ex",  listenerAnonymous);
                MessageResponse response = new MessageResponse(
                        RespStatus.TRANSFER_CALL_SUCCESS,
                        "call transferred successfully.",
                        jsonBody
                );
                callWait.onCallWaitStopped();
                callWait.dispose();
                this.msgHandlerEngine.sendReplyToAgent(response);
                professionalChannel.clearFlag(ChannelFlag.HOLD_CALL);
                agentChannel.clearFlag(ChannelFlag.HOLD_CALL);

                this.listener.setCustomerChannel(null);
                this.listener.setAgentChannel(null);
            }else{
                this.msgHandlerEngine.sendReplyToAgent(new MessageResponse(
                        RespStatus.SERVER_ERROR,
                        "Transfer call failed, " +  JSON.toJSONString(message),
                        jsonBody
                ));
            }

            return;
        }


        String from = getSessionInfo().getOpNum();
        String to = professionalChannel.getPhoneNumber();

        MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByExtNum(to);
        if (null != engine) {

            CallApi callApi = ((CallApi) engine.getMessageHandleByName("call"));
            if (null != callApi) {

                // HOLD professional agent's call session
                EslConnectionUtil.sendExecuteCommand(
                        "set",
                        "park_after_bridge=true",
                        professionalChannel.getUuid()
                );
                professionalChannel.setFlag(ChannelFlag.HOLD_CALL);
                agentChannel.setFlag(ChannelFlag.HOLD_CALL);
                logger.info("{} set professional agent {} HOLD_CALL flag and park_after_bridge=true",
                        getTraceId(), professionalChannel.getUuid());
                ThreadUtil.sleep(100);

                logger.info("{} try to hangup extension {} of user {}.", getTraceId(), getExtNum(), getSessionInfo().getOpNum());
                //挂断转出电话的坐席分机
                this.listener.endCall("call_transferred.");
                ThreadUtil.sleep(100);

                JSONObject jsonBody = new JSONObject();
                jsonBody.put("caller", from);
                jsonBody.put("callee", to);
                jsonBody.put("customerInfo", customerChannel);

                //发送弹屏消息
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.TRANSFER_CALL_RECV, "转接的来电请求", jsonBody)
                );

                EslMessage message = EslConnectionUtil.sendSyncApiCommand("uuid_bridge",
                        professionalChannel.getUuid() + " " +
                               customerChannel.getUuid()
                 );

                boolean success = JSON.toJSONString(message).contains("+OK");
                if(success) {
                    if(callApi.listener != null){
                        callApi.listener.setCustomerChannel(customerChannel);
                    }
                    EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(professionalChannel.getUuid() + "-ex",  callApi.listener);
                    EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(customerChannel.getUuid() + "-ex",  callApi.listener);

                    MessageResponse response = new MessageResponse(
                            RespStatus.TRANSFER_CALL_SUCCESS,
                            "call transferred successfully.",
                            jsonBody
                    );
                    callWait.onCallWaitStopped();
                    callWait.dispose();
                    this.msgHandlerEngine.sendReplyToAgent(response);
                    engine.sendReplyToAgent(response);

                    professionalChannel.clearFlag(ChannelFlag.HOLD_CALL);
                    agentChannel.clearFlag(ChannelFlag.HOLD_CALL);

                    this.listener.setCustomerChannel(null);
                    this.listener.setAgentChannel(null);
                }else{
                    this.msgHandlerEngine.sendReplyToAgent(new MessageResponse(
                            RespStatus.SERVER_ERROR,
                            "Transfer call failed, " +  JSON.toJSONString(message),
                            jsonBody
                    ));
                }
            } else {
                sendReplyToAgent(new MessageResponse(
                        RespStatus.SERVER_ERROR, "server internal error!")
                );
            }
        }else{
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "Destination professional agent user is offline."
            ));
        }
    }

    private void transferCall(CallArgs callArgs) {
	    if(this.listener == null){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "no call session found."
            ));
	        return;
        }
        String from = this.getSessionInfo().getOpNum();
        String to = callArgs.getArgs().getString("to");
        String transferType = callArgs.getArgs().getString("transferType");

        if(from.equalsIgnoreCase(to)){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "can not transfer call to yourself."
            ));
            return;
        }
        if(StringUtils.isNullOrEmpty(to)){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "'to' argument is null in transferCall."
            ));
            return;
        }

        if(StringUtils.isNullOrEmpty(transferType)){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "'transferType' argument is null in transferCall."
            ));
            return;
        }

        SwitchChannel customerChannel = listener.getCustomerChannel();
        if(customerChannel.getHangupTime() > 0L || customerChannel.getAnsweredTime() == 0L ){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "call is hangup or not ready."
            ));
            return;
        }

       if(transferType.equalsIgnoreCase("outer")){
           EslConnectionUtil.sendExecuteCommand(
                   "set",
                   "park_after_bridge=true",
                   customerChannel.getUuid()
           );
           customerChannel.setFlag(ChannelFlag.HOLD_CALL);
           logger.info("{} set customerChannel {} HOLD_CALL and park_after_bridge=true",
                   getTraceId(), customerChannel.getUuid());
           ThreadUtil.sleep(100);

           //挂断转出电话的分机
           this.listener.endCall("call_transferred_to_external");
           ThreadUtil.sleep(100);

           doTransferCallToExternalLine(from, to, customerChannel);
           return;
       }

        MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().getMsgHandlerEngineByOpNum(to);
        if (null != engine) {
            if(engine.getSessionInfo() == null || !engine.getSessionInfo().tryLock()){
                sendReplyToAgent(new MessageResponse(
                        RespStatus.LOCK_AGENT_FAIL,
                        "lock agent failed."
                ));
                return;
            }

            CallApi callApi = ((CallApi) engine.getMessageHandleByName("call"));
            if (null != callApi) {

                // HOLD Customer CALL
                EslConnectionUtil.sendExecuteCommand(
                        "set",
                        "park_after_bridge=true",
                        customerChannel.getUuid()
                );
                customerChannel.setFlag(ChannelFlag.HOLD_CALL);
                logger.info("{} set customerChannel {} HOLD_CALL and park_after_bridge=true",
                        getTraceId(), customerChannel.getUuid());
                ThreadUtil.sleep(100);

                //挂断转出电话的分机
                this.listener.endCall("call_transferred.");
                ThreadUtil.sleep(100);

                //发送弹屏消息
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.TRANSFER_CALL_RECV, "转接的来电请求", customerChannel)
                );
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("status", AgentStatus.busy.getIndex());
                // 座席置忙
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.STATUS_CHANGED, "当前用户状态: 忙碌", jsonObject)
                );

                logger.info("{} 设定座席的忙碌锁定状态. userId={}, extNum={}",
                        callApi.getTraceId(),
                        to,
                        callApi.getExtNum()
                );
                AppContextProvider.getBean(SysService.class).setAgentStatusWithBusyLock(
                        to, AgentStatus.busy.getIndex()
                );

                callApi.doTransferCall(from, to, customerChannel);
            } else {
                engine.sendReplyToAgent(new MessageResponse(
                        RespStatus.SERVER_ERROR, "server internal error!", customerChannel)
                );
            }
        }else{
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "Destination agent user is offline."
            ));
        }
    }

	/**
	 *  结束通话：
	 */
	private void endCall() {
		if (this.getIsDisposed()) {
			return;
		}

		if (this.listener != null) {
            this.listener.clearCustomerHoldCallFlag();
			this.listener.endCall();
		}else{
			Utils.processArgsError("通话不存在.", thisRef);
		}
	}

    private String fullRecordPath = "";

	private static final  String CALL_TEMPLATE_STRING =
               "hangup_after_bridge=false,park_after_bridge=true,"
			+  "cc_call_leg_uuid=%s,"
			+ "origination_uuid=%s,"
			+ "%s"
			+ "RECORD_STEREO=%s,"
			+ "origination_caller_id_number=%s,"
			+ "effective_caller_id_number=%s,"
			+ "caseno=%s,"
			+ "callee=%s,"
			+ "caller=%s,"
			+ "extNum=%s,"
			+ "opNum=%s,"
			+ "callId=%s,"
			+ "role=%s,"
			+ "projectId=%s,"
			+ "fullRecordPath=%s,"
			+ "record_waste_resources=true,"
			+ "record_sample_rate=8000%s";


    /**
     * 外呼之前，先接通座席分机
     */
    public boolean connectCallExtNum(CallListener callListener, SwitchChannel agentChannel,
                                  SwitchChannel customerChannel, String projectId, String caseNo){
        if(null == callListener) {
            listener = new CallListener(this, agentChannel, customerChannel);
        }else{
            listener = callListener;
        }

        EslConnectionPool connectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
        connectionPool.getDefaultEslConn().addListener(agentChannel.getUuid(), listener);
        String callExtensionStr = genCallExtensionStr(projectId, caseNo,
                agentChannel, customerChannel.getPhoneNumber());
        logger.info("{} callExtensionStr = {}", getTraceId(), callExtensionStr);

        String jobId = EslConnectionUtil.sendAsyncApiCommand(
                "originate",
                callExtensionStr
        );

        logger.info("{} callExtension response: {}", getTraceId(), jobId);
        if (!StringUtils.isNullOrEmpty(jobId)) {
            connectionPool.getDefaultEslConn().addListener(jobId.trim(), listener);
            this.listener.setBackgroundJobUuid(jobId.trim());
        } else {
            logger.error("{} callExtension cant not get fs backGroundJobUuid", getTraceId());
        }

        listener.waitForSignal();

        // 超时未接听，自动结束通话
        if (agentChannel.getAnsweredTime() == 0L || agentChannel.getHangupTime() > 0L) {
            sendReplyToAgent(new MessageResponse(
                    RespStatus.CALLER_RESPOND_TIMEOUT, "extension no reply, please check extension is login.")
            );
            return false;
        }
        return  true;
    }

	private String genCallExtensionStr(String projectId, String caseNo, SwitchChannel channel, String phone){
        String extNum = this.getExtNum();
        String uuidInner = channel.getUuid();
        String callType = channel.getCallType();
        String videoLevel = channel.getVideoLevel();
	    String caller = extNum;
		String callee = phone;
		String phoneHiddenStr = CommonUtils.hiddenPhoneNumber(phone);
		String enableCcRecordStereo = SystemConfig.getValue("enable_cc_record_stereo", "false");
		String callPrefixInnerLine = String.format(CALL_TEMPLATE_STRING,
				uuidInner,
				uuidInner,
                PhoneCallType.checkAudioCall(callType) ?  ("absolute_codec_string=pcma,") : "",
				enableCcRecordStereo,
				phoneHiddenStr,
				phoneHiddenStr,
				caseNo,
				callee,
				caller,
				extNum,
                getSessionInfo().getOpNum(),
				uuidInner,
				"2",
				projectId,
				fullRecordPath,
				PhoneCallType.checkVideoCall(callType) ?
						",rtp_force_video_fmtp='profile-level-id="+ videoLevel.trim() +";packetization-mode=1',record_concat_video=true" : ""
		);
		return String.format("{%s,sip_auto_answer=true,not_save_record_flag=1}user/%s  &park",
				callPrefixInnerLine,
				extNum
		);
	}

    private String genCallPhoneString(GatewayConfig gatewayConfig, String projectId, String caseNo,
                                 String uuidInner, String uuidOuter, String phone,
                                 String callType, String videoLevel)
    {
        String extNum = this.getExtNum();
        String caller = extNum;
        String callee = phone;
        String enableCcRecordStereo = SystemConfig.getValue("enable_cc_record_stereo", "false");

        // 外呼网关地址
        String gatewayAddress = gatewayConfig.getGatewayAddr();
        // 主叫号码
        String callerNumber =  CommonUtils.getCallerNumberRandomly(gatewayConfig.getCallerNumber());
        // 被叫前缀
        String calleePrefix = gatewayConfig.getCalleePrefix();
        String sipProfile = gatewayConfig.getCallProfile();
        String codec = gatewayConfig.getAudioCodec();

        String callPrefixOuterLine = String.format(CALL_TEMPLATE_STRING,
                uuidInner,
                uuidOuter,
                PhoneCallType.checkAudioCall(callType) ?   ("absolute_codec_string=" + codec + ",")  : "",
                enableCcRecordStereo,
                callerNumber,
                callerNumber,
                caseNo,
                callee,
                caller,
                extNum,
                getSessionInfo().getOpNum(),
                uuidOuter,
                "1",
                projectId,
                fullRecordPath,
                PhoneCallType.checkVideoCall(callType) ?
                        ",rtp_force_video_fmtp='profile-level-id="+ videoLevel.trim() +";packetization-mode=1',record_concat_video=true" : ""
        );

        String extraParams = SystemConfig.getValue("outbound-call-extra-params-for-profile-"+ sipProfile , "");
        String extraParamsFinal =  extraParams.length() == 0 ? "" :  "," + extraParams ;

        // 对接模式和注册模式，bridge字符串拼接内容不同;
        String bridgeString = String.format("{execute_on_answer='record_session %s',%s%s}sofia/%s/%s%s@%s  &park",
                CallConfig.RECORDINGS_PATH + fullRecordPath,
                callPrefixOuterLine,
                extraParamsFinal,
                sipProfile,
                calleePrefix,
                phone,
                gatewayAddress
        );

        if(gatewayConfig.getRegister() == 1){
            bridgeString = String.format("{execute_on_answer='record_session %s',%s%s}sofia/gateway/%s/%s%s  &park",
                    CallConfig.RECORDINGS_PATH + fullRecordPath,
                    callPrefixOuterLine,
                    extraParamsFinal,
                    gatewayConfig.getGwName(),
                    calleePrefix,
                    phone
            );
        } else if(gatewayConfig.getRegister() == 2) {
            String authUsername = gatewayConfig.getAuthUsername();
            String dynamicGateway = CommonUtils.getDynamicGatewayAddr(authUsername, getTraceId());
            logger.info("{} successfully get dynamic gateway address : {}", getTraceId(), dynamicGateway);
            // for dynamic gateway, we must use internal profile
            bridgeString = String.format("{execute_on_answer='record_session %s',%s%s}sofia/internal/%s%s@%s  &park()",
                    CallConfig.RECORDINGS_PATH + fullRecordPath,
                    callPrefixOuterLine,
                    extraParamsFinal,
                    calleePrefix,
                    callee,
                    dynamicGateway
            );
        }

        return bridgeString;
    }

    private static final String BIZ_FIELD_DEFAULT_VALUE = "NOT_SET";

	/**
	 *  开始通话：
	 */
	private void startCall(CallArgs callArgs) {
        String gatewayListArgs = null;
        boolean gatewayEncrypted = callArgs.getArgs().getBoolean("gatewayEncrypted");
        // audio or video
        String callType = callArgs.getArgs().getString("callType");
        String videoLevel = callArgs.getArgs().getString("videoLevel");
        boolean useSameAudioCodeForOutbound = callArgs.getArgs().getBoolean("useSameAudioCodeForOutbound");
        this.fullRecordPath = "";

        if (!PhoneCallType.checkCallTypeValid(callType)) {
            callType = PhoneCallType.AUDIO_CALL;
            logger.info("{} 由于参数传递错误，已经自动把 callType 参数设置为 {}", getTraceId(), callType);
        }

        if (PhoneCallType.checkVideoCall(callType)) {
            if (StringUtils.isNullOrEmpty(videoLevel)) {
                logger.info("已经自动把 videoLevel 参数设置为 {}", VideoConfigs.DEFAULT_VIDEO_LEVEL);
            }
            if (!VideoConfigs.checkVideoLevels(videoLevel)) {
                Utils.processArgsError("UnSupported videoLevel " + videoLevel, thisRef);
                return;
            }
        }

        if (gatewayEncrypted) {
            try {
                gatewayListArgs = DESUtil.decrypt(callArgs.getArgs().getString("gatewayList"));
            } catch (Throwable e) {
                logger.error("解密网关列表数据失败: {} {}", e.toString(), JSON.toJSONString(e.getStackTrace()));
                Utils.processArgsError("gatewayList parameter error!", thisRef);
                return;
            }
        } else {
            gatewayListArgs = callArgs.getArgs().getString("gatewayList");
        }

        List<GatewayConfig> gatewayList = null;
        try {
            gatewayList = JSON.parseObject(gatewayListArgs, new TypeReference<List<GatewayConfig>>() {
            });
        }catch (Throwable e){
            logger.error("{} parse gatewayList parameter error! {}  \n {}", getTraceId(), e.toString(),
                    CommonUtils.getStackTraceString(e.getStackTrace()));
        }
        logger.info("gatewayListArgs={}", gatewayListArgs);
        if (null == gatewayList || gatewayList.size() == 0) {
            Utils.processArgsError("gatewayList parameter error!", thisRef);
            return;
        }

        String phoneAndCaseInfo = callArgs.getArgs().getString("destPhone");
        String caseNo = "";
        String phone = "";
        if (phoneAndCaseInfo.contains(";")) {
            caseNo = phoneAndCaseInfo.split(";")[1];
            phone = phoneAndCaseInfo.split(";")[0];
        } else {
            // This is a custom business field parameter.
            // It will be carried when sending messages via WebSocket.
            caseNo = BIZ_FIELD_DEFAULT_VALUE;
            phone = phoneAndCaseInfo;
        }

        boolean isNumeric = StringUtils.isNumeric(phone.replace(" ", "").replace("-", "").trim());
        // 电话号码不是数字的情况下，需要解密号码字符串
        if (!isNumeric) {
            String key = SystemConfig.getValue("phone_encrypted_key");
            phone = EncryptUtil.getInstance().DESdecode(phone, key);
            if (null == phone) {
                Utils.processArgsError("手机号码解密失败，请检查解密的密码字符串", thisRef);
                return;
            }
        }

        String uuidInner = UuidGenerator.GetOneUuid();
        String uuidOuter = UuidGenerator.GetOneUuid();
        SwitchChannel agentChannel = new SwitchChannel(uuidInner, uuidOuter, callType, CallDirection.OUTBOUND);
        SwitchChannel customerChannel = new SwitchChannel(uuidOuter, uuidInner, callType, CallDirection.OUTBOUND);

        agentChannel.setVideoLevel(videoLevel);
        agentChannel.setBizFieldValue(caseNo);
        agentChannel.setPhoneNumber(this.getExtNum());
        agentChannel.setSendChannelStatusToWsClient(true);
        customerChannel.setVideoLevel(videoLevel);
        customerChannel.setPhoneNumber(phone);
        customerChannel.setBridgeCallAfterPark(true);
        customerChannel.setSendChannelStatusToWsClient(true);
        CallMonitorInfo callMonitorInfo = new CallMonitorInfo(
                uuidOuter,
                uuidInner,
                getExtNum(),
                phone,
                callType,
                CallDirection.OUTBOUND,
                this.getSessionInfo().getGroupId(),
                System.currentTimeMillis()
        );
        if (callMonitorEnabled) {
            customerChannel.setCallMonitorEnabled(true);
            customerChannel.setCallMonitorInfo(callMonitorInfo);
        }

        // 项目编号
        String projectId = this.getSessionInfo().getGroupId();
        String recordingsType = SystemConfig.getValue("recordings_extension", "wav");
        if (PhoneCallType.checkVideoCall(callType)) {
            recordingsType = "mp4";
        }

        String recordFieName = getExtNum() + "_" + phone + "_" + uuidInner + "." + recordingsType;
        fullRecordPath = projectId
                + DateUtils.format(new Date(), "/yyyy/MM/dd/HH/")
                + recordFieName;

        logger.info("{} set fullRecordPath={}{}", getTraceId(), CallConfig.RECORDINGS_PATH, fullRecordPath);
        customerChannel.setRecordingFilePath(fullRecordPath);

        //首先接通分机
        boolean callExtSuccess = this.connectCallExtNum(null, agentChannel, customerChannel, projectId, caseNo);
        if (!callExtSuccess) {
            return;
        }

        JSONObject outboundStartEvent = new JSONObject();
        outboundStartEvent.put("uuid", agentChannel.getUuid());
        outboundStartEvent.put("uuid_customer", customerChannel.getUuid());
        outboundStartEvent.put("destPhone", customerChannel.getPhoneNumber());
        outboundStartEvent.put("biz_field_value", caseNo);
        this.sendReplyToAgent(
                new MessageResponse(
                        RespStatus.OUTBOUND_START,
                        "outbound start event",
                        outboundStartEvent
                )
        );

        //记录已经尝试过的网关;
        List<GatewayConfig> triedList = new ArrayList<>(10);
        EslConnectionPool connectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
        if (connectionPool != null) {

            boolean firstCall = true;
            do {
                if (!firstCall) {
                    logger.info("{} call failed sipCode={}，retry again",
                            getTraceId(), customerChannel.getHangupSipCode());
                    ThreadUtil.sleep(100);
                }
                firstCall = false;

                listener.resetCustomerChannelLastCallStatus();

                GatewayConfig gatewayConfig = SipGatewayLoadBalance.getGateway(gatewayList, triedList);
                if (null == gatewayConfig) {
                    logger.info("{} no available gateway, exit the outgoing call attempt! Number of tried gateways={}", getTraceId(), triedList.size());
                    this.sendReplyToAgent(
                            new MessageResponse(
                                    RespStatus.OUTBOUND_FINISHED,
                                    "No available gateway, Number of tried gateways: " + triedList.size()
                            )
                    );
                    break;
                }
                logger.info("{} successfully get a gateway: {} ", getTraceId(), JSON.toJSONString(gatewayConfig));

                String originationStr = genCallPhoneString(gatewayConfig, projectId, caseNo, uuidInner,
                        uuidOuter, phone, callType, videoLevel);
                customerChannel.setGatewayConfig(gatewayConfig);

                connectionPool.getDefaultEslConn().addListener(uuidOuter, listener);
                logger.info("{} originationStr: originate {}", getTraceId(), originationStr);
                String jobId = EslConnectionUtil.sendAsyncApiCommand("originate", originationStr, connectionPool);
                logger.info("{} fs bgapi originate response: {}", getTraceId(), jobId);
                if (!StringUtils.isNullOrEmpty(jobId)) {
                    connectionPool.getDefaultEslConn().addListener(jobId.trim(), listener);
                    this.listener.setBackgroundJobUuid(jobId.trim());
                } else {
                    logger.error("{}  cant not get FreeSWITCH backGroundJobUuid", getTraceId());
                }

                listener.waitForSignal();
                if (super.getIsDisposed()) {
                    break;
                }

                if (!listener.checkCustomerChannelCallStatus()) {
                    logger.info("{} call originate failed，add current gateway to  triedList： gateway = {}  ", getTraceId(), gatewayConfig);
                    triedList.add(gatewayConfig);
                } else {
                    logger.info("{} call originate finished successfully，tried gateway list count：{} , details: {}", getTraceId(), triedList.size(), triedList);
                }
            } while (!listener.checkCustomerChannelCallStatus() &&
                    agentChannel.getHangupTime() == 0L && !getIsDisposed());

            logger.info("{} Call finished.", getTraceId());

            boolean customerAnswered = listener.getCustomerChannel().getAnsweredTime() > 0;
            if (!customerAnswered) {
                logger.info("{} hangup extension {} due to no outbound call connected.", getTraceId(), getSessionInfo().getExtNum());
                endCall();
            }

        } else {
            Utils.processServerInternalError("Can not connect to freeSwitch.", this, true);
        }
    }

	public void bridgeCall(SwitchChannel agentChannel, SwitchChannel customerChannel){
        String uuidInner = agentChannel.getUuid();
        String uuidOuter = customerChannel.getUuid();
        logger.info("{} try to bridge call,  uuidInner={}, uuidOuter={}",
                getTraceId(), uuidInner, uuidOuter
        );

        EslMessage eslMessage = EslConnectionUtil.sendSyncApiCommand(
                "uuid_bridge",
                String.format("%s %s",uuidInner, uuidOuter),
                EslConnectionUtil.getDefaultEslConnectionPool()
        );

        boolean bridgeSucceed = false;
        if(eslMessage.getBodyLines().size() > 0){
            if(eslMessage.getBodyLines().get(0).contains("+OK")){
                bridgeSucceed = true;
                agentChannel.setChannelState(ChanneState.BRIDGED);
                customerChannel.setChannelState(ChanneState.BRIDGED);
            }
        }

        if(!bridgeSucceed){
            logger.error("{} call bridged failed： {}", getTraceId(), JSON.toJSONString(eslMessage));
        }else{
            logger.info("{} call bridged successfully： {}", getTraceId(), JSON.toJSONString(eslMessage));

        }
    }


	/**
	 * Acd转坐席之前，先接通座席分机
	 */
	public String connectAgentExtNum(SwitchChannel agentChannel, SwitchChannel customerChannel,
	        String displayNumber,int transferAgentTimeOut, Long inboundTime){

		listener = new CallListener(this, agentChannel, customerChannel);

        CallMonitorInfo callMonitorInfo = new CallMonitorInfo(
                customerChannel.getUuid(),
                customerChannel.getUuidBLeg(),
                getExtNum(),
                customerChannel.getPhoneNumber(),
                agentChannel.getCallType(),
                CallDirection.INBOUND,
                this.getSessionInfo().getGroupId(),
                inboundTime

        );
        if(callMonitorEnabled) {
            agentChannel.setCallMonitorEnabled(true);
            agentChannel.setCallMonitorInfo(callMonitorInfo);
        }
        agentChannel.setBridgeCallAfterPark(true);
        agentChannel.setSendChannelStatusToWsClient(true);

		EslConnectionPool connectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
		// 当前监听器是扩展分发的esl消息; 采用 -ex 后缀，避免覆盖主监听器：
		connectionPool.getDefaultEslConn().addListener(agentChannel.getUuid() + "-ex", listener);
		connectionPool.getDefaultEslConn().addListener(customerChannel.getUuid() + "-ex", listener);

		boolean videoCall = agentChannel.getCallType().equals(PhoneCallType.VIDEO_CALL);
		String originationParam = String.format("originate_timeout=%s,hangup_after_bridge=false,origination_uuid=%s,%s,ignore_early_media=true,not_save_record_flag=1",
				String.valueOf(transferAgentTimeOut),
                agentChannel.getUuid(),
                videoCall ?  "record_concat_video=true" : "absolute_codec_string=pcma"
                // rtp_force_video_fmtp='profile-level-id=42e01e;packetization-mode=1',
		);
 		String callerInfo = String.format(
				"extnum=%s,origination_caller_id_number=%s,origination_caller_id_name=%s,effective_caller_id_number=%s,effective_caller_id_name=%s",
				this.getExtNum(),
				displayNumber,
				displayNumber,
				displayNumber,
				displayNumber
		);
		return EslConnectionUtil.sendAsyncApiCommand(
				"originate",
				String.format("{%s,%s}user/%s &park()",
						originationParam,
						callerInfo,
						this.getExtNum()
				),
				connectionPool
		);
	}

	@Override
	public void dispose() {
        if(null != this.listener){
        	// 业务对象销毁的时候，默认会挂断当前通话;
			if(Boolean.parseBoolean(AppContextProvider.getEnvConfig("app-config.phone-bar.end-call-on-websocket-disconnect", "false"))) {
				this.listener.endCall("on_dispose");
			}
		}
		super.dispose();
	}

	private class HandlerInitializer implements IMsgHandlerInitializer {
		@Override
		public void activeCurrentHandlerInstance() {
			logger.info("callApi actived ...");
		}
		@Override
		public void destroyHandlerInstance() {
			if (listener != null) {
				listener.onDispose();
			}
			logger.info(" {} callApi object for user {} is destroyed.",
					getTraceId(), getExtNum());
		}
	}
	@Override
	public void activeCurrentObject(MessageHandlerEngine msgHandlerEngine, IMsgHandlerInitializer... initializer) {
	       super.activeCurrentObject(msgHandlerEngine, new HandlerInitializer());
	}

	public String getExtNum() {
		return this.getSessionInfo().getExtNum();
	}
}
