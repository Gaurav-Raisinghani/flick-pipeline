package com.flik.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class DagRequest {

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("priority")
    private int priority;

    @JsonProperty("region")
    private String region;

    @JsonProperty("steps")
    private List<DagStep> steps;

    public DagRequest() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public List<DagStep> getSteps() { return steps; }
    public void setSteps(List<DagStep> steps) { this.steps = steps; }

    public static class DagStep {
        @JsonProperty("taskType")
        private String taskType;

        @JsonProperty("payload")
        private Object payload;

        public DagStep() {}

        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }
    }
}
