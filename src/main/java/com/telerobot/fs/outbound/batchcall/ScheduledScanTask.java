package com.telerobot.fs.outbound.batchcall;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.entity.dao.CallTaskEntity;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import com.telerobot.fs.outbound.CallConfig;
import com.telerobot.fs.service.CallTaskService;
import com.telerobot.fs.service.PhoneService;
import com.telerobot.fs.utils.*;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

@Component
@DependsOn({"appContextProvider"})
public class ScheduledScanTask implements ApplicationListener<ApplicationReadyEvent> {
	private static final Logger log = LoggerFactory.getLogger(ScheduledScanTask.class);

	@Autowired
    private  CallTaskService batchService;
	@Autowired
	private  PhoneService phoneService;

	private static final Object ADD_SQL_QUEUE_LOCKER = new Object();
	private static  List<CustmInfoEntity> phoneNumList = new ArrayList<>(2000);

	public static void addToSQLQueue(CustmInfoEntity phone) {
		synchronized (ADD_SQL_QUEUE_LOCKER) {
			if (!phoneNumList.contains(phone)) {
				phoneNumList.add(phone);
			}
		}
	}

	 
	private  void ProcessSQLQueue(){
		while(true){
			// 不同任务的SQL保存的字段值不同，避免相互影响;
			processSQLQueue(phoneNumList, 1);
			ThreadUtil.sleep(12000);
		}
	}

	private static final int MAX_RETRY = 3;
	private   void processSQLQueue(List<CustmInfoEntity> phoneList, int taskType) {
		if (phoneList.size() > 0) {
			List<CustmInfoEntity> _phoneNumList = new ArrayList<CustmInfoEntity>(10000);
			synchronized (ADD_SQL_QUEUE_LOCKER) {
				int arrayLen = phoneList.size();
				for (int i = arrayLen - 1; i >= 0; i--) {
					CustmInfoEntity phone = phoneList.get(i);
					if (!_phoneNumList.contains(phone)) {
						_phoneNumList.add(phone);
					}
				}
				phoneList.clear();
			}

			boolean updateSuccess = false;
            int counter = 0;
			while (!updateSuccess && counter <= MAX_RETRY) {
				try {
					counter++;
					long statTime = System.currentTimeMillis();
					if (taskType == 1) {
						phoneService.batchUpdatePhone(_phoneNumList);
					}

					updateSuccess = true;
					log.info("execute batchUpdatePhone to save outbound status, time-consuming = {} ms， affect rows = {}.",
							System.currentTimeMillis() - statTime,
							_phoneNumList.size()
					);
					_phoneNumList.clear();
				} catch (Throwable e) {
					int sleepMills = RandomUtils.getRandomByRange(5000, 12000);
					log.error("batchUpdatePhone error, database error, retry after {} ms.  ex={}, json={} ",
							sleepMills,
							e.toString(),
							JSON.toJSONString(_phoneNumList)
					);
					ThreadUtil.sleep(sleepMills);
				}
			}

		}
	}

	private static ArrayBlockingQueue<String> callStatInfoList = new ArrayBlockingQueue<>(400);

	/**
	 *  获取外呼任务的统计信息
	 * @return
	 */
	public  static  String getCallStatInfoList() {
		StringBuilder stringBuilder = new StringBuilder();
		for (String item : callStatInfoList) {
			stringBuilder.append(item).append("\n");
		}
		return stringBuilder.toString();
	}

    /**
     * Scan the database regularly to obtain outbound calling tasks
     ***/
    public  void scanTaskFromDb()  {
		ThreadUtil.sleep(9000);
		log.info("AI outbound program, scanTaskFromDb thread started...");
		String callNodeNo = AppContextProvider.getEnvConfig("app-config.call-node-no");
		if(StringUtils.isNullOrEmpty(callNodeNo)){
		    log.error("The outbound call node number of the current server has not been set, program is exiting.");
		    return;
        }
		ThreadPoolExecutor taskManagerThreadPool = ThreadPoolCreator.create(
				20,
				"batchMonitor",
				24,
				20 *2
		);
    	List<CallTaskEntity> taskList = null;
    	while(true){
            taskList = batchService.getBatchList(callNodeNo);
            batchService.setBatchTaskStatus(taskList);
			for (int i=0; i<= taskList.size() - 1; i++)
            { 
				CallTaskEntity task = taskList.get(i);
				log.info("try to submit outbound task {} , batchId={}.", task.getBatchName(), task.getBatchId());
				taskManagerThreadPool.execute(new BatchTaskManager(task));
            }
			if(taskList.size() == 0) {
				log.info("There are no new outbound tasks at present.");
			}
			String statInfo = String.format("Statistics, max-outbound-limitation: %d, line used: %d. Total call task batches: %d",
					CallConfig.maxCallConcurrency,
					CallConfig.getMaxLineNumber_Used(),
					BatchTaskManager.getAllTaskCount()
			);
			log.info(statInfo);
			if(callStatInfoList.size() > 327){
				callStatInfoList.poll();
			}
			callStatInfoList.offer(DateUtils.formatDateTime(new Date()) + "  " + statInfo);

			ThreadUtil.sleep(11000);
    	}
    }

	@Override
	public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						ProcessSQLQueue();
					}
				}, "processSQLQueue"
		).start();

		new Thread(
				new Runnable() {
					@SneakyThrows
					@Override
					public void run() {
						try {
							scanTaskFromDb();
						}catch (Throwable e){
						}
					}
				}, "processSQLQueue"
		).start();
	}
}