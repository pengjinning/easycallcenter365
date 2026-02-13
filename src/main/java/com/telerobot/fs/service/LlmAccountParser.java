package com.telerobot.fs.service;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.entity.dao.LlmAgentAccount;
import com.telerobot.fs.entity.dto.llm.AccountBaseEntity;
import com.telerobot.fs.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LlmAccountParser {

    protected final static Logger logger = LoggerFactory.getLogger(LlmAccountParser.class);

    public static AccountBaseEntity parse(LlmAgentAccount accountJSON ){
        if(accountJSON == null)  { return null; }
        try {
            Class<?> clazz = Class.forName("com.telerobot.fs.entity.dto.llm." + accountJSON.getAccountEntity());
            AccountBaseEntity entity =  (AccountBaseEntity) JSON.parseObject(accountJSON.getAccountJson(), clazz);
            entity.id = accountJSON.getId();
            entity.provider = accountJSON.getProviderClassName();
            entity.interruptFlag = accountJSON.getInterruptFlag();
            entity.interruptKeywords = accountJSON.getInterruptKeywords();
            entity.interruptIgnoreKeywords = accountJSON.getInterruptIgnoreKeywords();
            entity.concurrentNum = accountJSON.getConcurrentNum();
            entity.transferManualDigit = accountJSON.getTransferManualDigit();
            entity.kbCatId = accountJSON.getKbCatId();
            return entity;
        } catch (Throwable e) {
            logger.error("parse llmAccount error for accountId={}, {} {} ", accountJSON.getId(),
                    e.toString(),
                    CommonUtils.getStackTraceString(e.getStackTrace())
            );
        }
        return null;
    }

}
