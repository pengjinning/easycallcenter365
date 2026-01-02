package com.telerobot.fs.entity.bo;

import com.telerobot.fs.acd.CallHandler;
import lombok.Data;

import java.util.Objects;
import java.util.concurrent.Semaphore;

@Data
public class LlmConsumer {

    private Semaphore semaphore;
    private String uuid;
    private String llmUuid;
    private int llmConcurrentNum;
    private volatile boolean released;

    public LlmConsumer(String uuid, int llmUuid, int llmConcurrentNum){
        this.semaphore = new Semaphore(0);
        this.uuid = uuid;
        this.llmUuid = String.valueOf(llmUuid);
        this.llmConcurrentNum = llmConcurrentNum;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
            // 引用相等返回 true
        }
        // 如果等于 null，或者对象类型不同返回 false
        if (!(o instanceof LlmConsumer)) {
            return false;
        }
        // 强转为自定义 CallHandler 类型
        LlmConsumer callHandler = (LlmConsumer) o;
        // 如果uuid相等，就返回 true
        return uuid.equals(callHandler.uuid);
    }
    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

}
