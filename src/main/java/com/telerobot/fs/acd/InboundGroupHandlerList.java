package com.telerobot.fs.acd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
	private List<InboundGroupHandler> callHandlerList = new ArrayList<>(30);
	private InboundGroupHandler findHandlerByGroupId(String groupId) {
		InboundGroupHandler destHandler = null;
		for (int i = 0; i <= callHandlerList.size() - 1; i++) {
			try {
				InboundGroupHandler myHandler = callHandlerList.get(i);
				if (myHandler.getGroupId().equals(groupId)) {
					destHandler = myHandler;
					break;
				}
			} catch (Throwable ex) {
				log.error(ex.toString());
			}
		}
		return destHandler;
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
					this.callHandlerList.add(destHandler);
				}
			}
		}

		return destHandler;
	}

}
