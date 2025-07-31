package com.telerobot.fs.entity.dao;

import org.hibernate.procedure.spi.ParameterRegistrationImplementor;

/**
 * Telephone robot outbound calling task entity class
 */
public class CallTaskEntity {

	private int batchId;
	private String groupId;
	private String batchName;
	private int ifcall;
	private double rate	;
	private int threadNum;
	private long createtime;
	private int executing;
	private long stopTime;
	private String userid;

	/**
	 * 0 Pure manual outbound call;
	 * 1 Pure AI outbound calling;
	 * 2 Human-machine coupling
	 */
	private int taskType;

	private int gatewayId;
	private String gwAddr;
	private String callNodeNo;
    private String caller;
    private String calleePrefix;
    private String codec;
    private String gwName;
    private String profileName;

	/**
	 *  voice call play times
	 */
	private int playTimes;

	private String voiceCode;
	private String voiceSource;

	/**
	 * The average ringing duration of the call
	 */
	private double avgRingTimeLen;

	/**
	 * The average pure call duration per call
	 */
    private double avgCallTalkTimeLen;

	/**
	 *  The duration of form filling after the call ends
	 */
	private double avgCallEndProcessTimeLen;

	private int llmAccountId;

	public int getBatchId() {
		return batchId;
	}

	public void setBatchId(int batchId) {
		this.batchId = batchId;
	}

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getBatchName() {
		return batchName;
	}

	public void setBatchName(String batchName) {
		this.batchName = batchName;
	}

	public int getIfcall() {
		return ifcall;
	}

	public void setIfcall(int ifcall) {
		this.ifcall = ifcall;
	}

	public double getRate() {
		return rate;
	}

	public void setRate(double rate) {
		this.rate = rate;
	}

	public int getThreadNum() {
		return threadNum;
	}

	public void setThreadNum(int threadNum) {
		this.threadNum = threadNum;
	}

	public long getCreatetime() {
		return createtime;
	}

	public void setCreatetime(long createtime) {
		this.createtime = createtime;
	}

	public int getExecuting() {
		return executing;
	}

	public void setExecuting(int executing) {
		this.executing = executing;
	}

	public long getStopTime() {
		return stopTime;
	}

	public void setStopTime(long stopTime) {
		this.stopTime = stopTime;
	}

	public String getUserid() {
		return userid;
	}

	public void setUserid(String userid) {
		this.userid = userid;
	}

	public int getTaskType() {
		return taskType;
	}

	public void setTaskType(int taskType) {
		this.taskType = taskType;
	}

	public int getGatewayId() {
		return gatewayId;
	}

	public void setGatewayId(int gatewayId) {
		this.gatewayId = gatewayId;
	}

	public String getGwAddr() {
		return gwAddr;
	}

	public void setGwAddr(String gwAddr) {
		this.gwAddr = gwAddr;
	}

	public String getCallNodeNo() {
		return callNodeNo;
	}

	public void setCallNodeNo(String callNodeNo) {
		this.callNodeNo = callNodeNo;
	}

	public String getCaller() {
		return caller;
	}

	public void setCaller(String caller) {
		this.caller = caller;
	}

	public String getCalleePrefix() {
		return calleePrefix;
	}

	public void setCalleePrefix(String calleePrefix) {
		this.calleePrefix = calleePrefix;
	}

	public String getCodec() {
		return codec;
	}

	public void setCodec(String codec) {
		this.codec = codec;
	}

	public String getGwName() {
		return gwName;
	}

	public void setGwName(String gwName) {
		this.gwName = gwName;
	}

	public double getAvgRingTimeLen() {
		return avgRingTimeLen;
	}

	public void setAvgRingTimeLen(double avgRingTimeLen) {
		this.avgRingTimeLen = avgRingTimeLen;
	}

	public double getAvgCallTalkTimeLen() {
		return avgCallTalkTimeLen;
	}

	public void setAvgCallTalkTimeLen(double avgCallTalkTimeLen) {
		this.avgCallTalkTimeLen = avgCallTalkTimeLen;
	}

	public double getAvgCallEndProcessTimeLen() {
		return avgCallEndProcessTimeLen;
	}

	public void setAvgCallEndProcessTimeLen(double avgCallEndProcessTimeLen) {
		this.avgCallEndProcessTimeLen = avgCallEndProcessTimeLen;
	}

	public String getVoiceCode() {
		return voiceCode;
	}

	public void setVoiceCode(String voiceCode) {
		this.voiceCode = voiceCode;
	}

	public String getVoiceSource() {
		return voiceSource;
	}

	public void setVoiceSource(String voiceSource) {
		this.voiceSource = voiceSource;
	}

	public int getLlmAccountId() {
		return llmAccountId;
	}

	public void setLlmAccountId(int llmAccountId) {
		this.llmAccountId = llmAccountId;
	}

	public String getProfileName() {
		return profileName;
	}

	public void setProfileName(String profileName) {
		this.profileName = profileName;
	}

	public int getPlayTimes() {
		return playTimes;
	}

	public void setPlayTimes(int playTimes) {
		this.playTimes = playTimes;
	}
}
