package com.telerobot.fs.entity.pojo;


/**
 * 座席状态枚举
 * @author easycallcenter365@gmail.com
 */
public enum AgentStatus {

    /**
     *  刚刚上线，准备就绪中;
     */
    justLogin("justLogin", "已签入", 1),

    /**
     * 空闲
     */
    free("free", "置闲", 2),

    /**
     *  手动置忙
     */
    busy("busy", "置忙", 3),

    /**
     *  小休
     */
    busy_rest("busy_rest", "小休", 31),

    /**
     *  会议
     */
    busy_meeting("busy_meeting","会议", 32),

    /**
     *  培训
     */
    busy_training("busy_training","培训", 33),

    /**
     * 通话中
     */
    incall("incall", "通话中", 4),

    /**
     * 事后处理，填写表单中
     */
    processing("fill_form","话后整理", 5),

    /**
     * 多方会议中
     */
    conference("conference","多方会议", 6),

    /**
     * 坐席预占(呼入来电锁定)
     */
    lockStatus("lockStatus", "预占", 4);



    /**
     *  状态描述
     */
    private String name;

    /**
     *  状态描述
     */
    private String text;


    /**
     * index
     */
    private int index;

    public  static AgentStatus getItemByValue(int index){
        AgentStatus[] items = AgentStatus.values();
        for(AgentStatus item : items){
            if(item.getIndex() == index){
                return item;
            }
        }
        return null;
    };

    private AgentStatus(String name, String text, int index) {
        this.name = name;
        this.index = index;
        this.text = text;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s", this.index,  this.name);
    }

    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    public int getIndex() {
        return index;
    }

} 
