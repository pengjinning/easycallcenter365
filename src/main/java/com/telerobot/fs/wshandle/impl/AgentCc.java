package com.telerobot.fs.wshandle.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.entity.pojo.AgentStatus;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.wshandle.*;

/**
 *  用户控制； 置忙；置闲；下线；
 */
public class AgentCc extends MsgHandlerBase {
    private AgentCc thisRef = this;
    private SysService sysService = AppContextProvider.getBean(SysService.class);

    @Override
    public void processTask(MsgStruct data) {
        CallArgs callArgs = null;
        try {
            callArgs = JSON.parseObject(data.getBody(), CallArgs.class);
        } catch (Throwable e) {
            Utils.processArgsError("invalid json format:" + e.toString(), thisRef);
            return;
        }
        if (callArgs == null) {
            return;
        }
        String cmd = callArgs.getCmd();
        if (cmd == null || cmd.length() == 0) {
            Utils.processArgsError("cmd param error", thisRef);
            return;
        }
        switch (cmd) {
            case "setStatus":
                int status = callArgs.getArgs().getIntValue("status");
                setStatus(status);
                break;
            case "disconnect":
                MessageHandlerEngineList.getInstance().delete(getSessionInfo().getSessionId(), true);
                break;
            default:
                Utils.processArgsError(String.format("method not supported :%s", cmd), thisRef);
                break;
        }
    }

    public static boolean setAgentStatus(AgentStatus status, String opNum){
        MessageHandlerEngine engine = MessageHandlerEngineList.getInstance().
                getMsgHandlerEngineByOpNum(opNum);
        if(null != engine) {
            AgentCc agentCc = ((AgentCc) engine.getMessageHandleByName("setAgentStatus"));
            if(null != agentCc){
                agentCc.setStatus(status.getIndex());
                return true;
            }
        }
        return false;
    }

    /**
     * set agent status.
     * @param status
     */
    protected void setStatus(int status){
        int affectRow = sysService.setAgentStatus(getSessionInfo().getOpNum(), status);
        String sessionId = getSessionInfo().getSessionId();
        logger.info("try to set acd agent status={}, extNum={}, opNum={}, sessionId={}.",
                status, getSessionInfo().getExtNum(), getSessionInfo().getOpNum(), sessionId);
        if(status == AgentStatus.free.getIndex()) {
            this.getSessionInfo().unLock();
            logger.info("unLock acd agent extNum={}, opNum={}, sessionId={}.",
                    getSessionInfo().getExtNum(), getSessionInfo().getOpNum(), sessionId);
        }
        if (affectRow == 1) {
            logger.info("successfully set acd agent status={}, extNum={}, opNum={}, sessionId={}.",
                    status, getSessionInfo().getExtNum(), getSessionInfo().getOpNum(), sessionId);
            AgentStatus agentStatus = AgentStatus.getItemByValue(status);
            String description = "cant not get description";
            if (null != agentStatus) {
                description = agentStatus.toString();
            }
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("status", status);
            jsonObject.put("text", agentStatus.getText());
            sendReplyToAgent(new MessageResponse(RespStatus.STATUS_CHANGED, "agent status: " + description, jsonObject));
        } else {
            logger.info("Failed to set acd agent status={}, extNum={}, opNum={}, sessionId={}.",
                    status, getSessionInfo().getExtNum(), getSessionInfo().getOpNum(), sessionId);
            sendReplyToAgent(new MessageResponse(RespStatus.SERVER_ERROR, "update agent status error."));
        }
    }

}
