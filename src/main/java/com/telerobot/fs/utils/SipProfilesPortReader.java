package com.telerobot.fs.utils;

import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SipProfilesPortReader {
    private static final Logger logger = LoggerFactory.getLogger(SipProfilesPortReader.class);
    /**
     * get  sip-ports of all sip-profile files in 'sip_profiles' directory
     *
     * @param dirPath sip_profiles directory
     * @return sip-port
     */
    public static List<Integer> readSipPorts(String dirPath) {
        List<Integer> sipPorts = new ArrayList<>();

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            logger.error("directory not exists , {}", dir.getName());
            return sipPorts;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files == null) {
            logger.error("No sip profiles found in directory  {} !", dir.getName());
            return sipPorts;
        }

        for (File file : files) {
            parseFile(file, sipPorts);
        }

        return sipPorts;
    }

    private static void parseFile(File file, List<Integer> sipPorts) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(true);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);

            NodeList paramList = doc.getElementsByTagName("param");

            for (int i = 0; i < paramList.getLength(); i++) {
                Element param = (Element) paramList.item(i);

                String name = param.getAttribute("name");
                if ("sip-port".equals(name)) {
                    String value = param.getAttribute("value");
                    int port = 0;
                    if(!StringUtils.isNumeric(value)){
                        EslMessage response = EslConnectionUtil.sendSyncApiCommand("eval", value);
                        StringBuilder body = new StringBuilder();
                        List<String> array = response.getBodyLines();
                        for (String s : array) {
                            body.append(s);
                        }
                        String actualValue = body.toString().trim();
                        if(StringUtils.isNumeric(actualValue)) {
                            logger.info("Dynamically calculate the actual value of the sip-port parameter '{}'  as  '{}'.", value, actualValue);
                            port = Integer.parseInt(actualValue);
                        }else{
                            logger.error("Error dynamically calculating the actual value of the sip-port parameter! {} {}", value, actualValue);
                        }
                    }else{
                        port = Integer.parseInt(value);
                    }
                    if(port > 0) {
                        sipPorts.add(port);
                    }
                }
            }

        } catch (Throwable e) {
            logger.error("sip profile {} read error , {}", file.getName(), CommonUtils.getStackTraceString(e.getStackTrace()));
        }
    }

    public static void main(String[] args) {
//        String path = "D:\\Program Files\\FreeSWITCH\\conf\\sip_profiles\\";
//
//        List<String> sipPorts = readSipPorts(path);
//
//        System.out.println("all sip-port:");
//        for (String port : sipPorts) {
//            System.out.println(port);
//        }

        String test = "/192.168.67.217:49952";
        String clientIP = CommonUtils.getIpFromFullAddress(test);
        System.out.println(clientIP);
    }
}