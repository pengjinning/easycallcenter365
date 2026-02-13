package com.telerobot.fs.service;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Service
public class PhoneService {
	@Resource
	private JdbcTemplate jdbcTemplate;

	public void batchUpdatePhone(final List<CustmInfoEntity> phoneList) {
		String sql = "UPDATE cc_call_phone SET " +
				"cust_name = ?, " +
				"callstatus = ?, " +
				"callout_time = ?, " +
				"callcount = ?, " +
				"call_end_time = ?, " +
				"time_len = ?, " +
				"valid_time_len = ?, " +
				"uuid = ?, " +
				"connected_time = ?, " +
				"hangup_cause = ?, " +
				"answered_time = ?, " +
				"dialogue = ?, " +
				"wavfile = ?, " +
				"record_server_url = ?, " +
				"dialogue_count = ?, " +
				"acd_opnum = ?, " +
				"acd_queue_time = ?, " +
				"acd_wait_time = ?, " +
				"empty_number_detection_text = ?, " +
				"ivr_dtmf_digits = ?, " +
				"manual_answered_time = ?, " +
				"manual_answered_time_len = ? " +
				"WHERE id = ?";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				CustmInfoEntity cp = phoneList.get(i);
				ps.setString(1, cp.getCustName());
				ps.setInt(2, cp.getCallstatus());
				ps.setLong(3, cp.getCalloutTime());
				ps.setInt(4, cp.getCallcount());
				ps.setLong(5, cp.getCallEndTime());
				ps.setInt(6, cp.getTimeLen());
				ps.setInt(7, cp.getValidTimeLen());
				ps.setString(8, cp.getUuid());
				ps.setLong(9, cp.getConnectedTime());
				ps.setString(10, cp.getHangupCause());
				ps.setLong(11, cp.getAnsweredTime());
				ps.setString(12, JSON.toJSONString(cp.getDialogue()));
				ps.setString(13, cp.getWavfile());
				ps.setString(14, cp.getRecordServerUrl());
				ps.setInt(15, cp.getDialogueCount());
				ps.setString(16, cp.getAcdOpnum());
				ps.setLong(17, cp.getAcdQueueTime());
				ps.setInt(18, cp.getAcdWaitTime());
				ps.setString(19, cp.getEmptyNumberDetectionText());
				ps.setString(20, cp.getIvrDtmfDigits());
				ps.setLong(21, cp.getManualAnsweredTime());
				ps.setLong(22, cp.getManualAnsweredTimeLen());
				ps.setString(23, cp.getId()); // WHERE id=?
			}

			@Override
			public int getBatchSize() {
				return phoneList.size();
			}
		});
	}



}
