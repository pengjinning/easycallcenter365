package com.telerobot.fs.wshandle.impl;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.utils.ThreadUtil;
import com.telerobot.fs.wshandle.*;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;

import java.util.Map;

public class CallHold extends MsgHandlerBase {

    private volatile   SwitchChannel customerChannel = null;

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
        logger.info("{} recv msg, CallHold, {}", getTraceId(), data.getBody());
        String cmd = callArgs.getCmd();
        if (cmd == null || cmd.length() == 0) {
            Utils.processArgsError("cmd param error", this);
            return;
        }
        switch (cmd) {
            case "hold":
                holdCallSession();
                break;
            case "unhold":
                unHoldCallSession();
                break;
            default:
                Utils.processArgsError("unSupported command.", this);
        }
    }

    private void unHoldCallSession(){
        if(null == customerChannel){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "No call is on hold."
            ));
            return;
        }

        CallApi callApi = ((CallApi) this.msgHandlerEngine.getMessageHandleByName("call"));
        if (null == callApi) {
            sendReplyToAgent(new MessageResponse(
                    RespStatus.SERVER_ERROR,
                    "Cant not get CallApi."
            ));
            return;
        }

        if(callApi.listener == null){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "No call session found, cant not get call listener."
            ));
            return;
        }

        SwitchChannel agentChannel =  callApi.listener.getAgentChannel();
        if(null == agentChannel){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "No call is on hold, cant not get agentChannel."
            ));
            return;
        }

        String uuidB = customerChannel.getUuid();
        String uuidA = agentChannel.getUuid();
        if(StringUtils.isNullOrEmpty(uuidA) || StringUtils.isNullOrEmpty(uuidB)){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "No call is on hold, cant not get channel uuid."
            ));
            return;
        }

        EslMessage msg =  EslConnectionUtil.sendSyncApiCommand("uuid_bridge", uuidA + " " + uuidB);
        String resp = JSON.toJSONString(msg);
        logger.info("{} {} uuid_bridge result: {}", uuidA, uuidB, resp);
        if(resp.contains("+OK")) {
            syncState();
            sendReplyToAgent(new MessageResponse(
                    RespStatus.CUSTOMER_CHANNEL_UNHOLD,
                    "Successfully un-hold call session."
            ));
        }
    }

    /**
     *  hold call session, and play music
     *
     */
    private void holdCallSession() {
        if(null != customerChannel){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "The previous call is on hold."
            ));
            return;
        }
        MessageHandlerEngine engine = this.msgHandlerEngine;
        CallApi callApi = ((CallApi) engine.getMessageHandleByName("call"));
        if (null != callApi) {

            if(callApi.listener == null){
                sendReplyToAgent(new MessageResponse(
                        RespStatus.REQUEST_PARAM_ERROR,
                        "No call session found."
                ));
                return;
            }

            customerChannel = callApi.listener.getCustomerChannel();
            if(customerChannel == null || customerChannel.getHangupTime() > 0L || customerChannel.getAnsweredTime() == 0L ){
                syncState();
                sendReplyToAgent(new MessageResponse(
                        RespStatus.REQUEST_PARAM_ERROR,
                        "Call session is hangup or not ready."
                ));
                return;
            }

            customerChannel.setHangupHook(new IOnHangupHook() {
                @Override
                public void onHangup(Map<String, String> eventHeaders, String traceId) {
                    syncState();
                    engine.sendReplyToAgent(new MessageResponse(
                            RespStatus.CUSTOMER_ON_HOLD_HANGUP, "", customerChannel)
                    );
                }
            });

            // HOLD Customer CALL
            EslConnectionUtil.sendExecuteCommand(
                    "park",
                    "",
                    customerChannel.getUuid()
            );
            ThreadUtil.sleep(1000);

            EslConnectionUtil.sendExecuteCommand("endless_playback",
                    "$${sounds_dir}/ivr/hold.wav",
                    customerChannel.getUuid()
            );

            new Thread(new Runnable() {
                @Override
                public void run() {
                    sendWsMsg();
                }
            }).start();
        } else {
            engine.sendReplyToAgent(new MessageResponse(
                    RespStatus.SERVER_ERROR, "server internal error!")
            );
        }
    }

    private void sendWsMsg(){
        while(null != customerChannel){
            synchronized (customerChannel.getUuid().intern()) {
                if(null != customerChannel) {
                    //send ws msg.
                    sendReplyToAgent(new MessageResponse(
                            RespStatus.CUSTOMER_CHANNEL_HOLD, "customer call session is on hold.", customerChannel)
                    );
                }
            }
            ThreadUtil.sleep(2000);
        }
    }

    private void syncState(){
        if (null != customerChannel) {
            synchronized (customerChannel.getUuid().intern()) {
                if (null != customerChannel) {
                    customerChannel = null;
                }
            }
        }
    }


}
