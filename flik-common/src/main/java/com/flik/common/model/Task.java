package com.flik.common.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private TaskType taskType;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(length = 32)
    private String region;

    @Column(name = "dag_id")
    private UUID dagId;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column
    private double cost;

    @Column(name = "storage_tier", length = 16)
    private String storageTier;

    @Column(name = "worker_version", length = 32)
    private String workerVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public Task() {}

    public Task(UUID id, String tenantId, TaskType taskType, int priority, String payload) {
        this.id = id;
        this.tenantId = tenantId;
        this.taskType = taskType;
        this.priority = priority;
        this.payload = payload;
        this.status = TaskStatus.PENDING;
        this.retryCount = 0;
        this.region = "us-east";
        this.storageTier = "HOT";
        this.cost = 0.0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public TaskType getTaskType() { return taskType; }
    public void setTaskType(TaskType taskType) { this.taskType = taskType; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
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
    public UUID getDagId() { return dagId; }
    public void setDagId(UUID dagId) { this.dagId = dagId; }
    public UUID getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(UUID parentTaskId) { this.parentTaskId = parentTaskId; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public String getStorageTier() { return storageTier; }
    public void setStorageTier(String storageTier) { this.storageTier = storageTier; }
    public String getWorkerVersion() { return workerVersion; }
    public void setWorkerVersion(String workerVersion) { this.workerVersion = workerVersion; }
}
