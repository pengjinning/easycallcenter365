package com.telerobot.fs.entity.po;

import lombok.Data;

/**
 *  System license info
 */
@Data
public class LicenseInfo {
    private String modules;
    private int concurrency;
    private long expireDate;
    private long updateTime;
}
