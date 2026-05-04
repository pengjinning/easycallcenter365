package com.telerobot.fs.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SwitchRtpPortConfigReader {
    private static final Logger logger = LoggerFactory.getLogger(SwitchRtpPortConfigReader.class);

    /**
     *  read 'rtp-start-port' and 'rtp-end-port' parameter from switch.conf.xml
     * @return rtp-start-port = arrayList.get(0); rtp-end-port = arrayList.get(1)
     */
    public static List<Integer> load(String filePath) {
        int rtpStartPort = 0;
        int rtpEndPort = 0;

        List<Integer> resultList = new ArrayList<>();
        try {
            File file = new File(filePath);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);

            NodeList paramList = doc.getElementsByTagName("param");

            for (int i = 0; i < paramList.getLength(); i++) {
                Node node = paramList.item(i);

                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;

                    String name = element.getAttribute("name");
                    String value = element.getAttribute("value");

                    if ("rtp-start-port".equals(name)) {
                        rtpStartPort = Integer.parseInt(value);
                    } else if ("rtp-end-port".equals(name)) {
                        rtpEndPort = Integer.parseInt(value);
                    }
                }
            }

        } catch (Throwable e) {
            logger.error("switch.conf.xml read error , {}", CommonUtils.getStackTraceString(e.getStackTrace()));
        }
        resultList.add(rtpStartPort);
        resultList.add(rtpEndPort);
        return  resultList;
    }

    public static void main(String[] args) {
        SwitchRtpPortConfigReader reader = new SwitchRtpPortConfigReader();

        List<Integer> resultList = reader.load("D:\\Program Files\\FreeSWITCH\\conf\\autoload_configs\\switch.conf.xml");

        System.out.println("rtp-start-port = " + resultList.get(0));
        System.out.println("rtp-end-port   = " + resultList.get(1));
    }
}