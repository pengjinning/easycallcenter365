package com.telerobot.fs.acd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/***
 *  Stores call queue handling objects for all business groups.
 ***/
public class InboundGroupHandlerList {
    private final static Logger log = LoggerFactory.getLogger(InboundGroupHandlerList.class);
	private InboundGroupHandlerList() {
	}

	private static final InboundGroupHandlerList INSTANCE = new InboundGroupHandlerList();
	public static InboundGroupHandlerList getInstance() {
		return INSTANCE;
	}
	private ConcurrentHashMap<String, InboundGroupHandler>  callHandlerList= new ConcurrentHashMap<>(20);
	private InboundGroupHandler findHandlerByGroupId(String groupId) {
		 return callHandlerList.get(groupId.trim());
	}
	
	/***
	 * Find the inboundGroupHandler object based on the group-id
	 ***/
	public InboundGroupHandler getCallHandlerBySkillGroupId(String groupId) {
 		InboundGroupHandler destHandler = findHandlerByGroupId(groupId);

		if (destHandler  == null) {
			synchronized (this) {
				destHandler = findHandlerByGroupId(groupId);
				if (destHandler == null) {
					destHandler = new InboundGroupHandler(groupId);
					this.callHandlerList.put(groupId.trim(),  destHandler);
				}
			}
		}

		return destHandler;
	}

}
