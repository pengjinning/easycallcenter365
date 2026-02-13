package com.telerobot.fs.controller;

import com.telerobot.fs.acd.AcdSqlQueue;
import com.telerobot.fs.acd.CallHandler;
import com.telerobot.fs.acd.InboundGroupHandler;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.config.UuidGenerator;
import com.telerobot.fs.entity.bo.InboundBlack;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.entity.bo.LlmConsumer;
import com.telerobot.fs.entity.dao.LlmAgentAccount;
import com.telerobot.fs.entity.dto.InboundConfig;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.global.BizThreadPoolForEsl;
import com.telerobot.fs.ivr.IvrEngine;
import com.telerobot.fs.robot.LlmThreadManager;
import com.telerobot.fs.robot.RobotChat;
import com.telerobot.fs.service.CallTaskService;
import com.telerobot.fs.service.InboundBlackService;
import com.telerobot.fs.service.InboundDetailService;
import com.telerobot.fs.service.LlmAccountParser;
import com.telerobot.fs.utils.*;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.IEslEventListener;
import link.thingscloud.freeswitch.esl.constant.EventNames;
import link.thingscloud.freeswitch.esl.constant.UuidKeys;
import link.thingscloud.freeswitch.esl.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;


// public.xml config sample:
/**
 *
 *    <extension name="inbound_acd">
 *           <condition field="destination_number"  expression="^(99999easycallcenter365)$" >
 *               <action application="set" data="inherit_codec=true"/>
 *               <action application="set" data="not_save_record_flag=1"/>
 *               <action application="answer" />
 *               <action application="start_dtmf"/>
 *               <action application="export" data="RECORD_STEREO=true"/>
 *               <action application="set" data="RECORD_STEREO=true"/>
 *               <action application="log" data="INFO ${uuid}  caller=${caller_id_number}, callee=$1 " />
 *               <action application="log" data="INFO local_media_port=${local_media_port}, remote_media_port=${remote_media_port}, local_video_port=${local_video_port}, remote_video_port=${remote_video_port}" />
 *               <action application="set" data="record_sample_rate=8000"/>
 *               <action application="set" data="RECORD_STEREO=false"/>
 *               <action application="set" data="continue_on_fail=true"/>
 *               <action application="set" data="hangup_after_bridge=false"/>
 *               <action application="set" data="record-time=${strftime(%Y%m%d%H%M%S)}" />
 *               <action application="set" data="groupId=1"/>
 *               <action application="set" data="send_silence_when_idle=-1"/>
 *               <action application="curl" data="http://127.0.0.1:8870/call-center/inboundProcessor?remote_video_port=${remote_video_port}&amp;local-media-port=${local_media_port}&amp;uuid=${uuid}&amp;caller=${caller_id_number}&amp;callee=$1&amp;load-test-uuid=${uuid}&amp;wav-file=${wav-file}&amp;group-id=${groupId}"/>
 *               <action application="park" />
 *           </condition>
 *       </extension>
 *
 */

@Controller
@Scope("request")
public class InboundCallController {
	private static final Logger logger = LoggerFactory.getLogger(InboundCallController.class);
	private static int inboundCallThreadPoolSize = Integer.parseInt(
			SystemConfig.getValue("max-call-concurrency", "200")
	);
    private  static ThreadPoolExecutor mainThreadPool = ThreadPoolCreator.create(
			inboundCallThreadPoolSize,
            "inbound-call-thread",
			 365*24,
			inboundCallThreadPoolSize * 2
	);

	@RequestMapping("/inboundProcessor")
	@ResponseBody
	public String inboundCall(HttpServletRequest request) throws InstantiationException, IllegalAccessException {
		String clientIP = request.getRemoteAddr();
		String allowedIP = SystemConfig.getValue("call-center-server-ip-addr","");
		if(!"127.0.0.1".equalsIgnoreCase(clientIP) && !allowedIP.equalsIgnoreCase(clientIP)){
			String tips = String.format("Forbidden, only '127.0.0.1' and %s are allowed to visit this api.", allowedIP);
			logger.warn(tips);
			//return  tips;
		}

		final String uuid = request.getParameter("uuid");
		final String caller = request.getParameter("caller").replace("+86", "");
		final String callee = request.getParameter("callee").replace("+86", "");
		final String mediaPort = request.getParameter("local-media-port");
		final String remoteVideoPort = request.getParameter("remote_video_port");
		final String loadTestUuid = request.getParameter("load-test-uuid");

		// 在拨号计划中设置录音路径
		String currentThreadPoolInfo = String.format(
				"Current thread pool info：taskCount: %d, activeCount: %d, completedTask: %d, corePoolSize: %d, ",
				mainThreadPool.getTaskCount(),
				mainThreadPool.getActiveCount(),
				mainThreadPool.getCompletedTaskCount(),
				mainThreadPool.getCorePoolSize()
		);
		logger.info("RECV NEW INBOUND CALL, uuid:{}, caller:{}, mediaPort:{}, recordTime: {}, remoteVideoPort:{}",
				uuid, caller, mediaPort, loadTestUuid, remoteVideoPort);
		logger.info("uuid: {}, currentThreadPoolInfo: {}", uuid, currentThreadPoolInfo);
		int maxPoolSize =  mainThreadPool.getCorePoolSize();
		if(mainThreadPool.getActiveCount() >=  maxPoolSize){
			logger.error("{} System load is too high; please scale up or adjust system parameters!", uuid);
		}

		mainThreadPool.execute(
				new Runnable() {
					@Override
					public void run() {
						logger.info("Processing NEW INBOUND CALL, uuid:{}, caller:{}, recordtime:{}",uuid, caller, mediaPort);

						// 查询黑名单
						InboundBlack inboundBlack = AppContextProvider.getBean(InboundBlackService.class).getInboundBlackByCaller(caller);
                        if(null == inboundBlack) {
							InboundConfig inboundConfig  =
							              AppContextProvider.getBean(InboundDetailService.class).getInboundConfigByCallee(callee);

							if(null == inboundConfig){
								logger.error("{} cant not get inboundConfig for callee {}. ", uuid, callee);
								EslConnectionUtil.sendExecuteCommand(
										"hangup",
										"cant-not-get-inboundConfig.",
										uuid
								);
								return;
							}

                            String mediaFile = genRecordingsFileName(remoteVideoPort, caller, callee);
                            InboundDetail inboundDetail = new InboundDetail(
                                    UuidGenerator.GetOneUuid(),
                                    caller,
                                    callee,
                                    System.currentTimeMillis(),
                                    uuid,
                                    mediaFile,
                                    String.valueOf(0),
                                    remoteVideoPort,
                                    null
                            );
                            AppContextProvider.getBean(InboundDetailService.class).insertInbound(inboundDetail);

							logger.info("{} ServiceType={} for callee {}.",
									uuid,
									inboundConfig.getServiceType(),
									callee
							);

                            //设置bridge后不挂机;
                            EslConnectionUtil.sendExecuteCommand(
                                    "set",
                                    "hangup_after_bridge=false",
                                    uuid
                            );

                            RecordingsUtil.startRecordings(mediaFile, uuid);

							if("ai".equalsIgnoreCase(inboundConfig.getServiceType())) {
								logger.info("{} Try to transfer call to ai for callee {}.",
										uuid,
										callee
								);
								LlmAgentAccount accountJson =  AppContextProvider.getBean(CallTaskService.class)
										.getLlmAgentAccountById(inboundConfig.getLlmAccountId());
								AccountBaseEntity account =  LlmAccountParser.parse(accountJson);
								if(null == account){
									logger.error("{} cant not get llmAccount for callee {}.", uuid, callee);
									EslConnectionUtil.sendExecuteCommand(
											"hangup",
											"cant-not-get-llmAccount.",
											uuid
									);
									return;
								}

								account.voiceSource = inboundConfig.getVoiceSource();
                                account.voiceCode = inboundConfig.getVoiceCode();
                                account.asrProvider = inboundConfig.getAsrProvider();
								account.aiTransferType = inboundConfig.getAiTransferType();
								account.aiTransferData = inboundConfig.getAiTransferData();
								account.ivrId = inboundConfig.getIvrId();
								account.satisfSurveyIvrId = inboundConfig.getSatisfSurveyIvrId();
								logger.info("{} tts config info: voiceSource={}, voiceCode={} for callee {}",
										uuid,
										account.voiceSource,
										account.voiceCode,
										callee
								);

								RobotChat robotChat = new RobotChat(inboundDetail, account);
								if(!robotChat.getHangup()){
									robotChat.startProcess(uuid);
								}
							}else if("acd".equalsIgnoreCase(inboundConfig.getServiceType())) {
								logger.info("{} Try to transfer call to acd queue for callee {}.",
										uuid,
										callee
								);
								CallHandler callHandler = new CallHandler(inboundDetail);
								callHandler.setSatisfSurveyIvrId(inboundConfig.getSatisfSurveyIvrId());
								if (InboundGroupHandler.addCallToQueue(callHandler,inboundConfig.getAiTransferData())) {
									logger.info("{} successfully add call to acd queue.", inboundDetail.getUuid());
								}
							}else if("ivr".equalsIgnoreCase(inboundConfig.getServiceType())) {
								String ivrPlanId =  inboundConfig.getIvrId();
								logger.info("{} Try to start ivr process. ivrId={}.", uuid, ivrPlanId);
								IEslEventListener listener = new IEslEventListener() {
									@Override
									public void eventReceived(String addr, EslEvent event) {
										BizThreadPoolForEsl.submitTask(new Runnable() {
											@Override
											public void run() {
												processEslMsg(addr, event);
											}
										});
									}
									private void processEslMsg(String addr, EslEvent event) {
										Map<String,String> headers = event.getEventHeaders();
										String eventName = headers.get("Event-Name");
										logger.info("{} recv {} event", inboundDetail.getUuid(), eventName);
										if (eventName.equalsIgnoreCase(EventNames.CHANNEL_HANGUP)) {
											inboundDetail.setHangupTime(System.currentTimeMillis());
											long timeLen = System.currentTimeMillis() - inboundDetail.getInboundTime();
											long answeredTimeLen = System.currentTimeMillis() - inboundDetail.getAnsweredTime();
											inboundDetail.setTimeLen(timeLen);
											inboundDetail.setAnsweredTimeLen(answeredTimeLen);
											String hangupCause = headers.get("Hangup-Cause");
											String sipCode = headers.get("variable_proto_specific_hangup_cause");
											CommonUtils.setHangupCauseDetail(
													inboundDetail,
													hangupCause,
													"sip-code=" + sipCode
											);
										}
										AcdSqlQueue.addToSqlQueue(inboundDetail);
									}
									@Override
									public void backgroundJobResultReceived(String addr, EslEvent event) {

									}

									@Override
									public String context() {
										return InboundCallController.class.getName();
									}
								};
								inboundDetail.setAnsweredTime(System.currentTimeMillis());
								EslConnectionUtil.getDefaultEslConnectionPool().getDefaultEslConn().addListener(
										uuid, listener
								);
								inboundDetail.setExtnum("ivr");
								inboundDetail.setOpnum("ivr");
								AppContextProvider.getBean(IvrEngine.class).startIvrSession(inboundDetail, ivrPlanId);
							}

						}else{
                        	logger.warn("{} caller {} hit the black list, reject the inbound call.", uuid, caller);
							EslConnectionUtil.sendExecuteCommand(
									"playback",
									"$${sounds_dir}/ivr/hangup.wav",
									uuid,
									EslConnectionUtil.getDefaultEslConnectionPool()
							);
							ThreadUtil.sleep(2000);
							EslConnectionUtil.sendExecuteCommand(
									"hangup",
									"",
									uuid,
									EslConnectionUtil.getDefaultEslConnectionPool()
							);
						}
					}
				});

		return "success";
	}



	private String genRecordingsFileName(String remoteVideoPort, String caller, String callee){
		String recordFileExtension = SystemConfig.getValue("recordings_extension", "wav");
		if(!StringUtils.isNullOrEmpty(remoteVideoPort)){
			recordFileExtension = "mp4";
		}
		String dateStr = DateUtils.format(new Date(), "yyyy/MM/dd/HH");
		String fileName = caller + "_" + callee + "_" + DateUtils.format(new Date(), "mmss");
        return String.format("%s/%s.%s", dateStr, fileName,  recordFileExtension);
	}

}
