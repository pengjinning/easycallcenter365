package com.telerobot.fs.wshandle;

import com.alibaba.fastjson.JSON;
import com.telerobot.fs.config.SystemConfig;
import com.telerobot.fs.utils.CommonUtils;
import com.telerobot.fs.utils.FileUtils;
import com.telerobot.fs.utils.SipProfilesPortReader;
import com.telerobot.fs.utils.StringUtils;
import com.telerobot.fs.utils.SwitchRtpPortConfigReader;
import com.telerobot.fs.utils.ThreadUtil;
import link.thingscloud.freeswitch.esl.EslConnectionUtil;
import link.thingscloud.freeswitch.esl.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Use the firewalld service and FreeSWITCH's built-in ACL mechanism
 *  to ensure the security of the telephony system.
 * @author easycallcenter365
 */
public class SecurityManager {
    private static final Object SYNC_ROOT = new Object();
    private static final Logger logger = LoggerFactory.getLogger(SecurityManager.class);
    private static SecurityManager instance;
    private SecurityManager() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    logger.info("The worker thread for dynamically adding IP whitelist entries to the firewalld has started.");
                    while (true) {
                        try {
                            scheduledUpdateFirewall();
                        } catch (Throwable e) {
                            logger.error("SessionManager scheduledUpdateIptables error: {}, {}",
                                    e.toString(),
                                    CommonUtils.getStackTraceString(e.getStackTrace())
                            );
                        }
                        ThreadUtil.sleep(5000);
                    }
                }
            }).start();
    }

    public static SecurityManager getInstance() {
        if (instance == null) {
            synchronized (SYNC_ROOT) {
                if (instance == null) {
                    instance = new SecurityManager();
                }
            }
        }
        return instance;
    }

    private static final ArrayBlockingQueue<String> IP_ADDRESS = new ArrayBlockingQueue<>(1000);
    private static boolean hasNewIpAddr = false;
    /**
     *  Add the specified IP address to the firewall whitelist.
     * @param ip client ip
     */
    public void addClientIpToFirewallWhiteList(String ip){
        if(!IP_ADDRESS.contains(ip)) {
            synchronized (SYNC_ROOT) {
                if (!IP_ADDRESS.contains(ip)) {
                    IP_ADDRESS.add(ip);
                    hasNewIpAddr = true;
                    logger.info("Add the specified IP address to the firewall whitelist. ipAddress = {} .", ip);
                }
            }
        }
    }

    public void reloadFirewallConfig(){
        hasNewIpAddr = true;
    }

    /**
     * create freeSWITCH acl File
     * @param aclType inbound 、 register
     * @allIpList
     */
    private boolean createFsAclFile(String aclType, List<String> allIpList){
        String fsConfPath = SystemConfig.getValue("fs_conf_directory");
        String paramName = String.format("fs-%s-acl-enabled", aclType);
        if (Boolean.parseBoolean(SystemConfig.getValue(paramName, "true"))) {
            logger.info("{}=true, use freeSWITCH's built-in ACL mechanism to ensure the security.", paramName);
            String inboundAclFile = fsConfPath +  String.format("/autoload_configs/%s_acl.xml", aclType);
            StringBuilder aclItemList = new StringBuilder();
            aclItemList.append(String.format("<list name=\"%s_allow_list\" default=\"deny\">\n", aclType));
            for (String ip : allIpList) {
                aclItemList.append("  <node type=\"allow\" cidr=\"");
                aclItemList.append(ip.trim());
                aclItemList.append("/32\"/>\n");
            }
            aclItemList.append("</list>");
            return FileUtils.WriteFile(inboundAclFile, aclItemList.toString());
        }else{
            logger.info("{}=false, freeSWITCH's built-in ACL mechanism is disabled.", paramName);
        }
        return false;
    }

    /**
     *  Scheduled refresh of the firewall configuration file and restart of the firewall service.
     */
    private void scheduledUpdateFirewall() {
        if(hasNewIpAddr) {
            List<String> allIpAddress;
            synchronized (SYNC_ROOT) {
                 allIpAddress =  SessionManager.getInstance().getAllUserIpList();
                IP_ADDRESS.clear();
                IP_ADDRESS.addAll(allIpAddress);
                hasNewIpAddr = false;
            }

            String fsConfPath = SystemConfig.getValue("fs_conf_directory");
            List<Integer> fsPortList = SipProfilesPortReader.readSipPorts(fsConfPath + "/sip_profiles/");
            if(fsPortList.size() ==0 ){
                return;
            }

            List<Integer> rtpPortInfo = SwitchRtpPortConfigReader.load(fsConfPath + "/autoload_configs/switch.conf.xml");
            if(rtpPortInfo.size() != 2){
                return;
            }

            // get inbound white ip list
            List<String> inboundAllowIpList = new ArrayList<>(10);
            String whiteIPList = SystemConfig.getValue("fs-inbound-allow-ip-list", "");
            if(!StringUtils.isNullOrEmpty(whiteIPList)){
                String[] array = whiteIPList.split("\\r\\n");
                for (String s : array) {
                    inboundAllowIpList.add(s.trim());
                }
            }
            allIpAddress.addAll(inboundAllowIpList);

            // get inbound white ip list
            List<String> registerAllowIpList = new ArrayList<>(10);
            whiteIPList = SystemConfig.getValue("fs-register-allow-ip-list", "");
            if(!StringUtils.isNullOrEmpty(whiteIPList)){
                String[] array = whiteIPList.split("\\r\\n");
                for (String s : array) {
                    registerAllowIpList.add(s.trim());
                }
            }

            if(allIpAddress.size() > 0 || inboundAllowIpList.size() > 0 ||  registerAllowIpList.size() > 0 ) {
                if (Boolean.parseBoolean(SystemConfig.getValue("firewalld-enabled", "true"))) {
                    logger.info("firewalld-enabled=true, use system firewalld mechanism to ensure the security.");
                    StringBuilder sb = new StringBuilder();
                    sb.append("<!-- The following rules is generated by easycallcenter365. Dot not edit it manually. --> \n ");
                    int wsPort = Integer.parseInt(SystemConfig.getValue("ws-server-port", "1081"));
                    sb.append(String.format("   <port port=\"%s\" protocol=\"tcp\"/>\n", String.valueOf(wsPort)));
                    sb.append("   <service name=\"ssh\"/>\n");
                    sb.append("   <service name=\"dhcpv6-client\"/>\n");
                    for (String ip : allIpAddress) {
                        for (int port : fsPortList) {
                            sb.append("   <rule family=\"ipv4\">\n");
                            sb.append("     <source address=\"");
                            sb.append(ip);
                            sb.append("\"/>\n");
                            sb.append("     <port port=\"");
                            sb.append(port);
                            sb.append("\" protocol=\"udp\"/>\n");
                            sb.append("     <accept/>\n");
                            sb.append("   </rule>\n");
                        }

                        // append rtp-start-port and rtp-end-port
                        sb.append("   <rule family=\"ipv4\">\n");
                        sb.append("     <source address=\"");
                        sb.append(ip);
                        sb.append("\"/>\n");
                        sb.append("     <port port=\"");
                        sb.append(rtpPortInfo.get(0));
                        sb.append("-");
                        sb.append(rtpPortInfo.get(1));
                        sb.append("\" protocol=\"udp\"/>\n");
                        sb.append("     <accept/>\n");
                        sb.append("   </rule>\n");
                    }

                    String firewallTemplate = fsConfPath + "/autoload_configs/firewalld-template.xml";
                    String firewallConfig = SystemConfig.getValue("firewalld-config-path", "/etc/firewalld/zones/public.xml");
                    String toReplacer = "<!--${office_ip_rule_list}-->";
                    String fileContent = FileUtils.ReadFile(firewallTemplate, "utf-8");
                    if (StringUtils.isNullOrEmpty(fileContent)) {
                        logger.error("Unable to read the firewall template file :{}", firewallTemplate);
                        return;
                    }
                    String writeContent = fileContent.replace(toReplacer, sb.toString());
                    boolean success = FileUtils.WriteFile(firewallConfig, writeContent);
                    if (success) {
                        logger.info("Successfully updated the firewall configuration file. Preparing to restart the firewall service.");
                        String restartCmd = SystemConfig.getValue("firewalld-restart-cmd", "/usr/bin/systemctl  restart firewalld");
                        String response = CommonUtils.execSystemCommand(restartCmd);
                        if (response.contains("Failed")) {
                            logger.error("Got restart firewalld response：{}", response);
                        } else {
                            logger.info("Got restart firewalld response：{}", response);
                        }
                    }
                }else{
                    logger.info("firewalld-enabled=false, system firewall is disabled.");
                }


                boolean createInboundAclFileOk = createFsAclFile("inbound", allIpAddress);
                allIpAddress.removeAll(inboundAllowIpList);

                allIpAddress.addAll(registerAllowIpList);
                boolean createRegisterAclFileOk = createFsAclFile("register", allIpAddress);
                if(createInboundAclFileOk || createRegisterAclFileOk) {
                    logger.info("Preparing to reload freeSWITCH configs.");
                    EslMessage response = EslConnectionUtil.sendSyncApiCommand("reloadacl", "");
                    logger.info("reloadacl response: {}", JSON.toJSONString(response));
                }
            }
        }
    }

}
