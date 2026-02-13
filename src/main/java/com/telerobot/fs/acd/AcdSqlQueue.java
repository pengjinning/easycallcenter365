package com.telerobot.fs.acd;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.AppContextProvider;
import com.telerobot.fs.entity.bo.InboundDetail;
import com.telerobot.fs.outbound.batchcall.ScheduledScanTask;
import com.telerobot.fs.service.InboundDetailService;
import com.telerobot.fs.utils.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@DependsOn({"appContextProvider"})
public class AcdSqlQueue implements ApplicationListener<ApplicationReadyEvent>   {
	private static final Logger log = LoggerFactory.getLogger(AcdSqlQueue.class);
	private static final Object QUEUE_LOCKER = new Object();
	private static  List<InboundDetail> dataList = new ArrayList<>(10000);

	/**
	 * add cdr to sql queue.
	 * @param phone
	 */
	public static void addToSqlQueue(InboundDetail phone){
		if(phone.getOutboundPhoneInfo() != null){

			phone.getOutboundPhoneInfo().setCallstatus(6);
			phone.getOutboundPhoneInfo().setAcdOpnum(phone.getOpnum());
			phone.getOutboundPhoneInfo().setCallEndTime(phone.getHangupTime());
			phone.getOutboundPhoneInfo().setAnsweredTime(phone.getAnsweredTime());
			phone.getOutboundPhoneInfo().setManualAnsweredTime(phone.getManualAnsweredTime());
			phone.getOutboundPhoneInfo().setManualAnsweredTimeLen(phone.getManualAnsweredTimeLen());

			if(phone.getHangupTime() > 0) {
				phone.getOutboundPhoneInfo().setTimeLen((int) ((phone.getHangupTime() - phone.getOutboundPhoneInfo().getCalloutTime())));
				if(phone.getAnsweredTime() > 0){
					phone.getOutboundPhoneInfo().setValidTimeLen((int) ((phone.getHangupTime() - phone.getOutboundPhoneInfo().getAnsweredTime())));
				}

				List<JSONObject> origDialogueList = phone.getOutboundPhoneInfo().getDialogue();
				origDialogueList.addAll(phone.getChatContent());
				phone.getOutboundPhoneInfo().setDialogue(origDialogueList);
			}

			// Redirect the data to another SQL queue.
			ScheduledScanTask.addToSQLQueue(phone.getOutboundPhoneInfo());
			return;
		}
		synchronized (QUEUE_LOCKER) {
			dataList.add(phone);
		}
    }
	 
	private static  void processSqlQueue(){
		log.info("start processSqlQueue thread... ");
		while(true){
			if(dataList.size() > 0){
				List<InboundDetail> phoneList = new ArrayList<InboundDetail>(10000);
				synchronized (QUEUE_LOCKER) {
					int arrayLen = AcdSqlQueue.dataList.size();
					for(int i=arrayLen - 1; i>=0; i--){
						InboundDetail phone = AcdSqlQueue.dataList.get(i);
						if(!phoneList.contains(phone)) {
							phoneList.add(phone);
						}
					}
					AcdSqlQueue.dataList.clear();
				}
				try {
					AppContextProvider.getBean(InboundDetailService.class).updateInbound(phoneList);
					log.info("executed sql batch update task, affect rows: " + phoneList.size());
					phoneList.clear();
				}
				catch (Throwable e){
					log.error("database error occurs while executed sql batch update task:" + e.toString()
							+ ", JSON Data:" + JSON.toJSONString(phoneList));
				}
			}
			ThreadUtil.sleep(11000);
		}
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
		new Thread(
				new Runnable() {
					@Override
					public void run() {
						processSqlQueue();
					}
				}, "processSQLQueue").start();
	}
}