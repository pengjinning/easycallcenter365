package com.telerobot.fs.wshandle.impl;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.*;
import com.telerobot.fs.mybatis.dao.ConferenceDao;
import com.telerobot.fs.mybatis.dao.ConferenceMemberDao;
import com.telerobot.fs.utils.*;
import com.telerobot.fs.wshandle.*;
import link.thingscloud.freeswitch.esl.EslConnectionPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;

import com.alibaba.fastjson.JSON;

/**
 * 多方会议模块
 *
 *   * 1，首先接通主持人的话机；
 *     2. 呼叫每个参与者;
 *     3. 记录话单;
 */
public class Conference extends MsgHandlerBase {
	/**
	 * Conference支持的操纵方法
	 */
	private static List<String> conferenceAllowedMethodList = new ArrayList<String>(5);


	static {
		//  增加启动会议的方法;  传递会议模版布局;
		conferenceAllowedMethodList.add("startconf");
		conferenceAllowedMethodList.add("add");
		conferenceAllowedMethodList.add("remove");
		conferenceAllowedMethodList.add("mute");
		conferenceAllowedMethodList.add("unmute");
		conferenceAllowedMethodList.add("vmute");
		conferenceAllowedMethodList.add("unvmute");
		conferenceAllowedMethodList.add("endconf");
		conferenceAllowedMethodList.add("reset");
	}

	public Conference() {	}
	private ConferenceDao conferenceService = AppContextProvider.getBean(ConferenceDao.class);
	private ConferenceMemberDao conferenceMemberService = AppContextProvider.getBean(ConferenceMemberDao.class);

	/**
	 * 同步对象
	 */
	private final Object syncRoot = new Object();

	/**
	 * 会议成员列表
	 */
	private Map<String, ConfMember> conferenceMemberList = new ConcurrentHashMap<String, ConfMember>(30);

	/**
	 * 通过uuid查找ConfMember对象
	 * @return
	 */
	public ConfMember getConfMemberByPhone(String phone){
		return conferenceMemberList.get(phone.trim());
	}

	private class HandlerInitializer implements IMsgHandlerInitializer {
		@Override
		public void activeCurrentHandlerInstance() {
			logger.info(" Conference object  actived ...");
		}
		@Override
		public void destroyHandlerInstance() {
			logger.info(" {} Conference object for user {} is destroyed.",
					getTraceId(), getExtNum());
		}
	}
	@Override
	public void activeCurrentObject(MessageHandlerEngine msgHandlerEngine, IMsgHandlerInitializer... initializer) {
		super.activeCurrentObject(msgHandlerEngine, new HandlerInitializer());
	}


	/**
	 * 向FsServer发送指令
	 */
	private void sendCommandToFsServer(MsgStruct msgCmd) {
		if (!super.getIsDisposed()) {
			try {
				ConferenceCommand confCommand = JSON.parseObject(msgCmd.getBody(), ConferenceCommand.class);
				String method = confCommand.getMethod().toLowerCase().trim();
				if (!conferenceAllowedMethodList.contains(method)) {
					sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "operation not supported.");
					return;
				}

				if (!"endconf".equalsIgnoreCase(method) && !"startconf".equalsIgnoreCase(method) ) {
					List<ConfMember> phoneList = confCommand.getPhoneListEx();
					if (phoneList == null || phoneList.size() == 0) {
						sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "phoneList is null.");
						return;
					}
				}

				// 如果method不是 startconf，需要先判断是否有会议可以被操纵;
				if (!"startconf".equals(method) && StringUtils.isNullOrEmpty(getModeratorUuid())) {
					sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "no conference found");
					return;
				}

				if ("startconf".equals(method) && !StringUtils.isNullOrEmpty(getModeratorUuid())) {
					sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "conference is present");
					return;
				}

				if (!StringUtils.isNullOrEmpty(method)) {
					switch (method) {
					case "add":
						// 添加到会议
						logger.debug("process conference add method...");
						addToConference(confCommand);
						break;
					case "remove":
						// 从会议室移除
						logger.debug("process conference remove method...");
						removeConferenceMember(confCommand);
						break;
				   case "startconf":
				   	    // 发起会议
						logger.debug("process conference startconf method...");
						startConference(confCommand);
						break;
					case "endconf":
						// 结束会议
						logger.debug("process conference endconf method...");
						endConference();
						break;
					case "mute":
						// 禁言
						logger.debug("process conference mute method...");
						muteMember(confCommand);
						break;
					case "unmute":
						// 允许发言
						logger.debug("process conference unmute method...");
						unMuteMember(confCommand);
						break;
					case "vmute":
							logger.debug("process conference vmute method...");
							vmuteMember(confCommand);
							break;
					case "unvmute":
							logger.debug("process conference unvmute method...");
						    UnVMuteMember(confCommand);
							break;
					case "reset":
						// 主持人通话状态复位; 主持人话机未收到挂机信号的时候，而话机确实挂断了;
						this.setModeratorUuid("");
						logger.info("moderator " + this.getExtNum() + " status reset success.");
						sendReplyToAgentEx(RespStatus.CONFERENCE_MODERATOR_RESET, "reset");
						break;
					default:
						MessageResponse replyMsg = new MessageResponse();
						replyMsg.setMsg("operation not supported");
						replyMsg.setStatus(RespStatus.REQUEST_PARAM_ERROR);
						sendReplyToAgent(replyMsg);
						break;
					}
				} else {
					sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "operation not supported");
				}
			} catch (Exception ex) {
				if (ex instanceof com.alibaba.fastjson.JSONException) {
					sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "request body is invalid json.");
				} else {
					logger.info("Conference类的sendCommandToFsServer发生异常" + ex.toString() + ", details:" + CommonUtils.getStackTraceString(ex.getStackTrace()));
				}
			}
		}
	}



	private String getExtNum() {
		return this.getSessionInfo().getExtNum();
	}


	protected EslConnectionPool connPool = EslConnectionUtil.getDefaultEslConnectionPool();

	/**
	 * 多人通话开始时间
	 */
	private long confStartTime = 0;
	/**
	 * 主持人的通话uuid
	 */
	private String moderatorUuid = "";

	/**
	 *  本次视频会议的布局; 2x2还是3x3等
	 */
	private String currentVideoLayOut = "";

	/**
	 *  本次会议的类型;  audio/video
	 */
	private String currentCallType = "";

	/**
	 *  本次会议的使用的模版; (在conference.conf.xml定义的模版)
	 */
	private String currentConfTemplate = "";

	/**
	 * 电话会议密码
	 */
	private String confPin = ""; 
	
	/**
	 * 会议室房间号
	 */
	private String confRoomNO="";

	/**
	 *  会议布局
	 */
	private String confLayout = "";

	/**
	 * 获取当前主持人通话的内线uuid
	 * 
	 * @return
	 */
	public String getModeratorUuid() {
		return moderatorUuid;
	}

	/**
	 * 设置当前主持人通话的内线uuid
	 * 
	 * @return
	 */
	public void setModeratorUuid(String uuid_inner) {
		moderatorUuid = uuid_inner;
	}

	public String getConfRoomNO() {
		return confRoomNO;
	}

	/**
	 * 一个多方通话结束之后，重置Conference对象状态
	 */
	public void resetConferenceStatus() {
		if (super.getIsDisposed()) {
			return;
		}
		this.setModeratorUuid("");
	}

	/**
	 * 主持人挂机之后，切断所有通话; (说明：正常情况下，主持人挂机之后，所有已经接通的成员都会被自动挂机;
	 * 但是此时未接通的电话会继续外呼并接通，需要取消这些通话;)
	 */
	public void killAllConferenceMembersCall() {
		if (this.getIsDisposed()) {
			return;
		}
		Iterator<Map.Entry<String, ConfMember>> it = conferenceMemberList.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ConfMember> entry = it.next();
			ConfMember m = entry.getValue();
			if (m.getStatus().getIndex() != ConfMemerStatus.hangup.getIndex()) {
				String uuid = m.getCallUuid();
				logger.info(String.format("try to kill un-answered conference members, phone: %s ,uuid: %s",
						m.getPhone(), uuid));
				if (this.fsEventListener != null) {
					killCall(uuid);
				}
			}
		}
	}

	private Object saveConfCdrLocker = new Object();

	private com.telerobot.fs.wshandle.impl.ConfEventListener fsEventListener = null;

	public com.telerobot.fs.wshandle.impl.ConfEventListener GetFsEventListener() {
		return fsEventListener;
	}

	/**
	 * 设置conference_member_id，以便实现会议禁言/允许发言的功能;
	 */
	protected void setConferenceMemberId(String uuid, String member_id) {
		ConfMember destMember = null;
		Iterator<Map.Entry<String, ConfMember>> it = conferenceMemberList.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ConfMember> entry = it.next();
			ConfMember member = entry.getValue();
			if (member.getCallUuid().equals(uuid)) {
				destMember = member;
				break;
			}
		}
		if (destMember != null) {
			destMember.setConferenceMemberId(member_id);
		}
	}

	/**
	 * 获取正在通话中的电话数量
	 * 
	 * @return
	 */
	private int getInCallCount() {
		Iterator<Map.Entry<String, ConfMember>> it = conferenceMemberList.entrySet().iterator();
		int count = 0;
		while (it.hasNext()) {
			Map.Entry<String, ConfMember> entry = it.next();
			ConfMember member = entry.getValue();
			if (member.getStatus().equals("answered")) {
				count += 1;
			}
		}
		logger.debug("当前电话会议" + this.getExtNum() + "已接通人数：" + count);
		return count;
	}

	/**
	 * 是否已经播放了提示呼叫提示音
	 */
	private Boolean playedRingTone = false;

	private boolean checkContiuePlayRingTone() {
		return playedRingTone && fsEventListener != null && conferenceMemberList.size() != 0 && getInCallCount() == 0
				&& !fsEventListener.getIsModeratorHangup();
	}

	private void playRingTone() {
		if (getInCallCount() != 0) {
			return;
		}
		WebsocketThreadPool.addTask(new Runnable() {
			@Override
			public void run() {
				if (getInCallCount() != 0) {
					return;
				}
				if (!playedRingTone && fsEventListener != null && !fsEventListener.getIsModeratorHangup()) {
					playedRingTone = true;
					boolean continuePlay = checkContiuePlayRingTone();
					int playSoundTotalTime = 0;
					while (continuePlay) {
						logger.debug("正在播放电话会议提示音:" + getExtNum());
						EslConnectionUtil.sendExecuteCommand("playback", "$${sounds_dir}/ringout.wav", moderatorUuid, connPool);
						int startMills = 0;
						while(playedRingTone){
						    ThreadUtil.sleep(10);
						    startMills += 10;
						    playSoundTotalTime += 10;
						    if(startMills > 5000) {
								break;
							}
						    // 播放一次声音后，停顿5秒
						}
						continuePlay = checkContiuePlayRingTone();
						if(playSoundTotalTime > 50000) {
							break;
						}//放铃音超时为50秒;
					}
				}
			}
		});
	}

	/**
	 * 停止播放呼叫提示音
	 */
	public void stopPlayRingTone() {
		playedRingTone = false;
		if (fsEventListener != null && fsEventListener.getIsModeratorAnswered()) {
			playedRingTone = false;
			EslConnectionUtil.sendSyncApiCommand("uuid_break", this.getModeratorUuid() + " " + "all", connPool);
		}
	}


	/**
	 *  写入参会者的参会记录;
	 */
	protected void writeConferenceMemberRecord(String phone){
		try {
			ConfMember destMember = conferenceMemberList.get(phone.trim());
			if (destMember == null) {
				return;
			}
			long now = System.currentTimeMillis();
			ConferenceMemberRecord record = new ConferenceMemberRecord();
			record.setId(UuidGenerator.GetOneUuid());
			record.setConferenceId(conferenceEntity.getId());
			record.setStartTime(destMember.getAnsweredTime());
			record.setEndTime(now);
			record.setPhone(phone);
			record.setUserId("");
			record.setTimeLen(0);
			if (destMember.getAnsweredTime() > 0L) {
				record.setTimeLen((int) ((now - destMember.getAnsweredTime()) / 1000L));
			}
			conferenceMemberService.insertConferenceMember(record);
		}catch (Throwable e){
			logger.info("{} writeConferenceMemberRecord error {} {} ",getTraceId(),
					e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
		}
	}

	
	protected void updateMemberStatus(String phone, ConfMemerStatus status, String hangupClause, String sipCode) {
		ConfMember destMember = conferenceMemberList.get(phone.trim());
		if (destMember == null) {
			return;
		}

		if (!StringUtils.isNullOrEmpty(hangupClause)) {
			String hangupTips = getDialFailCaseTips(hangupClause);
			destMember.setHangupClause(hangupTips);
		}

		destMember.setSipCode(sipCode);
		destMember.setStatus(status);

		if (status.getIndex() == ConfMemerStatus.connected.getIndex()) {
			destMember.setAnsweredTime(System.currentTimeMillis());
			destMember.setHangupClause("");
			sendReplyToAgent(new MessageResponse(
					RespStatus.CONFERENCE_MEMBER_ANSWERED,
					String.format("参会者%s已接通电话", destMember.getPhone()),
					destMember
					)
			);
		}

		if (status.getIndex() == ConfMemerStatus.hangup.getIndex()) {
			destMember.setHangupTime(System.currentTimeMillis());
			conferenceMemberList.remove(destMember.getPhone());
			sendReplyToAgent(new MessageResponse(
							RespStatus.CONFERENCE_MEMBER_HANGUP,
							String.format("参会者%s已挂机.", destMember.getPhone()),
							destMember
					)
			);
		}
	}

	/**
	 * 逐个呼叫会议成员;
	 *  多方视频会议采用独立的网关外呼配置参数，不和CallApi共享参数；
	 *  CallApi需要考虑线路并发和多网关重试等，逻辑相对复杂；
	 *  而电话会议不太需要考虑线路并发等问题，否则会使得代码逻辑较为复杂。
	 */
	public void callMembers(ConfMember[] members) {
		// this.playRingTone();
		String caller = SystemConfig.getValue("conference_gateway_caller");
		String gatewayAddr = SystemConfig.getValue("conference_gateway_addr");
		String profile = SystemConfig.getValue("conference_outboud_profile");

		for (ConfMember member : members) {
			String destPhone = member.getPhone();
			String uuid = UuidGenerator.GetOneUuid();
			connPool.getDefaultEslConn().addListener(uuid, fsEventListener);

			StringBuilder callParam = new StringBuilder();
			callParam.append(String.format("{conf_member_phone=%s", destPhone));
			callParam.append(String.format(",hangup_after_bridge=true,origination_uuid=%s", uuid ));
			callParam.append(
					member.getCallType().equalsIgnoreCase(PhoneCallType.AUDIO_CALL) ?
					",absolute_codec_string=pcma" :
					String.format(",rtp_force_video_fmtp='profile-level-id=%s;packetization-mode=1'", member.getVideoLevel())
			);
			callParam.append(String.format(",origination_caller_id_number=%s,origination_caller_id_name=%s", caller, caller));
			callParam.append(String.format(",effective_caller_id_number=%s,effective_caller_id_name=%s", caller, caller));
			callParam.append(String.format("}sofia/%s/%s@%s", profile, destPhone, gatewayAddr));
			callParam.append(String.format(" &conference(%s@%s++flags{nomoh})", this.confRoomNO, this.currentConfTemplate));

			String callParams = callParam.toString();
			String msg = EslConnectionUtil.sendAsyncApiCommand("originate", callParams, connPool);
            logger.info("{} Conference call member: {}, response: {}", getTraceId(), destPhone, msg);
			member.setAddTime(System.currentTimeMillis());
			member.setCallUuid(uuid);
		}
	}

	/**
	 * 过滤重复的参会者;
	 * @param phoneList
	 * @return
	 */
	private ConfMember[] addMembers(ConfMember[] phoneList) {
		StringBuilder repeatTips = new StringBuilder();
		StringBuilder videoLevelWarnTips = new StringBuilder("");
		ArrayList<ConfMember> phoneListNoRepeat = new ArrayList<>(10);
		for (ConfMember ele : phoneList) {
			String memberName = ele.getName();
			String telephone = ele.getPhone();
			if ((RegExp.isMobile(telephone, "telephone")).checkInvalid()) {
				repeatTips.append(memberName).append(",").append("invalid phone ").append(telephone);
				continue;
			}
			String videoLevel = ele.getVideoLevel();
			if(!VideoConfigs.checkVideoLevels(videoLevel)) {
                ele.setVideoLevel(VideoConfigs.DEFAULT_VIDEO_LEVEL);
                logger.info("Auto change videoLevel from '{}'  to  '{}'  for  member {}. ", videoLevel, VideoConfigs.DEFAULT_VIDEO_LEVEL, telephone);
				videoLevelWarnTips.append("UnSupported videoLevel " + videoLevel + " for member " + telephone + "; ");
			}

			ele.setRoomNo(this.confRoomNO);

			if (conferenceMemberList.get(telephone) == null) {
				conferenceMemberList.put(telephone, ele);
				phoneListNoRepeat.add(ele);
			} else {
				repeatTips.append(memberName).append(",").append(telephone);
			}

		}

		String str = repeatTips.toString();
		if (!StringUtils.isNullOrEmpty(str)) {
			sendReplyToAgentEx(RespStatus.CONFERENCE_REPEAT_CALLEE, "重复的参会者:" + str);
		}
		if(videoLevelWarnTips.length() > 2){
			sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR,
					"videoLevel parameter Error:" + videoLevelWarnTips.toString());
		}

		ConfMember[] memberList = new ConfMember[phoneListNoRepeat.size()];
		for (int i = 0; i < phoneListNoRepeat.size(); i++) {
			memberList[i] = phoneListNoRepeat.get(i);
		}
		return memberList;
	}

	private final Object addToConfLocker = new Object();

	private void addToConference(ConferenceCommand cmd) {
		confStartTime = System.currentTimeMillis();

		// 首先检测主持人的通话是否正常; 避免网络等原因未收到挂机信号时，显示主持人仍在会议中，但实际通话已经结束的情况;
		if (!StringUtils.isNullOrEmpty(this.getModeratorUuid())) {
			if (fsEventListener != null) {
				logger.info("check whether moderator is in conference, uuid:" + this.getModeratorUuid());
				EslMessage eslReply = EslConnectionUtil.sendSyncApiCommand("uuid_exists", this.getModeratorUuid(), connPool);
				if (eslReply != null) {
					if (!eslReply.getBodyLines().get(0).trim().equals("true")) {
						logger.info("conference moderator's uuid " + this.getModeratorUuid()
								+ " not exists , conference ends.");
						this.setModeratorUuid("");
					}
				}
			}
		}

		ConfMember[] members = addMembers(cmd.getMemberList());

		if (!StringUtils.isNullOrEmpty(this.getModeratorUuid())) {
			// 逐个呼叫参会者的电话
			if (fsEventListener != null && fsEventListener.getIsModeratorAnswered()
					&& !fsEventListener.getIsModeratorHangup()) {
				callMembers(members);
			}
		}
	}
	
	private String getFirstMemberofConf(){
		String destNumber = "";
		Iterator<Map.Entry<String, ConfMember>> it = conferenceMemberList.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ConfMember> entry = it.next();
			ConfMember member = entry.getValue();
			destNumber = member.getPhone();
			break;
		}
		return destNumber;
	}

	private ConferenceEntity conferenceEntity = new ConferenceEntity();

	/**
	 *   增加会议记录;
	 * @param recordPath 录音/录像的相对路径
	 */
	public void addConferenceRecord(String recordPath, String confRoomNO){
		conferenceEntity.setConfPassword(String.valueOf(RandomUtils.getRandomByRange(100000,999999)));
		conferenceEntity.setEndTime(0L);
		conferenceEntity.setId(UuidGenerator.GetOneUuid());
		conferenceEntity.setMaxConcurrency(30);
		conferenceEntity.setModerator(this.getExtNum());
		conferenceEntity.setUserId("");
		conferenceEntity.setRoomNo(confRoomNO);
		conferenceEntity.setStartTime(System.currentTimeMillis());
		conferenceEntity.setRecordPath(recordPath);
        conferenceService.addConference(conferenceEntity);

	}

	/**
	 *   修改会议记录;
	 */
	private void updateConferenceRecord(){
		if(conferenceEntity.getStartTime() != null) {
			long now = System.currentTimeMillis();
			conferenceEntity.setEndTime(now);
			conferenceEntity.setTimeLen((int) ((now - conferenceEntity.getStartTime()) / 1000L));
			conferenceService.updateConference(conferenceEntity);
		}else{
			logger.info("会议没有发起成功，不在写入会议话单. {}", getTraceId());
		}
	}


	/**
	 * 呼叫主持人的分机
	 */
	private void callModerator(ConferenceCommand cmd) {
		String uuid = UuidGenerator.GetOneUuid();
		this.moderatorUuid = uuid;
		// 主持人的通话uuid
		StringBuilder callParam = new StringBuilder();
		String caller = SystemConfig.getValue("conference_gateway_caller");
		callParam.append(String.format("{moderator=%s", this.getExtNum()));

		if(PhoneCallType.VIDEO_CALL.equalsIgnoreCase(currentCallType)) {
			callParam.append(",rtp_force_video_fmtp='profile-level-id=42e01e;packetization-mode=1'");
		}else{
			callParam.append(",absolute_codec_string=pcma");
		}

		callParam.append(String.format(",hangup_after_bridge=true,origination_uuid=%s", uuid));
		callParam.append(String.format(",origination_caller_id_number=%s,origination_caller_id_name=%s", caller, caller));
		callParam.append(String.format(",effective_caller_id_number=%s,effective_caller_id_name=%s", caller, caller));
		callParam.append(String.format("}user/%s", this.getExtNum()));

		this.confPin = String.valueOf(RandomUtils.getRandomByRange(1000, 9999));
		//电话会议接入密码; 通过拨号计划接入;
		this.confRoomNO = this.getExtNum() + DateUtils.format(new Date(), "HHmmss");
		// 需要传递的参数： 会议的Name名称、会议模版名称、会议密码
		callParam.append(String.format(" &conference(%s@%s+%s+flags{endconf|moderator|nomoh})", this.confRoomNO, this.currentConfTemplate, this.confPin));
		String callParams = callParam.toString();

		fsEventListener = new com.telerobot.fs.wshandle.impl.ConfEventListener(this, uuid, this.confRoomNO);
		connPool.getDefaultEslConn().addListener(uuid, fsEventListener);
		// 先接通主持人的电话
		EslConnectionUtil.sendAsyncApiCommand("originate", callParams, connPool);
		logger.info("callModerator: originate " + callParams);

		long startTime = System.currentTimeMillis();
		while (fsEventListener != null && !fsEventListener.getIsModeratorAnswered()
				&& !fsEventListener.getIsModeratorHangup()) {
			ThreadUtil.sleep(10);
			int passedSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
			// 主持人 30秒接听超时;
			if (passedSeconds > 30) {
				break;
			}
		}

		// 超时未接听，自动挂断并结束通话
		if (fsEventListener != null && !fsEventListener.getIsModeratorAnswered()
				&& !fsEventListener.getIsModeratorHangup()) {
			EslConnectionUtil.sendSyncApiCommand("uuid_kill", uuid, connPool);
			sendReplyToAgentEx(RespStatus.CONFERENCE_MODERATOR_HANGUP, "会议主持人应答超时");
			this.resetConferenceStatus();
			return;
		}
		if (fsEventListener != null && fsEventListener.getIsModeratorAnswered()) {
			this.setModeratorUuid(uuid);
			// 此时主持人的电话被接通

			if (fsEventListener != null && fsEventListener.getIsModeratorAnswered()) {
				if(PhoneCallType.VIDEO_CALL.equalsIgnoreCase(currentCallType)) {
					WebsocketThreadPool.addTask(new Runnable() {
						@Override
						public void run() {
							ThreadUtil.sleep(5000);
							// 等待5秒后，设置会议的layout
							setConferenceLayout();
						}
					});
				}
			}
		}
	}

	private void setConferenceLayout(){
		EslMessage eslMsg = EslConnectionUtil.sendSyncApiCommand("conference",
				String.format("%s vid-layout %s", confRoomNO, this.currentVideoLayOut), connPool);

		if (eslMsg != null && eslMsg.getBodyLines().size() != 0) {
			String replyString = CommonUtils.ListToString(eslMsg.getBodyLines());
			if (replyString.contains("OK")) {
				sendReplyToAgentEx(
						RespStatus.CONFERENCE_LAYOUT_CHANGED,
						String.format("conference layout changed to %s", this.currentVideoLayOut)
				);
				return;
			}
		}

		sendReplyToAgentEx(
				RespStatus.SERVER_ERROR,
				String.format("set conference layout failed: %s", this.currentVideoLayOut)
		);
	}

	private void processServerInternalError(String msgTips, Boolean... displayErrorDetail) {
		MessageResponse msg = new MessageResponse();
		msg.setStatus(500);
		if (displayErrorDetail != null && displayErrorDetail.length != 0) {
			msg.setMsg(msgTips);
		}
		this.sendReplyToAgent(msg);
		resetConferenceStatus();
		logger.error(msgTips);
		dispose();
	}

	/**
	 * 移除指定的通话
	 * 
	 * @param cmd
	 */
	private void removeConferenceMember(ConferenceCommand cmd) {
		ConfMember[] destPhones = cmd.getMemberList();
		List<ConfMember>  destMembers = new ArrayList<>(10);
		for (ConfMember phone : destPhones) {
			ConfMember m = conferenceMemberList.get(phone.getPhone());
			if (m != null) {
				destMembers.add(m);
			} else {
				logger.info(phone.getPhone() + " is not in conference.");
				// 如果用户已经不再会议中，则再次发送下用户挂机的消息，便于客户端移除该用户; 否则可能出现无法移除的情况；
				sendReplyToAgent(new MessageResponse(RespStatus.CONFERENCE_MEMBER_HANGUP,
						String.format("参会者%s已被移除.", phone.getPhone()), phone))
				;
			}
		}
		killCallEx(destMembers);
	}
	
	private void killCallEx(List<ConfMember> confMembers) {
		for (ConfMember m : confMembers) {
			if (fsEventListener != null) {
				EslConnectionUtil.sendSyncApiCommand("uuid_kill", m.getCallUuid(), connPool);
			}
		}
	}
	
	/**
	 * 禁言会议成员
	 * 
	 * @param cmd
	 */
	private void muteMember(ConferenceCommand cmd) {
		ConfMember[] phoneList = cmd.getMemberList();
		for (ConfMember phone : phoneList) {
			ConfMember destMember = conferenceMemberList.get(phone.getPhone());
			if (destMember != null) {
				String conferenceMemberId = destMember.getConferenceMemberId();
				if (!StringUtils.isNullOrEmpty(conferenceMemberId)) {
					EslMessage eslMsg = EslConnectionUtil.sendSyncApiCommand("conference",
							String.format("%s mute %s", confRoomNO, conferenceMemberId), connPool);
					if (eslMsg != null && eslMsg.getBodyLines().size() != 0) {
						String replyString = CommonUtils.ListToString(eslMsg.getBodyLines());
						if (replyString.contains("OK")) {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_MUTED_SUCCESS,
									String.format("%s已被禁言.", destMember.getName()),
									destMember
							);
						} else {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_MUTED_FAILED,
									String.format("%s禁言失败，详情:%s", destMember.getName(), replyString),
									destMember
							);
						}
					} else {
						sendReplyToAgentEx(
								RespStatus.CONFERENCE_MEMBER_MUTED_FAILED,
								String.format("%s禁言失败，详情: 电话服务异常，请重试. ", destMember.getName()),
								destMember
						);
					}
				} else {
					sendReplyToAgentEx(608, "mute failed, can't get member_id.", destMember);
				}
			} else {
				sendReplyToAgentEx(608, "mute failed, can't get find member " + phone.getPhone(), phone);
			}
		}
	}

	/**
	 * 允许会议成员发言
	 * 
	 * @param cmd
	 */
	private void unMuteMember(ConferenceCommand cmd) {
		ConfMember[] phoneList = cmd.getMemberList();
		for (ConfMember phone : phoneList) {
			ConfMember destMember = conferenceMemberList.get(phone.getPhone());
			if (destMember != null) {
				String conferenceMemberId = destMember.getConferenceMemberId();
				if (!StringUtils.isNullOrEmpty(conferenceMemberId)) {
					EslMessage eslMsg =  EslConnectionUtil.sendSyncApiCommand("conference",
							String.format("%s unmute %s", confRoomNO, conferenceMemberId), connPool);
					if (eslMsg != null && eslMsg.getBodyLines().size() != 0) {
						String replyString = CommonUtils.ListToString(eslMsg.getBodyLines());
						if (replyString.contains("OK")) {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_UNMUTED_SUCCESS,
									destMember.getName() + "已解除禁言.",
									destMember
							);
						} else {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_UNMUTED_FAILED,
									destMember.getName() + "解除禁言失败,请稍后重试. 详情:" + replyString,
									destMember
							);
						}
					} else {
						sendReplyToAgentEx(
								RespStatus.CONFERENCE_MEMBER_UNMUTED_FAILED,
								destMember.getName() + "解除禁言失败, 服务器异常，请稍后重试.",
								destMember
						);
					}
				} else {
					sendReplyToAgentEx(610, "unmute failed, can't get member_id.", destMember);
				}
			} else {
				sendReplyToAgentEx(
						RespStatus.CONFERENCE_MEMBER_NOT_EXISTS,
						"没有找到参会成员" + phone.getName(),
						phone
				);
			}
		}
	}

	/**
	 * 视频禁用 会议成员
	 *
	 * @param cmd
	 */
	private void vmuteMember(ConferenceCommand cmd) {
		ConfMember[] phoneList = cmd.getMemberList();
		for (ConfMember phone : phoneList) {
			ConfMember destMember = conferenceMemberList.get(phone.getPhone());
			if (destMember != null) {
				String conferenceMemberId = destMember.getConferenceMemberId();
				if (!StringUtils.isNullOrEmpty(conferenceMemberId)) {
					EslMessage eslMsg = EslConnectionUtil.sendSyncApiCommand("conference",
							String.format("%s vmute %s", confRoomNO, conferenceMemberId), connPool);
					if (eslMsg != null && eslMsg.getBodyLines().size() != 0) {
						String replyString = CommonUtils.ListToString(eslMsg.getBodyLines());
						if (replyString.contains("OK")) {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_VMUTED_SUCCESS,
									String.format("%s的视频已被禁用.", destMember.getName()),
									destMember
							);
						} else {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_VMUTED_FAILED,
									String.format("%s禁用视频失败，详情:%s", destMember.getName(), replyString),
									destMember
							);
						}
					} else {
						sendReplyToAgentEx(
								RespStatus.CONFERENCE_MEMBER_VMUTED_FAILED,
								String.format("%s禁用视频失败，详情: 电话服务异常，请重试. ", destMember.getName()),
								destMember
						);
					}
				} else {
					sendReplyToAgentEx(RespStatus.SERVER_ERROR, "vmute failed, can't get member_id.", destMember);
				}
			} else {
				sendReplyToAgentEx(RespStatus.SERVER_ERROR, "vmute failed, can't get find member " + phone.getPhone(), phone);
			}
		}
	}

	/**
	 * 启用视频 会议成员
	 *
	 * @param cmd
	 */
	private void UnVMuteMember(ConferenceCommand cmd) {
		ConfMember[] phoneList = cmd.getMemberList();
		for (ConfMember phone : phoneList) {
			ConfMember destMember = conferenceMemberList.get(phone.getPhone());
			if (destMember != null) {
				String conferenceMemberId = destMember.getConferenceMemberId();
				if (!StringUtils.isNullOrEmpty(conferenceMemberId)) {
					EslMessage eslMsg = EslConnectionUtil.sendSyncApiCommand("conference",
							String.format("%s unvmute %s", confRoomNO, conferenceMemberId), connPool);
					if (eslMsg != null && eslMsg.getBodyLines().size() != 0) {
						String replyString = CommonUtils.ListToString(eslMsg.getBodyLines());
						if (replyString.contains("OK")) {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_UnVMUTED_SUCCESS,
									String.format("%s的视频已开启.", destMember.getName()),
									destMember
							);
						} else {
							sendReplyToAgentEx(
									RespStatus.CONFERENCE_MEMBER_UnVMUTED_FAILED,
									String.format("%s视频开启失败，详情:%s", destMember.getName(), replyString),
									destMember
							);
						}
					} else {
						sendReplyToAgentEx(
								RespStatus.CONFERENCE_MEMBER_UnVMUTED_FAILED,
								String.format("%s视频开启失败，详情: 电话服务异常，请重试. ", destMember.getName()),
								destMember
						);
					}
				} else {
					sendReplyToAgentEx(RespStatus.SERVER_ERROR, "unvmute failed, can't get member_id.", destMember);
				}
			} else {
				sendReplyToAgentEx(RespStatus.SERVER_ERROR, "unvmute failed, can't get find member " + phone.getPhone(), phone);
			}
		}
	}

	private void killCall(String uuid) {
		if (fsEventListener != null) {
			EslConnectionUtil.sendSyncApiCommand("uuid_kill", uuid, connPool);
		}
	}

	private void killCall(List<String> destUuids) {
		for (String uuid : destUuids) {
			if (fsEventListener != null) {
				killCall(uuid);
			}
		}
	}

	public void transferToConference(ConferenceCommand cmd, SwitchChannel customerChannel, String customerName){
		String outerUuid = customerChannel.getUuid();
		String customerPhone =  customerChannel.getPhoneNumber();
		this.startConference(cmd);

		if (fsEventListener == null || !fsEventListener.getIsModeratorAnswered()) {
			EslConnectionUtil.sendExecuteCommand("hangup", "Moderator not Answered in Conference!", outerUuid, connPool);
			logger.info("{} hangup customerPhone {} due to Moderator not Answered in Conference! ", getTraceId(), customerPhone);
			return;
		}

		// 检测对方是否挂断，如果没有则加入到会议
		if (!StringUtils.isNullOrEmpty(outerUuid)) {
			if (fsEventListener != null) {
				if(customerChannel.getCallDirection().equalsIgnoreCase(CallDirection.INBOUND)) {
					connPool.getDefaultEslConn().addListener(outerUuid + "-ex", fsEventListener);
				}else{
					connPool.getDefaultEslConn().addListener(outerUuid, fsEventListener);
				}

				ConfMember ele = new ConfMember(customerName, customerPhone);
				ele.setVideoLevel(VideoConfigs.DEFAULT_VIDEO_LEVEL);
				ele.setAnsweredTime(System.currentTimeMillis());
				ele.setAddTime(System.currentTimeMillis());
				ele.setCallType(PhoneCallType.VIDEO_CALL);
				ele.setCallUuid(outerUuid);
				ele.setRoomNo(this.confRoomNO);
				conferenceMemberList.put(customerPhone, ele);
				logger.info("{} check outerUuid {} hangup before transfer to Conference", getTraceId(), outerUuid);

				EslMessage eslReply = EslConnectionUtil.sendSyncApiCommand("uuid_exists", outerUuid, connPool);
				if (eslReply != null) {
					if (eslReply.getBodyLines().get(0).trim().equals("true")) {
						String param = String.format("%s@%s++flags{nomoh}", this.confRoomNO, this.currentConfTemplate);
						logger.info("{} try to join video Conference room for user {}  {} ", getTraceId(), customerPhone, customerName );
					    String response = EslConnectionUtil.sendExecuteCommand("conference", param, outerUuid, connPool);
					    if(response.contains("OK")){
					    	EslConnectionUtil.sendExecuteCommand("set", "conf_member_phone=" + customerPhone, outerUuid, connPool);
							updateMemberStatus(customerPhone, ConfMemerStatus.connected, "", "");
							sendReplyToAgentEx(RespStatus.CONFERENCE_TRANSFER_SUCCESS_FROM_EXISTED_CALL,
									"已成功把现有通话转换成多人会议.", ele);

							//设置成员的member_id
							WebsocketThreadPool.addTask(new Runnable() {
								@Override
								public void run() {
									ThreadUtil.sleep(5000);
									fsEventListener.setConferenceMemberId(customerPhone, outerUuid);
								}
							});
						}
					}else{
						logger.warn("{} outerUuid {} is hangup already. Cant not transfer to Conference.", getTraceId(), outerUuid);
						connPool.getDefaultEslConn().removeListener(outerUuid);
						conferenceMemberList.remove(customerPhone);
					}
				}
			}
		}
	}

	/**
	 * 发起电话会议
	 */
	public void startConference(ConferenceCommand cmd) {

		if(cmd.getArgs() == null){
			sendReplyToAgentEx(RespStatus.REQUEST_PARAM_ERROR, "conference args missing!");
			return;
		}

		currentVideoLayOut = cmd.getArgs().getString("layOut");
		currentCallType = cmd.getArgs().getString("callType");
		currentConfTemplate = cmd.getArgs().getString("confTemplate");

		if(!PhoneCallType.AUDIO_CALL.equalsIgnoreCase(currentCallType)) {
			MessageResponse checkResp = VideoConfigs.checkVideoConferenceParameters(currentVideoLayOut, currentCallType, currentConfTemplate);
			if (checkResp != null) {
				sendReplyToAgentEx(checkResp);
				return;
			}
		}

		if (StringUtils.isNullOrEmpty(this.getModeratorUuid())) {
			synchronized (addToConfLocker) {
				if (StringUtils.isNullOrEmpty(this.getModeratorUuid())) {
					callModerator(cmd);
				}
			}
		}
	}

	/**
	 * 结束电话会议
	 */
	private void endConference() {
		String currentUUID = getModeratorUuid();
		if (!StringUtils.isNullOrEmpty(currentUUID)) {
			killCall(currentUUID);
		}
	}

	/**
	 * 处理各种呼叫挂机原因
	 * 
	 * @param responseStr
	 */
	public String processDialFailCase(String responseStr) {
		String reply = "";
		if (responseStr.indexOf("USER_BUSY") != -1) {
			reply = "phone_busy";
		} else if (responseStr.indexOf("USER_NOT_REGISTERED") != -1) {
			reply = "phone_not_registered";
		} else if (responseStr.indexOf("SUBSCRIBER_ABSENT") != -1) {
			reply = "phone_offline_absent";
		} else if (responseStr.indexOf("No such channel") != -1) {

		} else if (responseStr.indexOf("NO_USER_RESPONSE") != -1) {
			reply = "no_user_response";
		} else if (responseStr.indexOf("RECOVERY_ON_TIMER_EXPIRE") != -1) {
			reply = "phone_offline_timer_expired";
		} else {
			reply = responseStr.toLowerCase();
		}
		return reply;
	}

	public String getDialFailCaseTips(String responseStr) {
		String matchStr = "";
		if (responseStr.indexOf("USER_BUSY") != -1) {
			matchStr = "对方忙";
		}else if (responseStr.indexOf("NORMAL_CLEARING") != -1) {
			matchStr = "正常挂机";
		} else if (responseStr.indexOf("USER_NOT_REGISTERED") != -1) {
			matchStr = "对方分机不在线";
		} else if (responseStr.indexOf("SUBSCRIBER_ABSENT") != -1) {
			matchStr = "对方分机不在线";
		} else if (responseStr.indexOf("NO_USER_RESPONSE") != -1) {
			matchStr = "对方无应答";
		} else if (responseStr.indexOf("RECOVERY_ON_TIMER_EXPIRE") != -1) {
			matchStr = "呼叫超时";
		} else {
			matchStr = "呼叫失败";
		}
		return matchStr;
	}

	public void disposeEx() {
		updateConferenceRecord();
		if (!super.getIsDisposed()) {
			synchronized (syncRoot) {
				if (!super.getIsDisposed()) {
					super.dispose();
					logger.info(String.format("conference object for user %s is destroyed.", this.getExtNum()));
				}
			}
		}
	}


	@Override
	public void dispose() {
		updateConferenceRecord();
		if (!super.getIsDisposed()) {
			synchronized (syncRoot) {
				if (!super.getIsDisposed()) {
					// 对象销毁的时候判断下：多方通话是否已经结束，防止在socket异常断开的时候，多方通话无法管理的问题;
					endConference();
					killAllConferenceMembersCall();
					resetConferenceStatus();
					// this.conferenceMemberList.clear();
					super.dispose();
					logger.info(String.format("conference object for user %s is destroyed.", this.getExtNum()));
				}
			}
		}
	}

	@Override
	public void processTask(MsgStruct data) {
		if (this.getSessionInfo() == null || !this.getSessionInfo().IsValid()) {
			logger.error("您尚未登录或登录超时，无法执行拨号等相关操作");
			return;
		}
	 	sendCommandToFsServer(data);
	}

	public void sendReplyToAgentEx(int code, String msg) {
		sendReplyToAgent(new MessageResponse(code, msg));
	}

	public void sendReplyToAgentEx(MessageResponse response) {
		sendReplyToAgent(response);
	}

	public void sendReplyToAgentEx(int code, String msg, Object object) {
		if (super.getIsDisposed()) {
			return;
		}
		sendReplyToAgent(new MessageResponse(code, msg, object));
	}


}
