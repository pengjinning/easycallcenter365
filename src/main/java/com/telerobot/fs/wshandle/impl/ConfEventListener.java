package com.telerobot.fs.wshandle.impl;

import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.bo.ConfMember;
import com.telerobot.fs.entity.bo.ConfMemerStatus;
import com.telerobot.fs.utils.DateUtils;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.wshandle.MessageResponse;
import com.telerobot.fs.wshandle.RespStatus;
import com.telerobot.fs.wshandle.WebsocketThreadPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 多方会议通话 Fs事件监听器
 */
public class ConfEventListener  implements IEslEventListener {
	private com.telerobot.fs.wshandle.impl.Conference confManager = null;
	protected static final Logger logger = LoggerFactory.getLogger(ConfEventListener.class);

	public ConfEventListener(com.telerobot.fs.wshandle.impl.Conference confObject, String moderatorUuid, String confRoomNO) {
		confManager = confObject;
		moderatorUUID = moderatorUuid;
		this.confRoomNO = confRoomNO;
	}

	private boolean isModeratorAnswered = false;

	/**
	 * 检测主持人的分机是否已经应答
	 *
	 * @return
	 */
	public boolean getIsModeratorAnswered() {
		return isModeratorAnswered;
	}

	private boolean isModeratorHangup = false;

	/**
	 * 检测主持人的分机是否已经挂机
	 */
	public boolean getIsModeratorHangup() {
		return isModeratorHangup;
	}

	/**
	 * 主持人的分机UUID
	 */
	private String moderatorUUID = "";

	private String confRoomNO;

	@Override
	public boolean equals(Object obj) {
		return super.equals(obj);
	}

	@Override
	public void eventReceived(String addr, EslEvent events) {
		WebsocketThreadPool.addTask(new Runnable() {
			@Override
			public void run() {
				Map<String, String> headers = events.getEventHeaders();
				String uniqueID = headers.get("Unique-ID");
				String eventName = events.getEventName();
				String sipCode = headers.get("variable_proto_specific_hangup_cause");
				logger.info("{} confEventListener recv {}, uuid={}, sipCode={}", confManager.getTraceId(), eventName, uniqueID, sipCode);
				if (null == sipCode) {
					sipCode = "";
				}
				if (sipCode.startsWith("sip:")) {
					sipCode = sipCode.replace("sip:", "");
				}
				String phone = headers.get("variable_conf_member_phone");
				if (eventName.equals(EventNames.CHANNEL_ANSWER)) {
					if (uniqueID.equals(moderatorUUID)) {
						isModeratorAnswered = true;
						// 内线已经接通，分机已经应答
						JSONObject jsonObject = new JSONObject();
						jsonObject.put("roomNo", confManager.getConfRoomNO());
						confManager.sendReplyToAgent(new MessageResponse(
								RespStatus.CONFERENCE_MODERATOR_ANSWERED,
								"主持人已接通", jsonObject)
						);
						String recordPath = String.format("%s/%s.mp4",
								DateUtils.format(new Date(), "yyyy-MM-dd"),
								confRoomNO
						);
                        confManager.addConferenceRecord(recordPath, confRoomNO);
					} else {
						setConferenceMemberId(phone, uniqueID);
						confManager.stopPlayRingTone();
						confManager.updateMemberStatus(phone, ConfMemerStatus.connected, "", sipCode);
					}
				} else if (eventName.equals(EventNames.CHANNEL_HANGUP)) {
					String hangupClause = headers.get("Hangup-Cause");
					if (uniqueID.equals(moderatorUUID)) {
						isModeratorHangup = true;
						JSONObject jsonObject = new JSONObject();
						jsonObject.put("roomNo", confManager.getConfRoomNO());
						jsonObject.put("hangupCause", confManager.processDialFailCase(hangupClause));
						confManager.sendReplyToAgentEx(
								RespStatus.CONFERENCE_MODERATOR_HANGUP,
								"主持人已挂断，会议结束.",
								jsonObject
						);
						logger.info("{} 主持人已挂断, sipCode={} ", confManager.getTraceId(),  sipCode);
					    confManager.dispose();
					} else {
						confManager.writeConferenceMemberRecord(phone);
						confManager.updateMemberStatus(phone, ConfMemerStatus.hangup, hangupClause, sipCode);
					}
				}
			}
		});
	}

	@Override
	public void backgroundJobResultReceived(String addr, EslEvent event) {
	}

	@Override
	public String context() {
		return this.getClass().getName();
	}


	/**
	 * 通过uuid_dump方式获取CHANNLE_DATA，从而得到conference_member_id，以便实现会议禁言/允许发言的功能;
	 *
	 * @param phone
	 */
	public void setConferenceMemberId(String phone, String uniqueID) {
		ConfMember member = confManager.getConfMemberByPhone(phone);
		if (null != member) {
			EslMessage eslMsg = EslConnectionUtil.sendSyncApiCommand("uuid_dump", uniqueID, confManager.connPool);
			if (null != eslMsg) {
				List<String> headers = eslMsg.getBodyLines();
				String memberId = "";
				for (String header : headers) {
					if(header.trim().startsWith("variable_conference_member_id")){
						memberId = header.split(":")[1].trim();
						break;
					}
				}

				if (!StringUtils.isNullOrEmpty(memberId)) {
					member.setConferenceMemberId(memberId);
					logger.info("{} successfully get conference member_id={} for {}",
							confManager.getTraceId(), memberId, member.getPhone());
				} else {
					logger.error("{} cant not get conference member_id for: {}",
							confManager.getTraceId(), member.getPhone());
				}
			}else {
				logger.error("{} uuid_dump error, cant not get conference_member_id, mute/unmute function can't work. uuid: {}"
						, confManager.getTraceId(), uniqueID);
			}
		}
	}


}
