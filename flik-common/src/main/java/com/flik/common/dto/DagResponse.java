package com.flik.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DagResponse {

    private UUID dagId;
    private String status;
    private List<TaskResponse> tasks;

    public DagResponse() {}

    public UUID getDagId() { return dagId; }
    public void setDagId(UUID dagId) { this.dagId = dagId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<TaskResponse> getTasks() { return tasks; }
    public void setTasks(List<TaskResponse> tasks) { this.tasks = tasks; }
}
