package com.telerobot.fs.acd;

import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.pojo.AgentStatus;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.ThreadPoolCreator;
import com.telerobot.fs.utils.ThreadUtil;
import com.telerobot.fs.wshandle.*;
import com.telerobot.fs.wshandle.impl.AgentCc;
import com.telerobot.fs.wshandle.impl.InboundMonitorDataPull;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Call queue handler class — a handler object is created for each group
 *  to process all inbound calls associated with that group's ID.
 * 
 ****/
public class InboundGroupHandler {

    private final static Logger log = LoggerFactory.getLogger(InboundGroupHandler.class);

    /**
	 * store call sessions for current groupId
	 ***/
	private ArrayBlockingQueue<CallHandler> inboundCallQueue = new ArrayBlockingQueue<>(600, false);

	private Semaphore semaphore = new Semaphore(0);

	public int getQueueSize(){
        return inboundCallQueue.size();
    }

	private static int inboundGroupHandlerThreadPoolSize = Integer.parseInt(
			SystemConfig.getValue("inbound-group-handler-thread-pool-size", "20")
	);
	private  static ThreadPoolExecutor assignCallThreadPool = ThreadPoolCreator.create(
			inboundGroupHandlerThreadPoolSize,
			"inbound-call-group-assign-thread",
			365*24,
			inboundGroupHandlerThreadPoolSize * 2
	);

	/**
	 * Calculate and retrieve the number of people ahead of the current customer in the queue
	 * @param current
	 * @return
	 */
	public int getQueuePosition(CallHandler current) {
		long currentNumber = current.getQueueNo();
		int count = 0;
		for (CallHandler ch : inboundCallQueue) {
			if (ch.getQueueNo() < currentNumber) {
				count++;
			}
		}
		return count;
	}

    /**
	 * business groupId
	 ***/
	private String groupId = null;

	public String getGroupId() {
		return groupId;
	}


	public InboundGroupHandler(String _groupId) {
		this.groupId = _groupId.trim();
        activeCallHandler();
	}

	private boolean disposed = false;

    /**
     * add a call session to queue
     * @param callDetailInfo
     * @return
     */
	public boolean addCallToQueue(CallHandler callDetailInfo) {
        if (callDetailInfo == null) {
            return false;
        }
        try {
			if(!inboundCallQueue.contains(callDetailInfo)) {
				this.inboundCallQueue.put(callDetailInfo);
			}else{
				String errorTips = "error， repeated call session, skip it. addCallToQueue(CallHandler)";
				log.error("{} {}",  callDetailInfo.getTraceId(), errorTips);
			}
            semaphore.release();
        } catch (Throwable e) {
			log.error("{} addCallToQueue error: {} ", callDetailInfo.getTraceId(), e.toString());
        	return false;
        }
        return true;
    }

	/**
	 *  add a call session to a queue of specific groupId
	 * @param callDetailInfo
	 * @return
	 */
	public static boolean addCallToQueue(CallHandler callDetailInfo, String skillGroupId) {
		InboundGroupHandler groupHandler = InboundGroupHandlerList.getInstance().
				getCallHandlerBySkillGroupId(skillGroupId.trim());
		assert null != groupHandler;
		return groupHandler.addCallToQueue(callDetailInfo);
	}



	private void assignCall() {
		log.info("assignCall thread started groupId={}.", this.groupId);
		while (!disposed) {
			try {
                semaphore.acquire();
				// the benefits of using peek function, keep original data,
				// to get the exact number of call waiting in acd queue
				CallHandler call = this.inboundCallQueue.peek();
				if (call == null) {
					ThreadUtil.sleep(10);
					continue;
				}

				log.info("{} try to assign call, groupId={}.", call.getTraceId(), groupId);

				int printerCounter = 0;
				// get free agent and assign call
				while (!disposed && !call.getInboundDetail().getHangup()) {
					List<SessionEntity> agentList = AppContextProvider.getBean(SysService.class).getFreeUserList(groupId);
					int agentNum =  agentList.size();
				    if(agentNum > 0){

						SessionEntity agentFound = null;
						for (SessionEntity agent : agentList) {
							MessageHandlerEngine engine = MessageHandlerEngineList.
									getInstance().getMsgHandlerEngine(agent.getSessionId());
							if (engine != null) {
								SessionEntity session = engine.getSessionInfo();
								if (session != null && session.tryLock()) {
									log.info("{} An free agent is successfully obtained. opnum={}, extnum={}, sessionId={}, lastHangupTime={}, agent groupId={}, groupId of call session={}.",
											call.getTraceId(), agent.getOpNum(), agent.getExtNum(), agent.getSessionId(),
											agent.getLastHangupTime(), agent.getGroupId(), call.getInboundDetail().getGroupId()
									);
									log.info("{} Set the busy lock status of an agent. uerId={}, extNum={}, sessionId={}.",
											call.getTraceId(),
											agent.getOpNum(),
											agent.getExtNum(),
											agent.getSessionId()
									);
									AgentCc.setAgentStatus(AgentStatus.lockStatus, agent.getOpNum());
									agentFound = agent;
									break;
								}
							}
						}

                        // 转接到当前座席的分机
						if(null != agentFound) {
							call.tryToBridgeCall(agentFound);
							break;
						}else{
							ThreadUtil.sleep(10);
						}
                    } else {
                        if(printerCounter == 5000) {
							log.info("{} No free agent is available. Continue to wait...", call.getTraceId());
							log.info("{} Current counter of queued calls:{}", getGroupId(), inboundCallQueue.size());
							printerCounter = 0;
						}
						printerCounter += 10;
						ThreadUtil.sleep(10);
					}
				}
				log.info("{} remove call from acd queue, caller={}", call.getTraceId(), call.getInboundDetail().getCaller());
			    this.inboundCallQueue.poll();

			    if(call.getCallMonitorInfo() != null){
					InboundMonitorDataPull.remove(call.getCallMonitorInfo());
				}

				log.info("{} current calls count in acd queue:{}", getGroupId(), inboundCallQueue.size());
			} catch (Throwable ex) {
				log.error("assignCall thread exception occurred, details: {}, {} ", ex.toString(), CommonUtils.getStackTraceString(ex.getStackTrace()));
			}
		}
		log.info("assignCall thread stopped. groupId is ：" + this.groupId);
		disposed = true;
	}

	public synchronized void dispose() {
		if (!this.disposed) {
			disposed = true;
			for(CallHandler call : inboundCallQueue) { call = null; }
			this.inboundCallQueue.clear();
			this.inboundCallQueue = null;
		}
	}

    private void activeCallHandler() {
		assignCallThreadPool.execute(
				new Runnable() {
					@Override
					public void run() {
						assignCall();
					}
				}
		);

		assignCallThreadPool.execute(
				new Runnable() {
					@Override
					public void run() {
						sendAcdQueueInfoToGroup();
					}
				}
		);
    }

    private volatile boolean sendQueueEmptyInfo = true;

	/**
	 * 把当前业务组排队的实时人数发送给所有客户端
	 */
	private void sendAcdQueueInfoToGroup() {
		while (true) {
			try {
				int queueNumber = inboundCallQueue.size();
				JSONObject jsonObject = new JSONObject();
				jsonObject.put("queue_number", queueNumber);
				MessageResponse msg = new MessageResponse(
						RespStatus.ACD_GROUP_QUEUE_NUMBER, "Real-time queue number.", jsonObject
				);

				if(queueNumber > 0) {
					sendQueueEmptyInfo = false;
					MessageHandlerEngineList.doBroadcast(msg, groupId);
				}else{
					if(!sendQueueEmptyInfo){
						sendQueueEmptyInfo = true;
						MessageHandlerEngineList.doBroadcast(msg, groupId);
					}
				}
			} catch (Throwable e) {
				log.error("sendAcdQueueInfoToGroup error: {} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
			}
			ThreadUtil.sleep(2000);
		}
	}

}
