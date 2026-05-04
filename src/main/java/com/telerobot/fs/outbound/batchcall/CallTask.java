package com.telerobot.fs.outbound.batchcall;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.acd.CallHandler;
import com.telerobot.fs.acd.InboundGroupHandler;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.dao.CallTaskEntity;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import com.telerobot.fs.entity.dto.EmptyNumberDetectionConfig;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.global.CdrPush;
import com.telerobot.fs.ivr.IvrEngine;
import com.telerobot.fs.outbound.CallConfig;
import com.telerobot.fs.robot.RobotChat;
import com.telerobot.fs.tts.TtsUtil;
import com.telerobot.fs.utils.*;
import link.thingscloud.freeswitch.esl.EslConnectionPool;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.constant.UuidKeys;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import link.thingscloud.freeswitch.esl.util.CurrentTimeMillisClock;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * a single outbound call task thread.
 **/
public class CallTask implements Runnable {

	private final static Logger log = LoggerFactory.getLogger(CallTask.class);

	protected static ConcurrentHashMap<String, CallTask> callTaskList = new ConcurrentHashMap<>(500);

	final CallTask task = this;

	private volatile CustmInfoEntity phoneInfo = null;

	public CustmInfoEntity getPhoneInfo()
	{
		return phoneInfo;
	}

	private volatile CallTaskEntity batchEntity = null;

	public CallTask() {
	}

	private BatchTaskManager batchMonitor = null;
	private static ThreadPoolExecutor fsMsgThreadPool = null;

	/**
	 *  A single, globally shared thread pool is used for outbound tasks.
	 *  This thread pool is designated for processing robot conversation tasks.
	 */
	private static ThreadPoolExecutor robotConversationTaskThreadPool = null;

	/**
	 *  stats table for empty-number-detection;
	 */
	private static final List<EmptyNumberDetectionConfig> emptyNumberDetectionStatusTable = new ArrayList<>();

	/**
	 *   Identify the status code for empty number detection from the given text.
	 * @param input asr-result
	 * @return
	 */
	public static int getEmptyNumberDetectionCodeByInput(String input) {
		for (EmptyNumberDetectionConfig entry : emptyNumberDetectionStatusTable) {
			String[] keywords = entry.getWords().split(",");
			for (String word : keywords) {
				if (input.contains(word)) {
					return entry.getCode();
				}
			}
		}
		return 0;
	}
	private StringBuilder emptyNumberDetectionText = new StringBuilder(256);
	private void setRecognitionText(){
		String asrText = emptyNumberDetectionText.toString();
		if(asrText.length() > 800){
			asrText = asrText.substring(800);
		}
		phoneInfo.setEmptyNumberDetectionText(asrText);
	}

	private volatile int emptyNumberDetectionCode = 0;
	private void saveEmptyNumberDetection(String asrResult){
		emptyNumberDetectionCode = getEmptyNumberDetectionCodeByInput(asrResult);
		log.info("{} empty-number-detection result" +
				" {}", getTraceId(), emptyNumberDetectionCode);
		if(emptyNumberDetectionCode != 0) {
			phoneInfo.setCallstatus(emptyNumberDetectionCode);
			ScheduledScanTask.addToSQLQueue(phoneInfo);
		}
	}


	static {
		int fsEslMsgThreadPoolSize = Integer.parseInt(AppContextProvider.getEnvConfig("app-config.fs-esl-msg-thread-pool-size", "50"));
		fsMsgThreadPool = ThreadPoolCreator.create(fsEslMsgThreadPoolSize, "fsMsgThreadPool", 12L, 5000);

		int batchTaskThreadNumber = Integer.parseInt(
				AppContextProvider.getEnvConfig("app-config.batch-call.batch-task-thread-number","500")
		);
		robotConversationTaskThreadPool = ThreadPoolCreator.create(
				batchTaskThreadNumber,
				"robotConversation",
				24,
				batchTaskThreadNumber *2
		);

		try {
			String jsonData = SystemConfig.getValue("empty-number-detection-config", "");
			if(!StringUtils.isEmpty(jsonData)) {
				JSONArray jsonArray = JSONArray.parseArray(jsonData);
				for (int i = 0; i < jsonArray.size(); i++) {
					JSONObject jsonObject = jsonArray.getJSONObject(i);
					EmptyNumberDetectionConfig entry = new EmptyNumberDetectionConfig();
					entry.setKey(jsonObject.getString("key"));
					entry.setCode(jsonObject.getIntValue("code"));
					entry.setCat(jsonObject.getString("cat"));
					entry.setWords(jsonObject.getString("words"));
					emptyNumberDetectionStatusTable.add(entry);
				}
			}else{
				log.error("No status code is defined for empty number detection.!");
			}
		} catch (Throwable e) {
			log.error("parse `empty-number-detection-config` param error! {} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
		}

		startMonitor();
	}

	/**
	 *  send hangup command
	 */
	public void hangup(String reaason) {
		EslConnectionUtil.sendExecuteCommand(
				"hangup",
				reaason,
				this.callUuid,
				this.eslConnectionPool
		);
	}


	/**
	 *  Calculate calls that are active (not hung up), not connected, and with outbound time less than 15 seconds.
	 * @param batchId
	 * @return
	 */
	public static int calcUnfinishedCall(int batchId){
		int unFinishedCount = 0;
		Iterator<Map.Entry<String, CallTask>> it = callTaskList.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry<String, CallTask> entry = it.next();
			CallTask task = entry.getValue();
			boolean matched = task.phoneInfo.getBatchId() == batchId;
			if(!matched) {
				continue;
			}
			boolean hangup = task.phoneInfo.getHangup();
			boolean connected = task.phoneInfo.getConnectedTime() > 1;
			long passedTime = (System.currentTimeMillis() - task.phoneInfo.getCalloutTime()) / 1000;
			// 计算出： 未挂机、未接通、外呼时长小于15秒的通话数量
            if(!hangup && !connected && passedTime < 15) {
				unFinishedCount += 1;
			}
		}
		return unFinishedCount;
	}

	/**
	 * The two functions of the startMonitor thread are:
	 * To terminate calls that have timed out without being answered.
	 * To terminate calls that exceed the maximum call duration.
	 */
	private static  void startMonitor(){
		new Thread(new Runnable() {
			@Override
			public void run() {
				while(true){
					Iterator<Map.Entry<String, CallTask>> it = callTaskList.entrySet().iterator();
					int processCount = 0;
					while (it.hasNext()){
						Map.Entry<String, CallTask> entry = it.next();
						CallTask task = entry.getValue();
						long currentTime = CurrentTimeMillisClock.now();
						long timePassed = currentTime - task.phoneInfo.getCalloutTime();
						boolean answered = task.phoneInfo.getConnectedTime() > 0L;
						boolean hangup = task.phoneInfo.getHangup();
						long callTimeOut = CallConfig.callTimeOut * 1000;
						if(timePassed > callTimeOut && !answered && !hangup){
							task.endCall("no-answer-within-timeout-period");
							task.phoneInfo.setCallstatus(37);
							processCount++;
							it.remove();
							task.eslListener.processFsMsg(task.generateHangupEvent("Exceed-callTimeOut"));
						}
					}
					if(processCount != 0) {
						log.info("Forced termination count of robot outbound calls：" + processCount);
					}

					ThreadUtil.sleep(11000);
				}
			}
		}, "monitorCallTaskThread").start();
	}

	private void endCall(String reason){
		log.info("{} end call session, reason: {},  phoneNumEntity: {}" ,
				getTraceId(),
				reason,
				JSON.toJSONString(phoneInfo)
		);
		hangup(reason);
		phoneInfo.setHangup(true);
	}

    private long taskSubmitTime = 0L;

	/**
	 *  create a outbound call thread
	 *
	 * @param _phoneNumEntity
	 * @param _batchEntity
	 */
	public CallTask(BatchTaskManager monitor, CustmInfoEntity _phoneNumEntity, CallTaskEntity _batchEntity) {
		this.batchMonitor = monitor;
		this.batchEntity = _batchEntity;
		this.phoneInfo = _phoneNumEntity;
        taskSubmitTime = System.currentTimeMillis();
	}

	@Override
	public void run() {
		try {
			doCall();
		} catch (Throwable e) {
			log.error("{}  CallTask thread exception：{} {}", getTraceId(), e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()) );
			endCall("CallTask-Thread-Exception");
		}
	}


	private volatile boolean isReleased = false;
	private final Object lockerHelper = new Object();
	private  void releaseThreadNum() {
		if (!isReleased) {
			synchronized (lockerHelper) {
				if (!isReleased) {
					isReleased = true;
					try {
						batchMonitor.releaseThreadNumUsed();
						CallConfig.releaseLine_Used();
						callTaskList.remove(this.callUuid);
					} catch (Throwable ex) {
						log.error("{} releaseThreadNum error，details: {}  {}" ,
								getTraceId() , ex.toString(), CommonUtils.getStackTraceString(ex.getStackTrace()));
					}
				}
			}
		}
	}

	private volatile boolean recordingsStarted = false;
	private synchronized void startWriteRecordings(String wavFile){
		if(!recordingsStarted) {
			RecordingsUtil.startRecordings(wavFile, callUuid);
			recordingsStarted = true;
		}
	}

	private volatile String wavFilePath = "";
	private synchronized String getWavFilePath(){
		if(StringUtils.isEmpty(wavFilePath)) {
			String recordingsBaseDir = "";
			switch (batchEntity.getTaskType()) {
				case 0:
					recordingsBaseDir = "batch_call";
					break;
				case 1:
					recordingsBaseDir = "ai_call";
					break;
				case 2:
					recordingsBaseDir = "voice_call";
					break;
				case 3:
					recordingsBaseDir = "ivr";
					break;
				default:
					recordingsBaseDir = "";
			}
			wavFilePath = String.format("%s/%s/%s.wav",
					recordingsBaseDir,
					DateUtils.format(new Date(), "yyyy/MM/dd/HH"),
					callUuid
			);
		}
		return wavFilePath;
	}

	/**
	 *  Telephone call event listener
	 **/
	class myESLEventListener implements IEslEventListener {
		private final Logger log = LoggerFactory.getLogger(this.getClass());
		private String backgroundJobUuid = "";
		private String asrEngine = SystemConfig.getValue("empty-number-detection-asr", "funasr");
		private myESLEventListener(String robotCallUuid){
		}
		public void setBackgroundJobUuid(String backgroundJobUuid) {
			this.backgroundJobUuid = backgroundJobUuid;
		}
		public void processFsMsg(Map<String, String> headers) {
			String uniqueID = headers.get("Unique-ID");
			String caller = headers.get("Caller-Caller-ID-Number");
			String dest = headers.get("Caller-Destination-Number");
			String eventName = headers.get("Event-Name");
			String hangupCause = headers.get("Hangup-Cause")
					+ ":" + headers.get("variable_proto_specific_hangup_cause");

			long eventTime = Long.parseLong(headers.get("Event-Date-Timestamp")) / 1000L;
			long timeDelay = System.currentTimeMillis() - eventTime;
			log.info("{} The time elapsed from {} event occurrence to message processing is: {} ms", getTraceId(), eventName, timeDelay);

			if (EventNames.CHANNEL_HANGUP.equalsIgnoreCase(eventName)) {
				log.info("{} CHANNEL_HANGUP, uuid: {}", getTraceId(), uniqueID);
				phoneInfo.setHangup(true);
				releaseThreadNum();
				if (phoneInfo.getTransferred()) {
					log.info("{} The call has been transferred. To avoid conflicts in call status recording, this module will no longer log call records. ",
							getTraceId());
					return;
				}

				log.info("{} hangup reason: {}", getTraceId(), hangupCause);
				if (phoneInfo.getAnsweredTime() > 0L) {
					long callDuring = eventTime - phoneInfo.getAnsweredTime();
					phoneInfo.setValidTimeLen((int) (callDuring));
				}
				if (phoneInfo.getConnectedTime() > 0L) {
					long callDuring = eventTime - phoneInfo.getConnectedTime();
					phoneInfo.setTimeLen((int) (callDuring));
				} else {
					log.info("{} try to save emptyNumberDetectionText.", getTraceId());
					setRecognitionText();
					saveEmptyNumberDetection(emptyNumberDetectionText.toString());
				}

				if (phoneInfo.getAnsweredTime() <= 0L) {
					//sip line errors
					boolean hitLineError = hangupCause.contains("404") ||
							hangupCause.contains("503") ||
							hangupCause.contains("480") ||
							hangupCause.contains("408");
					if (hitLineError) {
						phoneInfo.setCallstatus(7);
						log.info("{} Call terminated due to abnormal line conditions.", getTraceId());
					} else {
						phoneInfo.setCallstatus(30);
					}
				}

				if (emptyNumberDetectionCode > 0) {
					phoneInfo.setCallstatus(emptyNumberDetectionCode);
				}

				phoneInfo.setCallEndTime(System.currentTimeMillis());
				phoneInfo.setHangupCause(hangupCause);
				ScheduledScanTask.addToSQLQueue(phoneInfo);

				// push cdr
				CdrDetail cdrRecord = new CdrDetail();
				cdrRecord.setUuid(phoneInfo.getUuid());
				cdrRecord.setCdrType("outbound");
				cdrRecord.setCdrBody(JSON.toJSONString(phoneInfo));
				CdrPush.addCdrToQueue(cdrRecord);
			} else if (EventNames.CHANNEL_PROGRESS_MEDIA.equalsIgnoreCase(eventName)) {
				log.info("{} Ringing event received: {}", getTraceId(), eventName);
				// empty-number-detection-enabled
				if(Boolean.parseBoolean(SystemConfig.getValue("empty-number-detection-enabled", "false"))){
					String startCmd = String.format("start_%s_asr",asrEngine);
					log.info("{} try to start empty-number-detection, startCmd={}", getTraceId(), asrEngine);
					EslConnectionUtil.sendExecuteCommand(startCmd,  "hello", uniqueID);
				}
				if(Boolean.parseBoolean(SystemConfig.getValue("start-write-recordings-on-ringing", "false"))){
                    String wavFile = getWavFilePath();
				    log.info("{} Start to write recordings file {}.", callUuid, wavFile);
                    phoneInfo.setWavfile(wavFile);
					startWriteRecordings(wavFile);
				}
			}else  if (EventNames.CUSTOM.equalsIgnoreCase(eventName)) {
				if(phoneInfo.getConnectedTime() > 1){
					return;
				}
				String eventSubClass = headers.get("Event-Subclass");
				String asrEventDetail = headers.get("ASR-Event-Detail");
				String text = headers.get("Detect-Speech-Result");
				if(StringUtils.isEmpty(text)){
					return;
				}
				if("AsrEvent".equalsIgnoreCase(eventSubClass) &&
				   "Vad".equalsIgnoreCase(asrEventDetail)
				){
					emptyNumberDetectionText.append(text);
					log.info("{} recv emptyNumberDetection result: {}.", getTraceId(), text);
				}
			}
			else if(EventNames.CHANNEL_PARK.equalsIgnoreCase(eventName)){
				parkSemaphore.release();
			} else if(EventNames.PLAYBACK_STOP.equalsIgnoreCase(eventName)){
				if (batchEntity.getTaskType() == CallConfig.CALL_TYPE_VOICE_CALL_NOTIFICATION) {
					if(voiceCallPlayBackCounter < batchEntity.getPlayTimes()) {
						voiceCallPlayBackCounter++;
						playbackWavFile();
					}else{
						endCall("Voice playback limit reached.");
					}
				}
			}
			else if (EventNames.CHANNEL_ANSWER.equalsIgnoreCase(eventName)) {
				emptyNumberDetectionText = new StringBuilder();
				long waitParkSemStartTime = System.currentTimeMillis();
				// Only execute the following logic after confirming the CHANNEL_PARK event is received;
				// otherwise, parking will result in no sound during robot playback.
				try {
					parkSemaphore.tryAcquire(1200L, TimeUnit.MILLISECONDS);
				}catch (Throwable e){
				}

				log.info("{}  Waiting for parkSemaphore took {} ms.",
						getTraceId(),
						System.currentTimeMillis() - waitParkSemStartTime
				);

				if(Boolean.parseBoolean(SystemConfig.getValue("empty-number-detection-enabled", "false"))){
					String stopCmd = String.format("start_%s_asr",asrEngine);
                    log.info("{} try to stop empty-number-detection, stopCmd={}", getTraceId(), asrEngine);
					EslConnectionUtil.sendExecuteCommand(stopCmd, "", uniqueID);
				}

				long now = System.currentTimeMillis();
				phoneInfo.setConnectedTime(now);
				log.info(getTraceId() + " Telephone has been answered, caller: {}, dest: {}, uuid: {}", caller, dest, uniqueID);
				phoneInfo.setTaskInfo(batchEntity);

				try {
                    String wavFile = getWavFilePath();
					phoneInfo.setWavfile(wavFile);
					phoneInfo.setCallstatus(4);
					ScheduledScanTask.addToSQLQueue(phoneInfo);

					startWriteRecordings(wavFile);

					String groupId = "0";
					if("acd".equalsIgnoreCase(batchEntity.getAiTransferType())){
						groupId = batchEntity.getAiTransferData();
					}
					InboundDetail inboundDetail = new InboundDetail(
							callUuid,
							phoneInfo.getTelephone(),
							phoneInfo.getTelephone(),
							System.currentTimeMillis(),
							callUuid,
							wavFile,
							groupId,
							"",
							phoneInfo
					);
					EslConnectionUtil.sendExecuteCommand(
							"set",
							"hangup_after_bridge=false",
							 callUuid,
							 EslConnectionUtil.getDefaultEslConnectionPool()
					);

					if(batchEntity.getTaskType() == CallConfig.CALL_TYPE_PURE_MANUAL_CALL) {
						// transfer to a a human agent
						CallHandler callHandler = new CallHandler(inboundDetail);
						phoneInfo.setAcdQueueTime(System.currentTimeMillis());
						if (InboundGroupHandler.addCallToQueue(callHandler, batchEntity.getGroupId())) {
							log.info("{} successfully add outbound call to acd queue.", inboundDetail.getUuid());
						}
					}else if (batchEntity.getTaskType() == CallConfig.CALL_TYPE_PURE_AI_OUTBOUND_CALL) {
						// transfer to robot
						RobotChat robotChat = new RobotChat(inboundDetail, batchMonitor.getLlmAccount());
						phoneInfo.setTransferred(true);
						if (!robotChat.getHangup()) {
							robotConversationTaskThreadPool.execute(new Runnable() {
								@Override
								public void run() {
									robotChat.startProcess(callUuid);
								}
							});
						}
					}else  if (batchEntity.getTaskType() == CallConfig.CALL_TYPE_VOICE_CALL_NOTIFICATION) {
						phoneInfo.setAnsweredTime(System.currentTimeMillis());
						phoneInfo.setCallstatus(6);
						voiceCallPlayBackCounter ++;
						playbackWavFile();
					}else if(batchEntity.getTaskType() == CallConfig.CALL_TYPE_IVR_VOICE){
						phoneInfo.setCallstatus(6);
						phoneInfo.setAnsweredTime(System.currentTimeMillis());
						ScheduledScanTask.addToSQLQueue(phoneInfo);

						inboundDetail.setExtnum("ivr");
						inboundDetail.setOpnum("ivr");
						inboundDetail.setAnsweredTime(System.currentTimeMillis());

						String ivrPlanId = batchEntity.getIvrId();
						if(!StringUtils.isEmpty(ivrPlanId)) {
							robotConversationTaskThreadPool.execute(new Runnable() {
								@Override
								public void run() {
                                    log.info("{} start startIvrSession for call session. ", getTraceId());
									AppContextProvider.getBean(IvrEngine.class).startIvrSession(inboundDetail, ivrPlanId);
								}
							});
						}else{
							log.info("{} cant not startIvrSession for call session, ivrPlanId is null or empty. ", getTraceId());
						}
					}
				} catch (Exception e) {
					log.error("{} The call will be forcibly terminated due to a failed transfer to the robot. {} {}", getTraceId(),
							e.toString(),
                            CommonUtils.getStackTraceString(e.getStackTrace())
					);
					endCall("Failed-transfer-to-robot");
					phoneInfo.setCallstatus(5);
					ScheduledScanTask.addToSQLQueue(phoneInfo);
				}
			}
		}

		private Semaphore parkSemaphore = new Semaphore(0);

		@Override
		public void eventReceived(String addr, EslEvent event) {
			fsMsgThreadPool.execute(new Runnable() {
				@Override
				public void run() {
					try {
						processFsMsg(event.getEventHeaders());
					}catch (Exception e){
						log.error("{} process freeSWITCH esl msg error : {}  {}, msg: {}",
								getTraceId(),
								e.toString(),
								CommonUtils.getStackTraceString(e.getStackTrace()),
								event.toString()
						);
					}
				}
			});
		}


		@Override
		public void backgroundJobResultReceived(String addr, EslEvent event) {
			eslConnectionPool.getDefaultEslConn().removeListener(this.backgroundJobUuid);
			String eslStr = event.toString();
			log.info("{} backgroundJobResultReceived : {}" , getTraceId(), eslStr);
			if (!eslStr.contains("OK")) {
				log.info("{} single call failed, details：{}", getTraceId(), event.toString());
				releaseThreadNum();
			}
		}

		@Override
		public String context() {
			return CallTask.class.getName();
		}
	};

	private void playbackWavFile(){
		EslConnectionUtil.sendExecuteCommand(
				"playback",
				voiceCallNotificationWavFilePath,
				callUuid,
				EslConnectionUtil.getDefaultEslConnectionPool()
		);
	}


	private myESLEventListener eslListener=null;;
	private volatile String callUuid = "";
	private volatile String voiceCallNotificationWavFilePath = "";
	private volatile int voiceCallPlayBackCounter = 0;
	private  String traceId = "";
	private String getTraceId(){
		return traceId;
	}

	private void setTraceId(String uuid){
		traceId = batchEntity.getBatchName() + ":" + uuid + ":";
	}

	protected EslConnectionPool eslConnectionPool;

	/**
	 * Generate hang-up event data for exceptional situations.
	 * @return
	 */
	protected   Map<String, String> generateHangupEvent(String hangupClause){
		Map<String, String> eslHeaders = new HashMap<>(5);
		eslHeaders.put("Event-Name", EventNames.CHANNEL_HANGUP);
		eslHeaders.put("Callee", this.phoneInfo.getTelephone());
		eslHeaders.put("Unique-ID", this.callUuid);
		eslHeaders.put("Hangup-Cause", hangupClause);
		eslHeaders.put("Event-Date-Timestamp", String.valueOf(CurrentTimeMillisClock.now() * 1000L));
		return eslHeaders;
	}





	private void doCall() {
		this.callUuid = UuidGenerator.GetOneUuid();
		setTraceId(this.callUuid);

		if(batchEntity.getTaskType() == CallConfig.CALL_TYPE_VOICE_CALL_NOTIFICATION){
			String wavSaveFile = String.format("%s/%s/%s_%s.wav",
					"voice_call/tts",
					DateUtils.format(new Date(System.currentTimeMillis()), "yyyy/MM/dd/HH"),
					phoneInfo.getTelephone(),
					callUuid
			);
			voiceCallNotificationWavFilePath = String.format("%s%s",
					SystemConfig.getValue("recording_path", "/home/Records/"),
					wavSaveFile
			);
			boolean success = TtsUtil.synthesizeSpeech(
					phoneInfo.getTtsText(),
					voiceCallNotificationWavFilePath,
					batchEntity.getVoiceCode(),
					batchEntity.getVoiceSource()
			);
			if(!success){
				log.info("{} {} TTS synthesis failed; voice call task will not proceed..", this.callUuid, phoneInfo.getTelephone());
				releaseThreadNum();
				return;
			}
		}

        long startTime = System.currentTimeMillis();
        long timeSpent = startTime - taskSubmitTime;
        log.info("{} This outbound task took {} ms from submission to execution.", this.callUuid, timeSpent);

		if(batchEntity.getIfcall() == 0) {
			releaseThreadNum();
			return;
		}

		String callerName = CommonUtils.getCallerNumberRandomly(this.batchEntity.getCaller());
		log.info("{} Try send call request to freeSwitch Server {} , callerName={} ...",  getTraceId(),  this.phoneInfo.getTelephone(), callerName);
		this.eslConnectionPool = EslConnectionUtil.getDefaultEslConnectionPool();
		phoneInfo.setUuid(this.callUuid);
		// 设置群呼的网关
		String gateway = this.batchEntity.getGwName();
		if (StringUtils.isEmpty(gateway)) {
			log.error(getTraceId() + " Outbound gateway is not specified!");
			releaseThreadNum();
			return;
		}

		callTaskList.put(this.callUuid, this);

		StringBuilder callPrefix = new StringBuilder();
		callPrefix.append(String.format(
				"hangup_after_bridge=true,origination_uuid=%s,absolute_codec_string=pcma,ignore_early_media=false,",
				callUuid
		));
		callPrefix.append(
				"send_silence_when_idle=-1,record_waste_resources=true,record_sample_rate=8000,RECORD_STEREO=false,auto_batchcall_flag=1,"
		);

		String caller = callerName;
		String callee = StringUtils.trim(this.phoneInfo.getTelephone());

		callPrefix.append(String.format(
				"origination_caller_id_number=%s,origination_caller_id_name=%s,effective_caller_id_number=%s,effective_caller_id_name=%s",
				caller,
				caller,
				caller,
				caller
		));

		String extraParams = SystemConfig.getValue("outbound-call-extra-params-for-profile-"+ batchEntity.getProfileName() , "");
		String extraParamsFinal =  extraParams.length() == 0 ? "" :   "," + extraParams ;

		String callParameter = "";
		if(batchEntity.getRegister() == 0) {
			callParameter = String.format("{%s%s}sofia/%s/%s%s@%s  &park()",
					callPrefix.toString(),
					extraParamsFinal,
					batchEntity.getProfileName(),
					batchEntity.getCalleePrefix(),
					callee,
					batchEntity.getGwAddr()
			);
		}else if(batchEntity.getRegister() == 1) {
			callParameter = String.format("{%s}sofia/gateway/%s/%s%s  &park()",
					callPrefix.toString(),
					gateway,
					batchEntity.getCalleePrefix(),
					callee
			);
		}else if(batchEntity.getRegister() == 2) {
			String authUsername = batchEntity.getAuthUsername();
			String dynamicGateway = CommonUtils.getDynamicGatewayAddr(authUsername, getTraceId());
			if(StringUtils.isEmpty(dynamicGateway)){
				log.error("{} Failed to parse dynamic gateway address. ", getTraceId());
				releaseThreadNum();
				return;
			}
			log.info("{} successfully get dynamic gateway address : {}", getTraceId(), dynamicGateway);
			// for dynamic gateway, we must use internal profile
			callParameter = String.format("{%s%s}sofia/internal/%s%s@%s  &park()",
					callPrefix.toString(),
					extraParamsFinal,
					batchEntity.getCalleePrefix(),
					callee,
					dynamicGateway
			);
		}

		phoneInfo.setCalloutTime(System.currentTimeMillis());
		phoneInfo.setCallstatus(2);
		ScheduledScanTask.addToSQLQueue(phoneInfo);
		phoneInfo.setCallcount(phoneInfo.getCallcount() + 1);

		eslListener = new myESLEventListener(callUuid);
		this.eslConnectionPool.getDefaultEslConn().addListener(this.callUuid + UuidKeys.BATCH_CALL,  eslListener);
		log.info("{} Call Parameters: originate {}", getTraceId(),  callParameter);
		String response = EslConnectionUtil.sendAsyncApiCommand("originate", callParameter, this.eslConnectionPool);
		log.info("{} fs bgApi originate response: {}", getTraceId(),  response);
		if(!StringUtils.isEmpty(response)){
			String jobId = response.trim();
			if(jobId.length() == 36) {
				this.eslConnectionPool.getDefaultEslConn().addListener(jobId, eslListener);
				eslListener.setBackgroundJobUuid(jobId);
				log.info("{} Subscribed to subsequent events for Job-ID={}", getTraceId(),  response);
			}else{
				log.error("{} fs bgApi originate response error: {}", getTraceId(),  response);
			}
		}
        timeSpent = System.currentTimeMillis() -  startTime;
        log.info("{} This outbound task execution took {} ms.", this.callUuid, timeSpent);
	}
}
