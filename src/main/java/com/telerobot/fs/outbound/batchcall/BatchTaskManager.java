package com.telerobot.fs.outbound.batchcall;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.acd.InboundGroupHandlerList;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.dao.CallTaskEntity;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import com.telerobot.fs.entity.dao.LlmAgentAccount;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.pojo.AgentStatus;
import com.telerobot.fs.outbound.CallConfig;
import com.telerobot.fs.service.CallTaskService;
import com.telerobot.fs.service.LlmAccountParser;
import com.telerobot.fs.service.SysService;
import com.telerobot.fs.utils.*;
import com.telerobot.fs.wshandle.SessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * BatchTaskManager for outbound call tasks
 */
public class BatchTaskManager implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(BatchTaskManager.class);
	private CallTaskService batchTaskService = AppContextProvider.getBean(CallTaskService.class);

    /**
     *  A single, globally shared thread pool is used for outbound tasks.
     */
    private static ThreadPoolExecutor callTaskThreadPool = null;

    private static ThreadPoolExecutor monitorTaskThreadPool = null;

    private final Object currentBatchCurrencyLocker = new Object();


    static {
        int batchTaskThreadNumber = Integer.parseInt(
                AppContextProvider.getEnvConfig("app-config.batch-call.batch-task-thread-number","500")
        );
        int monitorTaskThreadNumber = Integer.parseInt(
                AppContextProvider.getEnvConfig("app-config.batch-call.monitor-task-thread-number", "20")
        );
        if(monitorTaskThreadNumber < 5){
            monitorTaskThreadNumber = 5;
        }
        log.info("Start the global outbound task thread pool: cpu-core-num={}, maximumPoolSize={}",
                Runtime.getRuntime().availableProcessors(),
                batchTaskThreadNumber
        );
        callTaskThreadPool = ThreadPoolCreator.create(
                batchTaskThreadNumber,
                "callTask",
                24,
                batchTaskThreadNumber *2
        );
        monitorTaskThreadPool = ThreadPoolCreator.create(
                monitorTaskThreadNumber,
                "monitorTask",
                24,
                monitorTaskThreadNumber *2
        );
    }

    /**
     *  The number of lines that have been used for the current outbound call task
     */
    private  int threadNumUsed =0;
    private List<SessionEntity> agentList = null;

    public int getThreadNumUsed() {
        return threadNumUsed;
    }

    private void addThreadNumUsed(int addNum) {
        synchronized(currentBatchCurrencyLocker){
            threadNumUsed += addNum;
        }
    }
    public void releaseThreadNumUsed()
    {
        synchronized(currentBatchCurrencyLocker){
            threadNumUsed -= 1;
        }
    }
    private void releaseThreadNumUsed(int num) {
        synchronized (currentBatchCurrencyLocker) {
            threadNumUsed -= num;
        }
    }


    @Override
	public void run() {
        if(needStopTask){
            return;
        }
		log.info(getTraceId() + " start outbound task.");
        phoneNumberContainer = GetPhones();
        if (phoneNumberContainer == null || phoneNumberContainer.size() == 0) {
            log.info(getTraceId() + " no available phone numbers, task stopped.");
            this.finishedFlag = true;
            return;
        }
        doCallOut();
	}
	
	/**
	 * A flag that indicates if there is remaining data in the database.
	 */
    private boolean finishedFlag = false;

    /**
	 * task pause switch.
	 */
    private boolean stopped = false;
    
    private CallTaskEntity currentBatchEntity = null;
    
    /**
	 * phone numbers buffer
	 */     
    private List<CustmInfoEntity> phoneNumberContainer = null;
    
    /**
   	 * id of current task
   	 */
    private int batchId = 0;

    private AccountBaseEntity llmAccount;

    public AccountBaseEntity getLlmAccount(){
        return llmAccount;
    }
    
    private String batchName = "";

    private static final ConcurrentHashMap<String, BatchTaskManager>  taskList = new ConcurrentHashMap<>(20);

    public static int getAllTaskCount(){
        return taskList.size();
    }
    
    public BatchTaskManager() {}

    private volatile long taskStartTimeStamp = 0L;

    private volatile boolean needStopTask = false;

    /**
     * Prevent the repeated startup of the same task
     * @return
     */
    private boolean preventRepeatRunTask() {
        String key = String.valueOf(this.batchId);
        boolean running = (null != taskList.get(key));
        if (!running) {
            synchronized (taskList) {
                running = (null != taskList.get(key));
                if (!running) {
                    taskList.put(key, this);
                }
            }
        }
        return running;
    }

    public BatchTaskManager(CallTaskEntity batchEntity) {
    	this.batchId = batchEntity.getBatchId();
    	this.batchName = batchEntity.getBatchName();
        this.currentBatchEntity = batchEntity;

        if(batchEntity.getTaskType() == CallConfig.CALL_TYPE_PURE_AI_OUTBOUND_CALL ) {
            LlmAgentAccount accountJSON = batchTaskService.getLlmAgentAccountById(this.currentBatchEntity.getLlmAccountId());
            this.llmAccount = LlmAccountParser.parse(accountJSON);
            if(null ==  this.llmAccount){
                String tips = String.format("  %s=%d,  llmAccount not set, stop current task !",  this.batchName, this.batchId);
                log.error(tips);
                needStopTask = true;
                return;
            }
            this.llmAccount.voiceSource = batchEntity.getVoiceSource();
            this.llmAccount.voiceCode = batchEntity.getVoiceCode();
        }

        if(preventRepeatRunTask()){
            String tips = String.format("outbound task is running, %s=%d, Stop the current outbound call request!",  this.batchName, this.batchId);
            log.error(tips);
            needStopTask = true;
            return;
        }

        taskStartTimeStamp = System.currentTimeMillis();

        int affectRow = batchTaskService.resetCallData(batchId);
        log.info("{} Reset the outbound call data status before the task starts，affect rows: {}", getTraceId(), affectRow);
        monitorTaskThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                monitorCallTask();
            }
        });

        if(currentBatchEntity.getTaskType() == CallConfig.CALL_TYPE_PURE_MANUAL_CALL) {
            monitorTaskThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    realTimeCalcCallData();
                }
            });
        }
    }
    
    private void releaseThreadNum()
    {
    	releaseThreadNumUsed();
		CallConfig.releaseLine_Used();
    }

    private List<CustmInfoEntity> GetPhones() {
        List<CustmInfoEntity> resultList = new LinkedList<>();
        int counter = 1;
        int maxTry = 10;
        boolean errorOccurred = false;
        while (counter <= maxTry) {
            try {
                resultList = batchTaskService.GetPhoneNumberFromDb(CallConfig.outbound_phoneNum_buffer, batchId);
                log.info("{} Successfully obtained the outbound call number resources: {} rows.", getTraceId(), resultList.size());
                break;
            }catch (Throwable e){
                errorOccurred = true;
                int sleepMills = RandomUtils.getRandomByRange(5000, 12000);
                log.error("{} Database error occurred when obtaining the outbound call number resources ! Retry after {} mills. {} , details： {}.",
                        getTraceId(),
                        e.toString(),
                        sleepMills,
                        CommonUtils.getStackTraceString(e.getStackTrace())
                );
                ThreadUtil.sleep(sleepMills);
            }
            counter++;
        }
        if(errorOccurred && resultList.size() == 0 ){
            log.error("{} No outbound call data was obtained for {} consecutive times.", getTraceId(), maxTry);
        }
        return resultList;
    }
    
    
    private void sleep(int mills) {
        ThreadUtil.sleep(mills);
    }

    /**
     *  Return the number of people queuing in the manual queue;
     * @return
     */
    private int getCallTransferQueueSize(String groupId){
        return InboundGroupHandlerList.getInstance().getCallHandlerBySkillGroupId(groupId).getQueueSize();
    }

    /**
     * Predict the number of agents currently on a call who will become available.
     * @return
     */
    private int calcFreeAgentNumber(String groupId){
        // 我们的算法：
        // 座席数量 X1 = 预测当前通话中的座席在N秒后的空闲状态  +
        // 座席数量 X2 = Χ当前空闲座席数 - Y未打完*Y接通率
        // 额外的需要考虑： 接通后的电话挂断率;  排队中的电话的放弃率；
        // 预测当前通话中的座席在 N 秒后的空闲状态 =  (平均振铃时长 - 平均通话时长15秒 - 事后处理4秒  - 已用通话时间6秒) ) > 0  ? "空闲"  :  "忙碌"

        int toBeFreeAgentCount = 0 ;
        agentList = AppContextProvider.getBean(SysService.class).getAgentTalkStartTime(groupId);
        // 平均接通耗时;
        double avgRingTimeLen = currentBatchEntity.getAvgRingTimeLen();
        // 平均有效通话时长;
        double avgCallTalkTimeLen = currentBatchEntity.getAvgCallTalkTimeLen();
        double avgFillFormTimeLen = currentBatchEntity.getAvgCallEndProcessTimeLen();
        for (SessionEntity session : agentList) {
            double passedSecs = (System.currentTimeMillis() - session.getStateChangeTime()) / 1000d;
            boolean free = avgRingTimeLen - (avgCallTalkTimeLen - avgFillFormTimeLen  - passedSecs) > 0;
            log.info("预测座席坐席 {} 在 {} 秒后的状态是否空闲 {}，当前座席状态{}， 当前状态已持续时间: {} 秒",
                    session.getOpNum(),
                    avgRingTimeLen,
                    free,
                    AgentStatus.getItemByValue(session.getAgentStatus()).getName(),
                    passedSecs
            );
            if(free){
                toBeFreeAgentCount += 1;
            }
        }
        return toBeFreeAgentCount;
    }

    /**
     *  Retrieve real-time call connection rate data.
     * @return
     */
    private double calcConnRate() {
        return 1 / currentBatchEntity.getRate();
    }


    /**
     * do outbound call
     **/
    private void doCallOut()
    {
    	log.info(getTraceId() + "start outbound call main thread.");
        while (!this.stopped && !finishedFlag) {
            if (!checkWhetherCanCall()) {
                log.info(getTraceId() + " The total number of outbound channels has exceeded the limit; task execution will be temporarily paused.");
                sleep(CallConfig.waitDelayWhenNoAvailableLine);
                continue;
            }

            // 说明： 关于外呼速率和最大并发数控制的问题;
            // 1.  如果是人工外呼，需要外呼速率字段；
            // 2. 如果是纯AI外呼，不需要外呼速率，只需要最大并发数即可。

            // 推荐算法：
            // 预测式外呼的精准度主要依赖两方面：座席的接听能力、被叫电话的接通率。影响这些算法的主要参数如图二所示：
            // 座席通话时长; 振铃时长； 座席事后处理时长； 外呼成功率； 挂断率；
            // 外呼量 =（当前空闲坐席数-正在外呼数*外呼成功率-当前排队个数*用户不放弃比例+N秒后员工挂断空闲个数+当前后处理数*配置系数） / (外呼成功率*用户不放弃比例) * 手工调整系数

            int speed = 0;
            // 每次都从数据库中检索当前任务批次最大线路数
            if (currentBatchEntity.getTaskType() == CallConfig.CALL_TYPE_PURE_MANUAL_CALL) {
                int freeAgentNum = AppContextProvider.getBean(SysService.class).getFreeUserList(currentBatchEntity.getGroupId()).size();
                // 查询当前在线座席人数;

                int agentNum = 0;
                //是否开启了自动预测算法
                boolean autoPredictCallEnabled = Boolean.parseBoolean(SystemConfig.getValue("outbound-enable-prediction-algorithm"));
                if(autoPredictCallEnabled) {
                    //  计算上一次外呼未打完的数量 *  接通率；
                    double connectionRate = currentBatchEntity.getRate();
                    log.info("{}  Number connection rate of current outbound tasks: {}.", getTraceId(), connectionRate);
                    // 计算出未打完的数量;
                    int unFinished = CallTask.calcUnfinishedCall(currentBatchEntity.getBatchId());
                    //int finished = CallTask.calcFinishedCall(currentBatchEntity);
                    log.info("{} Predict the number of unfinished and soon-to-end calls, unFinished={}.", getTraceId(), unFinished);
                    int agentNum1 = freeAgentNum  - (int) (unFinished * connectionRate);
                    int agentNum2 = calcFreeAgentNumber(currentBatchEntity.getGroupId());

                    int queueSize = getCallTransferQueueSize(currentBatchEntity.getGroupId());
                    int queueSizeEx = (int) (queueSize * 0.9d);
                    if (queueSizeEx > 0) {
                        log.info("{} The current number of customers queuing={}. Suppose the proportion of customers not giving up is 0.9, queueSizeEx={}", getTraceId(), queueSize, queueSizeEx);
                    }
                    agentNum = agentNum1 + agentNum2 - queueSizeEx;

                    log.info("{} Predict the number of available seats：agentNum={}, agentNum1 = {} ,  calcFreeAgentNumber={}, queueSizeEx={}",
                            getTraceId(),
                            agentNum,
                            agentNum1,
                            agentNum2,
                            queueSizeEx
                    );
                }else{
                    log.info("{} The predictive outbound algorithm is not enabled at present. outbound-enable-prediction-algorithm = false.", getTraceId());
                    agentNum = freeAgentNum;
                }

                if (agentNum <= 0) {
                    log.info("{} There are no available seats at present, wait {} mills.", getTraceId(), 3000);
                    sleep(3000);
                    continue;
                }

                speed = (int) (agentNum * currentBatchEntity.getRate());
                int available = currentBatchEntity.getThreadNum()  - getThreadNumUsed();
                if (speed > available) {
                    speed = available;
                }

            }else{
                // pure ai outbound call
                speed = currentBatchEntity.getThreadNum() - getThreadNumUsed();
            }

            log.info("{} Calculate available outbound concurrent, speed={}, ThreadNum={}, current task usedLineNum={}, global maxCallConcurrency={}, MaxLineNumber_Used={}",
                    getTraceId(),
                    speed,
                    currentBatchEntity.getThreadNum(),
                    getThreadNumUsed(),
                    CallConfig.maxCallConcurrency,
                    CallConfig.getMaxLineNumber_Used()
            );

            synchronized (LOCKER_GLOBAL) {
                int globalAvailableLine = CallConfig.maxCallConcurrency - CallConfig.getMaxLineNumber_Used();
                if (speed > globalAvailableLine) {
                    speed = globalAvailableLine;
                }
                if (speed > 0) {
                    addThreadNumUsed(speed);
                    CallConfig.addMaxLineNumber_Used(speed);
                }
            }

            if (speed <= 0) {
                int sleepDelay = CallConfig.waitDelayWhenNoAvailableLine;
                log.info(getTraceId() +
                        "No available outbound concurrent. The task is paused for  " + sleepDelay + " seconds.");
                sleep(sleepDelay);
                continue;
            }

            log.info("{} rate:{}. The concurrent number of upcoming outbound calls is : {}.",getTraceId(),  currentBatchEntity.getRate(), speed);

            for (int i = 1; i <= speed; i++) {
                callOut();
                sleep(CallConfig.singleCallDelay);
            }

            log.info("{} sleep {} seconds after originate {} outbound calls. ", getTraceId(), CallConfig.wait_delay_after_a_batch, speed );
            sleep(CallConfig.wait_delay_after_a_batch * 1000 );
        }
        sleep(3000);
        phoneNumberContainer.clear();
        phoneNumberContainer = null;
        log.info("{} The outbound call main thread has been stopped.", getTraceId());
    }

    private final Object getPhoneLocker = new Object();
    private static final Object LOCKER_GLOBAL = new Object();
    
    /**
     *  Retrieve a phone number resource from the buffer.
     **/
    private CustmInfoEntity GetOnePhoneNum() {
        synchronized (getPhoneLocker) {
            CustmInfoEntity oneTelephoneInfo = null;
            if (phoneNumberContainer.size() == 0) {
                phoneNumberContainer = GetPhones();
            }
            if (phoneNumberContainer.size() == 0) {
                return null;
            }
            oneTelephoneInfo = phoneNumberContainer.get(0);
            phoneNumberContainer.remove(0);
            return oneTelephoneInfo;
        }
    }


    /**
     * Check used channel count to evaluate outbound call eligibility.
     **/
    private boolean checkWhetherCanCall()
    {
        int totalUsedNum = CallConfig.getMaxLineNumber_Used();
        int maxLineNum = CallConfig.maxCallConcurrency;
        int currentThreadNum = this.currentBatchEntity.getThreadNum();
        int currentThreadNumUsed = getThreadNumUsed();
        if (totalUsedNum >= maxLineNum)
        {
            log.info("{} The system has reached the maximum outbound concurrency limit: {}, max concurrency is  {}.", getTraceId(), totalUsedNum,  maxLineNum);
            return false;
        }
        if (currentThreadNumUsed >= currentThreadNum)
        {
        	log.info("{}Current task concurrency limit reached: {}  outbound lines in use.", getTraceId(),  currentThreadNum);
            return false;
        }
        return true;
    }

   
    /**
     *  start a single outbound thread
     **/
    private void callOut() {
        CustmInfoEntity phoneNumEntity = GetOnePhoneNum();
        if (phoneNumEntity == null) {
            if (!finishedFlag) {
                finishedFlag = true;
            }
            releaseThreadNum();
            return;
        }
        phoneNumEntity.setBatchId(this.batchId);
        try {
            callTaskThreadPool.execute(new CallTask(this, phoneNumEntity, currentBatchEntity));
        } catch (Exception e) {
            log.error("{} An error occurred while adding the outbound task to the thread pool ! {} {} ",
                    getTraceId(),
                    e.toString(),
                    CommonUtils.getStackTraceString(e.getStackTrace())
            );
        }
    }

    private String tasKTraceId;
    private  String getTraceId(){
        if(StringUtils.isNullOrEmpty(tasKTraceId)){
            tasKTraceId = batchName + "_taskId=" + currentBatchEntity.getBatchId();
        }
        return tasKTraceId;
    }


    /**
     *  Evaluate if the outbound task has exceeded the threshold duration since its start.
     * @return
     */
    private boolean checkTaskStartTimePassed(){
        long waitTimeToCalcCallConnectRate = Long.parseLong(AppContextProvider.getEnvConfig(
                "app-config.ai-call.wait-time-to-calc-call-connect-rate", "7"
        )) * 60L * 1000L;
        long timePassed = System.currentTimeMillis() - taskStartTimeStamp;
        return  (timePassed > waitTimeToCalcCallConnectRate);
    }

    /**
     *  Real-time calculation of the answer rate for the current batch
     *     （实时计算当前批次的接通率;
     *      实时计算每个座席的， 平均通话时长；
     *      实时计算通话转接前，机器人的平均播报时长；
     *      实时计算平均振铃时长；
     *      实时计算通话转接之前的放弃率；）
     */
    public void realTimeCalcCallData() {
        log.info(getTraceId() + " Start relevant parameters and indicators for real-time outbound data computation.");
        int batchId = currentBatchEntity.getBatchId();
        while (!this.stopped && !finishedFlag) {
            sleep(31000);
            // 如果外呼启动时间大于指定的分钟数，则启动自动计算接通率的方式； 否则使用指定的速率;
            if (checkTaskStartTimePassed()) {

                long startTime = System.currentTimeMillis();

                Double connRate = AppContextProvider.getBean(SysService.class).getCallConnectedRateByBatchId(batchId);
                if(null != connRate) {
                    currentBatchEntity.setRate(1 / connRate);
                }

                Double avgRingTimeLen = AppContextProvider.getBean(SysService.class).getAvgRingTimeLenByBatchId(batchId);
                if(null != avgRingTimeLen){
                    currentBatchEntity.setAvgRingTimeLen(avgRingTimeLen);
                }

                // TODO 这里最好的选择是计算每个员工的平均数据；  在通话开始10分钟后，计算每个员工的平均值；用以替代外呼任务批次的数据;
                Double avgTalkTimeLen =  AppContextProvider.getBean(SysService.class).getAvgTalkTimeLenByUserId(batchId, "");
                if(null != avgTalkTimeLen){
                    if(avgTalkTimeLen < 5.0){
                        avgTalkTimeLen = 5.0;
                    }
                    currentBatchEntity.setAvgCallTalkTimeLen(avgTalkTimeLen);
                }

                log.info("{} Real-time outbound metrics calculation completed, spent {} ms, avgRingTimeLen={}, avgCallTalkTimeLen={}, " +
                                " real-time connection rate={} ",
                        getTraceId(),
                        System.currentTimeMillis() -  startTime,
                        avgRingTimeLen,
                        avgTalkTimeLen,
                        connRate
                );

            }
        }
    }

    /**
	 * Periodically read the database to detect the status of task batches. If the user has paused a batch, stop that batch.
     *  At the same time, regularly monitor the task batch status. If the user updates the task information,
     *  refresh the in-memory parameters to ensure immediate effect of the changes.
	 */
    public void monitorCallTask()
    {
    	log.info(getTraceId() + "start monitorCallTask.");
        while (!this.stopped && !finishedFlag)
        {
	        sleep(11000);

	        // 更新本批次的实时外呼并发数到redis;
            //AppContextProvider.getBean(RedisUtil.class).set("cc_call_task_concurrency_" + currentBatchEntity.getBatchId(), this.getThreadNumUsed());

        	CallTaskEntity ret = batchTaskService.getTaskInfoById(batchId);
            if(ret == null) {
            	log.error(getTraceId() + " Task batch status retrieval failed — outbound task will be terminated.");
            	this.stopped = true;
            	currentBatchEntity.setIfcall(0);
            	//通知SingleCallTask子线程停止任务
            	break;
            }
            if(!checkTaskStartTimePassed()) {
                currentBatchEntity.setRate(ret.getRate());
            }
            currentBatchEntity.setIfcall(ret.getIfcall());
            currentBatchEntity.setGroupId(ret.getGroupId());
            currentBatchEntity.setThreadNum(ret.getThreadNum());

            if (ret.getIfcall() == 0)  {
            	log.info(getTraceId() + " Outbound calling task is currently paused by user.");
                this.stopped = true;
                currentBatchEntity.setIfcall(0);
                // Signal the CallTask sub-thread to terminate the task.
                break;
            }
        }

        int waitTimeOut = 1 * 50 * 1000;
        int waitUnit = 5000;
        int totalLooper = waitTimeOut / waitUnit;
        int looper = 0;
        //设置50秒的超时时间
        while (getThreadNumUsed() != 0 && looper <= totalLooper){
            looper ++;
            log.info(getTraceId() + " Awaiting completion of all calls...");
            ThreadUtil.sleep(waitUnit);
        }
        if(getThreadNumUsed() != 0){
            log.info(getTraceId() + " {} worker threads have not yet completed. " + getThreadNumUsed() );
            synchronized (currentBatchEntity.getBatchName().intern()) {
                int unfinishedTaskNum = getThreadNumUsed();
                releaseThreadNumUsed(unfinishedTaskNum);
            }
        }else {
            log.info(getTraceId() + " All outbound calls have ended.");
        }
        if(finishedFlag){
            batchTaskService.UpdateTaskStatusToStop(batchId, System.currentTimeMillis());
            log.info(getTraceId()  + " Modify the stop time of the outbound task to the current timestamp.");
        }
        taskList.remove(String.valueOf(this.batchId));
        log.info("{} monitorCallTask thead has been terminated. ", getTraceId());
    }
}
