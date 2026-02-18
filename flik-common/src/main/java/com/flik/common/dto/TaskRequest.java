package com.flik.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TaskRequest {

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("taskType")
    private String taskType;

    @JsonProperty("priority")
    private int priority;

    @JsonProperty("payload")
    private Object payload;

    @JsonProperty("region")
    private String region;

    @JsonProperty("dagId")
    private String dagId;

    @JsonProperty("parentTaskId")
    private String parentTaskId;

    public TaskRequest() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getDagId() { return dagId; }
    public void setDagId(String dagId) { this.dagId = dagId; }
    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }
}
