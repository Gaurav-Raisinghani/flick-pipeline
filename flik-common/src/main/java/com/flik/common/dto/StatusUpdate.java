package com.flik.common.dto;

import java.time.Instant;
import java.util.UUID;

public class StatusUpdate {

    private UUID taskId;
    private String status;
    private Object result;
    private String errorMessage;
    private Instant timestamp;

    public StatusUpdate() {}

    public StatusUpdate(UUID taskId, String status) {
        this.taskId = taskId;
        this.status = status;
        this.timestamp = Instant.now();
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
