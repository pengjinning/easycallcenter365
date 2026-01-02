package com.telerobot.fs.entity.dao;

import java.util.Objects;

/**
 * The entity of the number information for outbound calling tasks
 *
 * @author easycallcenter365@126.com
 * */
public class CustmInfoEntity {
	private volatile String id;
	private volatile int batchId;
	private volatile String telephone;
    private volatile String custName;
	private volatile long createtime;
	private volatile int callstatus = 2;
	private volatile long calloutTime = 0L;
	private volatile int callcount = 0;
	private volatile long callEndTime = 0L;
	private volatile int timeLen = 0;
	private volatile int validTimeLen = 0;
	private volatile String uuid = "";
	private volatile long connectedTime = 0L;
	private volatile String hangupCause = "";
	private volatile long  answeredTime = 0L;
	private volatile String dialogue = "";
	private volatile String wavfile = "";
	private volatile String recordServerUrl = "";
	private volatile String bizJson = "";
	private volatile int dialogueCount = 0;
	private volatile String acdOpnum = "";
	private volatile long acdQueueTime = 0;
	private volatile int acdWaitTime = 0;
	private volatile boolean hangup = false;
	private volatile String ttsText;
	private volatile String emptyNumberDetectionText = "";
	private volatile String ivrDtmfDigits = "";

	/**
	 * The call has been taken over by a robot
	 */
	private volatile boolean callCenterRegisterListener = false;

	private volatile CallTaskEntity taskInfo;

	public CustmInfoEntity(){}

	public CustmInfoEntity(String id, String custName, String phoneNum, String json, int batchId, String ttsText) {
		this.telephone = phoneNum;
		this.id = id;
		this.custName = custName;
		this.hangup = false;
		this.bizJson = json;
		this.batchId = batchId;
		this.ttsText = ttsText;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public int getBatchId() {
		return batchId;
	}

	public void setBatchId(int batchId) {
		this.batchId = batchId;
	}

	public String getTelephone() {
		return telephone;
	}

	public void setTelephone(String telephone) {
		this.telephone = telephone;
	}

	public String getCustName() {
		return custName;
	}

	public void setCustName(String custName) {
		this.custName = custName;
	}

	public long getCreatetime() {
		return createtime;
	}

	public void setCreatetime(long createtime) {
		this.createtime = createtime;
	}

	public int getCallstatus() {
		return callstatus;
	}

	public void setCallstatus(int callstatus) {
		this.callstatus = callstatus;
	}

	public long getCalloutTime() {
		return calloutTime;
	}

	public void setCalloutTime(long calloutTime) {
		this.calloutTime = calloutTime;
	}

	public int getCallcount() {
		return callcount;
	}

	public void setCallcount(int callcount) {
		this.callcount = callcount;
	}

	public long getCallEndTime() {
		return callEndTime;
	}

	public void setCallEndTime(long callEndTime) {
		this.callEndTime = callEndTime;
	}

	public int getTimeLen() {
		return timeLen;
	}

	public void setTimeLen(int timeLen) {
		this.timeLen = timeLen;
	}

	public int getValidTimeLen() {
		return validTimeLen;
	}

	public void setValidTimeLen(int validTimeLen) {
		this.validTimeLen = validTimeLen;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public long getConnectedTime() {
		return connectedTime;
	}

	public void setConnectedTime(long connectedTime) {
		this.connectedTime = connectedTime;
	}

	public String getHangupCause() {
		return hangupCause;
	}

	public void setHangupCause(String hangupCause) {
		this.hangupCause = hangupCause;
	}

	public long getAnsweredTime() {
		return answeredTime;
	}

	public void setAnsweredTime(long answeredTime) {
		this.answeredTime = answeredTime;
	}

	public String getDialogue() {
		return dialogue;
	}

	public void setDialogue(String dialogue) {
		this.dialogue = dialogue;
	}

	public String getWavfile() {
		return wavfile;
	}

	public void setWavfile(String wavfile) {
		this.wavfile = wavfile;
	}

	public String getRecordServerUrl() {
		return recordServerUrl;
	}

	public void setRecordServerUrl(String recordServerUrl) {
		this.recordServerUrl = recordServerUrl;
	}

	public String getBizJson() {
		return bizJson;
	}

	public void setBizJson(String bizJson) {
		this.bizJson = bizJson;
	}

	public int getDialogueCount() {
		return dialogueCount;
	}

	public void setDialogueCount(int dialogueCount) {
		this.dialogueCount = dialogueCount;
	}

	public String getAcdOpnum() {
		return acdOpnum;
	}

	public void setAcdOpnum(String acdOpnum) {
		this.acdOpnum = acdOpnum;
	}

	public long getAcdQueueTime() {
		return acdQueueTime;
	}

	public void setAcdQueueTime(long acdQueueTime) {
		this.acdQueueTime = acdQueueTime;
	}

	public int getAcdWaitTime() {
		return acdWaitTime;
	}

	public void setAcdWaitTime(int acdWaitTime) {
		this.acdWaitTime = acdWaitTime;
	}

	public boolean getHangup() {
		return hangup;
	}

	public void setHangup(boolean hangup) {
		this.hangup = hangup;
	}

	public boolean getCallCenterRegisterListener() {
		return callCenterRegisterListener;
	}

	public void setCallCenterRegisterListener(boolean callCenterRegisterListener) {
		this.callCenterRegisterListener = callCenterRegisterListener;
	}

	public String getTtsText() {
		return ttsText;
	}

	public void setTtsText(String ttsText) {
		this.ttsText = ttsText;
	}

	public CallTaskEntity getTaskInfo() {
		return taskInfo;
	}

	public void setTaskInfo(CallTaskEntity taskInfo) {
		this.taskInfo = taskInfo;
	}

	public String getEmptyNumberDetectionText() {
		return emptyNumberDetectionText;
	}

	public void setEmptyNumberDetectionText(String emptyNumberDetectionText) {
		this.emptyNumberDetectionText = emptyNumberDetectionText;
	}

	public String getIvrDtmfDigits() {
		return ivrDtmfDigits;
	}

	public void setIvrDtmfDigits(String ivrDtmfDigits) {
		this.ivrDtmfDigits = ivrDtmfDigits;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()){ return false;}
		if(this.id == null) { return  false; }
		CustmInfoEntity that = (CustmInfoEntity) o;
		return  this.getId().equalsIgnoreCase(
				that.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				this.getId()
		);
	}

}