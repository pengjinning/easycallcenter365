package com.telerobot.fs.global;

import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.utils.ThreadPoolCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadPoolExecutor;

public class BizThreadPoolForEsl {
    private static final Logger logger = LoggerFactory.getLogger(BizThreadPoolForEsl.class);
    private static volatile ThreadPoolExecutor bizThreadPool = null;

    private  static void startIvrMainThreadPool(){
        if(bizThreadPool == null) {
            synchronized (BizThreadPoolForEsl.class) {
                if(bizThreadPool == null) {
                    int size = Integer.parseInt(SystemConfig.getValue("biz-thread-pool-size-for-esl", "50"));
                    bizThreadPool = ThreadPoolCreator.create(
                            size,
                            "biz_thread_pool_for_esl",
                            24L,
                            1000
                    );
                    logger.info("successfully create BizThreadPoolForEsl. thread pool size={}.", size);
                }
            }
        }
    }

    public static void submitTask(Runnable task){
        startIvrMainThreadPool();
        bizThreadPool.execute(task);
    }
}
