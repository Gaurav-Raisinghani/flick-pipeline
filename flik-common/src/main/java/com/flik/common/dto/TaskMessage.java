package com.flik.common.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public class TaskMessage implements Serializable {

    private UUID taskId;
    private String tenantId;
    private String taskType;
    private int priority;
    private String payload;
    private int retryCount;
    private String region;
    private UUID dagId;
    private UUID parentTaskId;
    private Instant createdAt;

    public TaskMessage() {}

    public TaskMessage(UUID taskId, String tenantId, String taskType, int priority, String payload) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.taskType = taskType;
        this.priority = priority;
        this.payload = payload;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public UUID getDagId() { return dagId; }
    public void setDagId(UUID dagId) { this.dagId = dagId; }
    public UUID getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(UUID parentTaskId) { this.parentTaskId = parentTaskId; }
}
