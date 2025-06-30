package com.telerobot.fs.entity.pojo;


/**
 * 座席状态枚举
 * @author easycallcenter365@gmail.com
 */
public enum AgentStatus {

    /**
     *  刚刚上线，准备就绪中;
     */
    justLogin("justLogin", 1),

    /**
     * 空闲
     */
    free("free", 2),

    /**
     *  手动置忙
     */
    busy("busy", 3),

    /**
     * 通话中
     */
    incall("incall", 4),

    /**
     * 事后处理，填写表单中
     */
    processing("fill_form", 5),

    /**
     * 会议中
     */
    conference("conference", 6);


    /**
     *  状态描述
     */
    private String name;
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

    private AgentStatus(String name, int index) {
        this.name = name;
        this.index = index;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s", this.index,  this.name);
    }

    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

} 
