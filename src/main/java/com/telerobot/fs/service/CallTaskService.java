package com.telerobot.fs.service;

import com.telerobot.fs.entity.dao.CallTaskEntity;
import com.telerobot.fs.entity.dao.CustmInfoEntity;
import com.telerobot.fs.entity.dao.LlmAgentAccount;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.RandomUtils;
import com.telerobot.fs.utils.ThreadUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

@Service
public class CallTaskService {
	private final Logger log = LoggerFactory.getLogger(this.getClass());
	@Resource
	private JdbcTemplate jdbcTemplate;

	/**
	 * Reset the status of all outbound call task batches
	 */
	@PostConstruct
	public void init() {
		try {
			jdbcTemplate.execute("update cc_call_task set executing=0  WHERE  ifcall=1");
			log.info("The status of all outbound call task batches was successfully reset.");
		}
		catch (Throwable e) {
			log.error("An error occurred when resetting all outbound call data batches, " + e.toString());
		}
	}


	/**
	 *
	 * Development and testing purpose: Reset the outbound call data of the specified batch
	 * @param batchId
	 * @return
	 */
	public int resetBatchInfoAndPhoneData(int batchId){
		int affectRow = 0;
		try {
			affectRow =  jdbcTemplate.update("update cc_call_phone set callstatus=0 where batch_id=" + batchId);
			jdbcTemplate.execute("update cc_call_task set ifcall=1,executing=0,stop_time=0 where batch_id=" + batchId);
			log.info("successfully reset data of batchId  {}, affect rows {} .", batchId, affectRow);
		}
		catch (Throwable e) {
			log.info("An error occurred when resetting the specified outbound call data batch!" + e.toString());
		}
		return affectRow;
	}

   public LlmAgentAccount getLlmAgentAccountById(int accountId){
	   List<LlmAgentAccount> list = new LinkedList<LlmAgentAccount>();
	   String checkSQL = "select * from  cc_llm_agent_account  where id=" + accountId;
	   RowMapper<LlmAgentAccount> rowMapper = new BeanPropertyRowMapper<>(LlmAgentAccount.class);
	   try {
		   list = this.jdbcTemplate.query(checkSQL, rowMapper);
	   } catch (Throwable e) {
		   log.error("An error occurred when obtaining the data of the specified LlmAgentAccount, id={} {} {}", accountId,
				   e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
	   }
	   return list.get(0);
   }

	public void batchUpdatePhoneStatus(int status, final List<String> phoneIdList) {
		String sql = "Update cc_call_phone set callstatus="+ status +" where id = ? ";
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int index) throws SQLException {
				String id = phoneIdList.get(index);
				ps.setString(1,  id);
			}
			@Override
			public int getBatchSize() {
				return phoneIdList.size();
			}
		});
	}

	private void tryUpdatePhoneStatus(int batchId, int status, List<String> updateList){
		int counter = 1;
		int maxTry = 70;
		boolean failed = false;
		while (counter <= maxTry) {
			try {
				batchUpdatePhoneStatus(status, updateList);
				log.info("{} Successfully marked the status of outbound call data: {} rows.", batchId, updateList.size());
				failed = false;
				break;
			} catch (Throwable e) {
				failed = true;
				int sleepMills = RandomUtils.getRandomByRange(5000, 12000);
				log.error("{}  Failed when marking the status of outbound call data！ {} , retry after {} seconds , details： {}.",
						batchId,
						e.toString(),
						sleepMills,
						CommonUtils.getStackTraceString(e.getStackTrace())
				);
				ThreadUtil.sleep(sleepMills);
			}
			counter++;
		}
		if (failed) {
			log.error("{} The marking of the outbound call data status failed, after {} times retry！", batchId, maxTry);
		}
	}

	/**
	 *
	 * Obtain the phone numbers from the database and mark the extracted data at the same time
	 * @return  return LinkedList<PhoneNumEntity> type
	 */
	public List<CustmInfoEntity> GetPhoneNumberFromDb(int phoneNumBuffer, int batchId) {
		long startTime = System.currentTimeMillis();
		List<CustmInfoEntity> phoneList = new LinkedList<CustmInfoEntity>();
		String checkSQL = "select id, cust_name, telephone, callcount, biz_json, tts_text from  cc_call_phone  where batch_id=" + batchId
				+ " and callstatus=0  order  BY RAND()  LIMIT " + phoneNumBuffer;
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(checkSQL);
		if (rows.size() == 0) {
			return phoneList;
		}
		StringBuilder sb = new StringBuilder("");
		Iterator<Map<String, Object>> it = rows.iterator();
		ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(rows.size() + 10);

		while (it.hasNext()) {
			Map<String, Object> map = (Map<String, Object>) it.next();
			// Id是数字的情况，sql拼接可以不用单引号，如果id是字符串的情况，必须使用单引号，否则会导致sql数据修改异常;
			// 异常的表现是：全部数据都被修改掉;
			queue.add(map.get("id").toString());
			String id = map.get("id").toString();
			String telephone = (String) map.get("telephone");
			String custName = (String) map.get("cust_name");
			String bizJson = (String) map.get("biz_json");
			String ttsText = (String) map.get("tts_text");
			if (StringUtils.isEmpty(telephone)) {
				log.error(" Illegal data， telephone is null, id={}", id);
			} else {
				phoneList.add(new CustmInfoEntity(id, custName, telephone, bizJson, batchId, ttsText));
			}
		}
		log.info("batchId={}, The time consumed to read the outbound call number data is {} ms, rows = {}",
				batchId,
				System.currentTimeMillis() - startTime,
				rows.size()
		);

		startTime = System.currentTimeMillis();
		int affectRow = 0;
		while (queue.peek() != null) {
			int maxRetrieveCount = 500;
			List<String> updateList = new ArrayList<>(maxRetrieveCount);
			for (int i = 0; i < maxRetrieveCount; i++) {
				String one = queue.poll();
				if (null != one) {
					updateList.add(one);
				} else {
					break;
				}
			}
			if (updateList.size() > 0) {
				tryUpdatePhoneStatus(batchId, 1, updateList);
			}

			affectRow += updateList.size();
		}

		log.info("Mark status of phone data, time consumed is {} ms, affectRow={},  batchId={} ",
				System.currentTimeMillis() - startTime,
				affectRow,
				batchId
		);
		rows.clear();
		return phoneList;
	}


	/**
	 *  根据手机号码获取客户信息;
	 */
	public CustmInfoEntity getCustomInfoByPhoneNumber(String phone) {
		List<CustmInfoEntity> phoneList = new LinkedList<CustmInfoEntity>();
		String checkSQL = "select id, cust_name, telephone, callcount, biz_json,batch_id from  cc_call_phone  where telephone='"+
				          phone.replace("'","") +"' LIMIT 1";
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(checkSQL);
		if (rows.size() == 0) {
			return null;
		}

		Iterator<Map<String, Object>> it = rows.iterator();

		while (it.hasNext()) {
			Map<String, Object> map = (Map<String, Object>) it.next();
			// Id是数字的情况，sql拼接可以不用单引号，如果id是字符串的情况，必须使用单引号，否则会导致sql数据修改异常;
			// 异常的表现是：全部数据都被修改掉;
			String id = map.get("id").toString();
			String telephone = (String) map.get("telephone");
			String custName = (String) map.get("cust_name");
			String bizJson = (String) map.get("biz_json");
			String batchId = map.get("batch_id").toString();
			if (StringUtils.isEmpty(telephone) ||
					StringUtils.isEmpty(bizJson)) {
				log.error(String.format(
						"遇到不合法的 cc_call_phone 数据，详情: telephone:%s,  bizJson: %s.", telephone, bizJson
				));
			} else {
				phoneList.add(new CustmInfoEntity(id, custName, telephone, bizJson, Integer.parseInt(batchId), ""));
			}
		}

		rows.clear();
		return phoneList.get(0);
	}


	/**
	 * Obtain the information of the specified task batch
	 ***/
	public CallTaskEntity getTaskInfoByIdEx(int batchId) {
		List<CallTaskEntity> batchList = new LinkedList<CallTaskEntity>();
		String checkSQL = "select * from  cc_call_task as t,cc_gateway as g  where "+
				"  t.gateway_id = g.id and batch_id=" + batchId;
		RowMapper<CallTaskEntity> rowMapper = new BeanPropertyRowMapper<CallTaskEntity>(CallTaskEntity.class);
		try {
			batchList = this.jdbcTemplate.query(checkSQL, rowMapper);
		} catch (Throwable e) {
			log.error("An error occurred when obtaining the data of the specified task：{} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
		}
		return batchList.get(0);
	}

	/**
	 * Obtain the information of the specified task batch
	 ***/
	public CallTaskEntity getTaskInfoById(int batchId) {
		CallTaskEntity batchInfo = null;
		String sql = "Select * from cc_call_task  where batch_id=" + batchId;

		try {
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
			Iterator<Map<String, Object>> it = rows.iterator();
			while (it.hasNext()) {
				Map<String, Object> map = (Map<String, Object>) it.next();
				batchInfo = new CallTaskEntity();
				batchInfo.setBatchId(Integer.parseInt(map.get("batch_id").toString()));
				batchInfo.setGroupId(map.get("group_id").toString());
				batchInfo.setBatchName(map.get("batch_name").toString());
				batchInfo.setIfcall(Integer.parseInt(map.get("ifcall").toString()));
				batchInfo.setRate(Double.parseDouble(map.get("rate").toString()));
				batchInfo.setThreadNum(Integer.parseInt(map.get("thread_num").toString()));
				batchInfo.setCreatetime(Long.parseLong(map.get("createtime").toString()));
				batchInfo.setExecuting(Integer.parseInt(map.get("executing").toString()));
				batchInfo.setStopTime(Long.parseLong(map.get("stop_time").toString()));
				batchInfo.setUserid(map.get("userid").toString());
				batchInfo.setTaskType(Integer.parseInt(map.get("task_type").toString()));
				batchInfo.setGatewayId(Integer.parseInt(map.get("gateway_id").toString()));
				batchInfo.setCallNodeNo(map.get("call_node_no").toString());
			}
			rows.clear();
		} catch (Exception e) {
			log.error("An error occurred when obtaining the data of the specified task: {} {} ",
					e.toString(), CommonUtils.getStackTraceString(e.getStackTrace())
			);
		}
		return batchInfo;
	}


	public List<CallTaskEntity> getBatchList(String callNodeNo) {
		List<CallTaskEntity> batchList = new LinkedList<CallTaskEntity>();
		String checkSQL = "select * from  cc_call_task as t,cc_gateways as g  where call_node_no='"+ callNodeNo.trim() +
				"'  and  t.gateway_id = g.id and  ifcall=1 and executing=0 order by batch_id asc ";
		RowMapper<CallTaskEntity> rowMapper = new BeanPropertyRowMapper<CallTaskEntity>(CallTaskEntity.class);
		try {
			 batchList = this.jdbcTemplate.query(checkSQL, rowMapper);
		} catch (Throwable e) {
			log.error("An error occurred when obtaining the list of outbound task batches：{} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
		}
		return batchList;
	}

	/**
	 *  setBatchTasKStatus
	 ***/
	public int setBatchTaskStatus(List<CallTaskEntity> taskList) {
		if(taskList.size() == 0) {
			return 0;
		}
		int affectRow = 0;
		String resetSQL = "Update  cc_call_task  Set executing=1 ";
		StringBuilder sb = new StringBuilder("");
		for (CallTaskEntity task : taskList) {
			sb.append(task.getBatchId()).append(",");
		}
		String idList = sb.toString();
		resetSQL = resetSQL + " where batch_id in ( "
				   + idList.substring(0, idList.length()-1)  + ")";
		try{
			jdbcTemplate.update(resetSQL);
		}
		catch(Throwable e){
			log.error("setBatchTastStatus error！{} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace())
			);
		}
		resetSQL = null;
		idList = null;
		return affectRow;
	}

	/**
	 * Reset the call status of the specified task batch data
	 */
	public int resetCallData(int batchId) {
		String resetCallPhone = String.format("update cc_call_phone set  callstatus=0    where batch_id=%d  and  callstatus=1 and callout_time=0",
				batchId
		);

		int affectRow = 0;
		boolean resetSuccess = false;
		while (!resetSuccess) {
			try {
				affectRow = jdbcTemplate.update(resetCallPhone);
				resetSuccess = true;
			} catch (Throwable ex) {
				int sleepMills = RandomUtils.getRandomByRange(5000, 12000);
				log.error("resetCallData error，retry after {} ms, sql={},  ex={}, trace={}",
						sleepMills,
						resetCallPhone,
						ex.toString(),
						CommonUtils.getStackTraceString(ex.getStackTrace()));
				ThreadUtil.sleep(sleepMills);
			}
		}

		return affectRow;
	}

	/**
	 * Set the current task status to stop
	 */
	public void UpdateTaskStatusToStop(int batchId, long stopTime) {
		String sql = "update cc_call_task Set  stop_time="+ stopTime  +", ifcall=0 where batch_id=" + batchId;
		try {
			jdbcTemplate.update(sql);
		} catch (Throwable e) {
			log.error("UpdateTaskStatusToStop error {} {} ", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
		}
	}


}
