package com.telerobot.fs.config;

/**
 * Status code for sip call sessions.
 * @author  easycallcenter365
 */
public class SipSessionStatusCode {

    // 1xx - temp response

    public static final int TRYING = 100;
    public static final int RINGING = 180;
    public static final int SESSION_PROGRESS = 183;

    public static final int OK = 200;

    // 3xx - redirect response

    public static final int MOVED_TEMPORARILY = 302;
    public static final int USE_PROXY = 305;

    // 4xx - sip client error

    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;


    public static final int USER_BUSY = 486;
    public static final int REQUEST_TERMINATED = 487;
    public static final int NOT_ACCEPTABLE_HERE = 488;


    // 5xx - sip server error

    public static final int SERVER_INTERNAL_ERROR = 500;
    public static final int SERVICE_UNAVAILABLE = 503;

    // 6xx - global error

    public static final int BUSY_EVERYWHERE = 600;
    public static final int DECLINE = 603;
    public static final int DOES_NOT_EXIST_ANYWHERE = 604;
}