package com.telerobot.fs.wshandle.impl;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.ChannelFlag;
import com.telerobot.fs.utils.ThreadUtil;
import com.telerobot.fs.wshandle.*;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;

import java.util.Map;

public class CallWait extends MsgHandlerBase {
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
        logger.info("{} recv msg: CallWait: {}", getTraceId(), data.getBody());
        String cmd = callArgs.getCmd();
        if (cmd == null || cmd.length() == 0) {
            Utils.processArgsError("cmd param error", this);
            return;
        }
        switch (cmd) {
            case "start":
                startCallWait();
                break;
            case "stop":
                stopCallWait();
                break;
            default:
                Utils.processArgsError("unSupported command.", this);
        }
    }

    /**
     *  hold call session, and play music
     *
     */
    protected void startCallWait() {
        if(null != customerChannel){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "The previous call is on call-wait."
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

            // HOLD Customer CALL
            EslConnectionUtil.sendExecuteCommand(
                    "set",
                    "park_after_bridge=true",
                    customerChannel.getUuid()
            );
            customerChannel.setFlag(ChannelFlag.HOLD_CALL);
            logger.info("{} set customerChannel {} HOLD_CALL and park_after_bridge=true",
                    getTraceId(), customerChannel.getUuid());
            ThreadUtil.sleep(10);

            // hangup extension
            callApi.listener.endCall("CallWait.");
            ThreadUtil.sleep(1000);

            EslConnectionUtil.sendExecuteCommand("endless_playback",
                    "$${sounds_dir}/ivr/hold.wav",
                    customerChannel.getUuid()
            );

            customerChannel.setHangupHook(new IOnHangupHook() {
                @Override
                public void onHangup(Map<String, String> eventHeaders, String traceId) {
                    syncState();
                    String tips = "customer call on callWait is hangup.";
                    engine.sendReplyToAgent(new MessageResponse(
                            RespStatus.CUSTOMER_ON_CALL_WAIT_HANGUP, tips, customerChannel)
                    );
                    logger.info(tips);
                    clearHoldCallFlag();
                }
            });

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

    private void sendWsMsg() {
        while (null != customerChannel) {
            synchronized (customerChannel.getUuid().intern()) {
                if (null != customerChannel) {
                    //send ws msg.
                    sendReplyToAgent(new MessageResponse(
                            RespStatus.CUSTOMER_CHANNEL_CALL_WAIT, "customer call session is on callWait.", customerChannel)
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

    protected void stopCallWait(){
        if(null == customerChannel){
            sendReplyToAgent(new MessageResponse(
                    RespStatus.REQUEST_PARAM_ERROR,
                    "No call is on callWait."
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
        if(callApi.listener != null){
            logger.info("{} Hangup the current call on the extension.", getTraceId());
            callApi.listener.endCall("Transfer-Call.");
            ThreadUtil.sleep(1000);
        }

        String bleg = UuidGenerator.GetOneUuid();
        SwitchChannel agentChannel = new SwitchChannel(
                bleg,
                customerChannel.getUuid(),
                customerChannel.getCallType(),
                CallDirection.INBOUND
        );;
        agentChannel.setAnsweredTime(0L);
        agentChannel.setHangupTime(0L);
        agentChannel.setBridgeCallAfterPark(true);
        agentChannel.setSendChannelStatusToWsClient(true);
        agentChannel.setAnsweredHook(new IOnAnsweredHook() {
            @Override
            public void onAnswered(Map<String, String> eventHeaders, String traceId) {
                syncState();
                logger.info("IOnAnsweredHook executed. uuid={}", bleg);
                sendReplyToAgent(new MessageResponse(
                        RespStatus.CUSTOMER_CHANNEL_OFF_CALL_WAIT, "customer call session off call-wait.", customerChannel)
                );
                clearHoldCallFlag();
            }
        });
        customerChannel.setSendChannelStatusToWsClient(true);
        customerChannel.setHangupHook(new IOnHangupHook() {
            @Override
            public void onHangup(Map<String, String> eventHeaders, String traceId) {
                syncState();
                clearHoldCallFlag();
            }
        });
        setHoldCallFlag();
        customerChannel.setUuidBLeg(bleg);
        callApi.connectExtension(agentChannel, customerChannel);
    }

    private void setHoldCallFlag(){
        if( customerChannel != null){
            customerChannel.setFlag(ChannelFlag.HOLD_CALL);
        }
    }

    private void clearHoldCallFlag(){
        if( customerChannel != null){
            customerChannel.clearFlag(ChannelFlag.HOLD_CALL);
        }
    }

}
