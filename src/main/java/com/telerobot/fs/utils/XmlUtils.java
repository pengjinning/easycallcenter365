package com.telerobot.fs.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;

public class XmlUtils {
    protected final static Logger logger = LoggerFactory.getLogger(XmlUtils.class);

    /**
     * parse freeSWITCH online registration user list info.
     * @param xml
     * @return
     */
    public static String parseFsOnlineUserListXml(String xml, String extensionNumber) {
        if(StringUtils.isNullOrEmpty(xml)){
            return "";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes()));

            NodeList registrations = doc.getElementsByTagName("registration");

            for (int i = 0; i < registrations.getLength(); i++) {
                Element registration = (Element) registrations.item(i);

                // get sip-auth-user element
                Node sipAuthUserNode = registration.getElementsByTagName("sip-auth-user").item(0);
                if (sipAuthUserNode != null && extensionNumber.equals(sipAuthUserNode.getTextContent())) {

                    // get network-ip和network-port
                    String networkIp = registration.getElementsByTagName("network-ip")
                            .item(0).getTextContent();
                    String networkPort = registration.getElementsByTagName("network-port")
                            .item(0).getTextContent();

                    return networkIp + ":" + networkPort;
                }
            }
        } catch (Throwable e) {
            logger.error("parseFsOnlineUserListXml error! {} {}", e.toString(), CommonUtils.getStackTraceString(e.getStackTrace()) );
        }
        return "";
    }

}
