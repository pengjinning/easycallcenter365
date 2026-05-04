package com.telerobot.fs.robot.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.entity.dto.LlmAiphoneRes;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.entity.dto.llm.CozeAccount;
import com.telerobot.fs.entity.dto.llm.LlmAccount;
import com.telerobot.fs.entity.pojo.LlmToolRequest;
import com.telerobot.fs.robot.AbstractChatRobot;
import com.telerobot.fs.utils.CommonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LocalWebApiTest extends AbstractChatRobot {

    static List<LlmAiphoneRes> testDataList = new ArrayList<>(10);
    static {

        String[] data = new String[]{
                "您好，请问您是测试1本人是吗？| /home/Records/23/round-1.wav; ",
                "这里是中信银行委托方，这个电话号码是测试1在中信银行办理业务时登记的号码，您是测试1吗？ | /home/Records/23/round-2-1.wav;/home/Records/23/round-2-2.wav;/home/Records/23/round-2-3.wav",
                "这里是中信银行委托方，这边主要是通知您一下，您在中信银行卡尾号6388的信用卡已错过到期环款日，当期账单账面欠款总额9097元全国统一核账时间在今天下午5点需要您在此之前处理进来，可以吧？| /home/Records/23/round-3-1.wav;/home/Records/23/round-3-2.wav;",
                "您的账单现在已经过环款日了，能和我们说一下您是为什么还没处理欠款吗？ | /home/Records/23/round-4.wav",
                "好的，请您在今天下午5点之前至少处理您的最低还款额0元时间和资金都没问题，对吗？|/home/Records/23/round-5.wav",
                "银行稍后安排工作人员查账，建议您尽快处理欠款，不打扰您了，再见。|/home/Records/23/round-6.wav"
        };
        for (String item : data) {
            String[] array = item.split("\\|");
            LlmAiphoneRes aiphoneRes = new LlmAiphoneRes();
            aiphoneRes.setBody(array[0].trim());
            aiphoneRes.setTtsFilePathList(array[1].trim());
            aiphoneRes.setStatus_code(1);
            aiphoneRes.setCostTime(1L);
            testDataList.add(aiphoneRes);
        }
    }

    private AtomicLong round = new AtomicLong(0);

    public  void makeMockData() {
        AccountBaseEntity llmAccount = getAccount();
        llmAccount.voiceSource = "";
        llmAccount.customerNoVoiceTips  = "/home/Records/23/no-voice.wav";
        llmAccount.transferToAgentTips  = "/home/Records/23/transfer-tips.wav";
        llmAccount.hangupTips = "/home/Records/23/hangup-tips.wav";
    }

    @Override
    public LlmAiphoneRes  talkWithAiAgent(String question, Boolean... withKbResponse) {
        int index = (int)round.getAndIncrement();
        if(index == testDataList.size() - 1){
            LlmAiphoneRes res = testDataList.get(index);
            res.setTransferToAgent(1);
            return res;
        }else{
            return testDataList.get(index);
        }
    }

}
