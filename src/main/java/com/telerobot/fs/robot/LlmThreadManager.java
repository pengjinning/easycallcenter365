package com.telerobot.fs.robot;

import com.telerobot.fs.entity.bo.LlmConsumer;
import com.telerobot.fs.utils.ThreadUtil;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class LlmThreadManager {

    protected final static Logger logger = LoggerFactory.getLogger(LlmThreadManager.class);
    private static LlmThreadManager instance = new LlmThreadManager();

    public LlmThreadManager(){
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                logger.info(" LlmThreadManager startAllocatorThread ... ");
                startAllocatorThread();
            }
        }).start();
    }

    /**
     *  key: 大模型的id
     *  value: 大模型并发的计数器
     */
    private static ConcurrentHashMap<String, Semaphore> llmCapacityCounter =
            new ConcurrentHashMap<>();

    /**
     *  key: 大模型的id
     *  value: 申请并发的消费者列表；
     */
    private static  ConcurrentHashMap<String, ArrayBlockingQueue<LlmConsumer>> consumers =
            new ConcurrentHashMap<>(1000);

    private static void startAllocatorThread() throws InterruptedException {
        while (true) {
            Iterator<Map.Entry<String, Semaphore>> it = llmCapacityCounter.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Semaphore> entry = it.next();
                Semaphore tmpObj = entry.getValue();
                int permits = tmpObj.availablePermits();
                String key = entry.getKey();

                ArrayBlockingQueue<LlmConsumer> typeConsumers = consumers.get(key);
                if (typeConsumers != null && permits > 0) {
                    while (permits > 0) {
                        LlmConsumer consumer = typeConsumers.poll();
                        if (null != consumer) {
                            tmpObj.acquire();
                            //通知等待的线程
                            consumer.getSemaphore().release();
                            permits--;
                        }else{
                            break;
                        }
                    }
                }
            }
            ThreadUtil.sleep(1);
        }
    }

    private static boolean addToQueue(ArrayBlockingQueue<LlmConsumer> typeConsumers,
                               LlmConsumer consumer){
        boolean success = false;
        if (!typeConsumers.contains(consumer)) {
            synchronized (consumer.getUuid().intern()) {
                if (!typeConsumers.contains(consumer)) {
                    success =   typeConsumers.add(consumer);
                }
            }
        }
        return success;
    }

    private static boolean registerToQueue(LlmConsumer consumer){
        ArrayBlockingQueue<LlmConsumer> typeConsumers = consumers.get(consumer.getLlmUuid());
        if(typeConsumers!= null){
           return  addToQueue(typeConsumers, consumer);
        }else{
            synchronized (consumer.getLlmUuid().intern()) {
                typeConsumers = consumers.get(consumer.getLlmUuid());
                if(typeConsumers == null) {
                    typeConsumers = new ArrayBlockingQueue<LlmConsumer>(1000);
                    consumers.put(consumer.getLlmUuid(), typeConsumers);
                }
                return addToQueue(typeConsumers, consumer);
            }
        }
    }

    public static void acquire(LlmConsumer consumer){

        if(consumer.getLlmConcurrentNum() == 0){
            return;
        }

        //check if llm id exists in global llmCapacityCounter
        Semaphore typePermit = llmCapacityCounter.get(consumer.getLlmUuid());
        if(null == typePermit){
            synchronized (consumer.getLlmUuid().intern()){
                typePermit = llmCapacityCounter.get(consumer.getLlmUuid());
                if(null == typePermit){
                    typePermit = new Semaphore(consumer.getLlmConcurrentNum());
                    llmCapacityCounter.put(consumer.getLlmUuid(), typePermit);
                }
            }
        }

        registerToQueue(consumer);

        try {
            // 等待startAllocatorThread线程的通知;
            consumer.getSemaphore().acquire();
        }catch (Throwable e){
        }
    }

    public static void release(LlmConsumer consumer) {
        logger.info("{} Try to release llm concurrency.", consumer.getUuid());
        if (consumer.getLlmConcurrentNum() == 0) {
            return;
        }

        ArrayBlockingQueue<LlmConsumer> typeConsumers = consumers.get(consumer.getLlmUuid());
        if (typeConsumers != null) {
            if (!consumer.isReleased()) {
                synchronized (consumer.getUuid()) {
                    if (!consumer.isReleased()) {
                        consumer.setReleased(true);
                        Semaphore typePermit = llmCapacityCounter.get(consumer.getLlmUuid());
                        if (null != typePermit) {
                            typePermit.release();
                            logger.info("{} Successfully released llm concurrency.", consumer.getUuid());
                        }
                    }
                }
            }
        }
    }

}
