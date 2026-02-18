package com.flik.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskResponse {

    private UUID taskId;
    private String tenantId;
    private String taskType;
    private int priority;
    private String status;
    private Object payload;
    private Object result;
    private int retryCount;
    private String errorMessage;
    private String region;
    private String dagId;
    private String parentTaskId;
    private double cost;
    private String storageTier;
    private String workerVersion;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public TaskResponse() {}

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getDagId() { return dagId; }
    public void setDagId(String dagId) { this.dagId = dagId; }
    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public String getStorageTier() { return storageTier; }
    public void setStorageTier(String storageTier) { this.storageTier = storageTier; }
    public String getWorkerVersion() { return workerVersion; }
    public void setWorkerVersion(String workerVersion) { this.workerVersion = workerVersion; }
}
