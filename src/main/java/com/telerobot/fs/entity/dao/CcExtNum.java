package com.telerobot.fs.entity.dao;

public class CcExtNum {
    /**
     * 流水编号
     */
    private Integer extId;
    /**
     * 分机号
     */
    private String extNum;
    /**
     * 分机密码
     */
    private String extPass;
    /**
     * 所属员工/绑定关系
     */
    private String userCode;

    public Integer getExtId() {
        return extId;
    }

    public void setExtId(Integer extId) {
        this.extId = extId;
    }

    public String getExtNum() {
        return extNum;
    }

    public void setExtNum(String extNum) {
        this.extNum = extNum;
    }

    public String getExtPass() {
        return extPass;
    }

    public void setExtPass(String extPass) {
        this.extPass = extPass;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }
}