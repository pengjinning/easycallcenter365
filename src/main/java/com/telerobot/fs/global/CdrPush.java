package com.telerobot.fs.global;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.entity.po.CdrDetail;
import com.telerobot.fs.entity.po.CdrEntity;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.DateUtils;
import com.telerobot.fs.utils.OkHttpClientUtil;
import com.telerobot.fs.utils.StringUtils;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

@Component
@DependsOn("appContextProvider")
public class CdrPush implements ApplicationListener<ApplicationReadyEvent> {

    private static Logger logger =  LoggerFactory.getLogger(CdrPush.class);
    private static Semaphore semaphore = new Semaphore(9999);
    private static ArrayBlockingQueue<CdrDetail> cdrQueue = new ArrayBlockingQueue<>(9999);
    private static boolean checkPostCdrEnabled(){
        return Boolean.parseBoolean(SystemConfig.getValue("post_cdr_enabled", "true"));
    }

    public static boolean addCdrToQueue(CdrDetail cdr){
         if(!checkPostCdrEnabled()){
             logger.info("{} cdr push is not enabled.", cdr.getUuid());
             return false;
         }
         if(cdrQueue.add(cdr)){
             semaphore.release(1);
             return true;
         }else{
             logger.error("{} cdr-push queue is full. Cant not process new requests. cdr json={}",
                     cdr.getUuid(), JSON.toJSONString(cdr)
             );
         }
         return  false;
    }

    private boolean postCdr(CdrDetail cdr){
        try {
            String url = SystemConfig.getValue("post_cdr_url");
            if (StringUtils.isNullOrEmpty(url)) {
                logger.error("post_cdr_url  has not been configured yet.");
                return false;
            }
            String cdrData = JSON.toJSONString(cdr);
            String response = OkHttpClientUtil.postCdr(url, cdrData);
            logger.info("{} postCdr: {}， request url {} , response: {}", cdr.getUuid(),  cdrData, url, response);
            if ("success".equalsIgnoreCase(response)) {
                logger.info("{} post cdr succeed.", cdr.getUuid());
                return true;
            } else {
                logger.error("{} post cdr failed: cdr data={}", cdr.getUuid(), cdrData);
            }
        }catch (Throwable e){
            logger.error("postCdr failed: {} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()));
        }
        return false;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
           new Thread(new Runnable() {
               @SneakyThrows
               @Override
               public void run() {
                       logger.info("CdrPush thread is now running...");
                       while (true) {
                           semaphore.acquire();
                           CdrDetail cdrDetail = cdrQueue.poll();
                           if (null != cdrDetail) {
                               if(!postCdr(cdrDetail)){
                                   addCdrToQueue(cdrDetail);
                                   Thread.sleep(100);
                               }
                           }
                           Thread.sleep(10);
                       }
               }
           }).start();
    }
}
