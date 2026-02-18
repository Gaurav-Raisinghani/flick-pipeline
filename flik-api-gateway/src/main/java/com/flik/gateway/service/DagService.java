package com.flik.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flik.common.constants.QueueConstants;
import com.flik.common.dto.DagRequest;
import com.flik.common.dto.DagResponse;
import com.flik.common.dto.TaskMessage;
import com.flik.common.dto.TaskResponse;
import com.flik.common.model.Task;
import com.flik.common.model.TaskStatus;
import com.flik.common.model.TaskType;
import com.flik.gateway.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DagService {

    private static final Logger log = LoggerFactory.getLogger(DagService.class);

    private final TaskRepository taskRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public DagService(TaskRepository taskRepository, RabbitTemplate rabbitTemplate,
                      ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DagResponse submitDag(DagRequest request) {
        UUID dagId = UUID.randomUUID();
        List<DagRequest.DagStep> steps = request.getSteps();
        List<TaskResponse> taskResponses = new ArrayList<>();

        UUID previousTaskId = null;

        for (int i = 0; i < steps.size(); i++) {
            DagRequest.DagStep step = steps.get(i);
            UUID taskId = UUID.randomUUID();
            TaskType taskType = TaskType.valueOf(step.getTaskType().toUpperCase());

            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(step.getPayload());
            } catch (JsonProcessingException e) {
                payloadJson = "{}";
            }

            Task task = new Task(taskId, request.getTenantId(), taskType, request.getPriority(), payloadJson);
            task.setDagId(dagId);
            task.setRegion(request.getRegion() != null ? request.getRegion() : "us-east");

            if (i == 0) {
                task.setStatus(TaskStatus.QUEUED);
            } else {
                task.setParentTaskId(previousTaskId);
                task.setStatus(TaskStatus.PENDING);
            }

            taskRepository.save(task);

            if (i == 0) {
                enqueueTask(task, payloadJson, dagId);
            }

            TaskResponse resp = new TaskResponse();
            resp.setTaskId(taskId);
            resp.setStatus(task.getStatus().name());
            resp.setTaskType(taskType.name());
            resp.setDagId(dagId.toString());
            if (previousTaskId != null) {
                resp.setParentTaskId(previousTaskId.toString());
            }
            taskResponses.add(resp);

            previousTaskId = taskId;
        }

        log.info("DAG submitted: dagId={}, steps={}", dagId, steps.size());

        DagResponse response = new DagResponse();
        response.setDagId(dagId);
        response.setStatus("RUNNING");
        response.setTasks(taskResponses);
        return response;
    }

    @Transactional
    public void triggerNextStep(UUID completedTaskId) {
        List<Task> dependents = taskRepository.findByParentTaskId(completedTaskId);
        for (Task dependent : dependents) {
            if (dependent.getStatus() == TaskStatus.PENDING) {
                dependent.setStatus(TaskStatus.QUEUED);
                taskRepository.save(dependent);
                enqueueTask(dependent, dependent.getPayload(), dependent.getDagId());
                log.info("DAG next step triggered: dagId={}, taskId={}, type={}",
                        dependent.getDagId(), dependent.getId(), dependent.getTaskType());
            }
        }
    }

    public Optional<DagResponse> getDag(UUID dagId) {
        List<Task> tasks = taskRepository.findDagTasks(dagId);
        if (tasks.isEmpty()) return Optional.empty();

        DagResponse response = new DagResponse();
        response.setDagId(dagId);

        boolean allCompleted = tasks.stream().allMatch(t -> t.getStatus() == TaskStatus.COMPLETED);
        boolean anyFailed = tasks.stream().anyMatch(t ->
                t.getStatus() == TaskStatus.FAILED || t.getStatus() == TaskStatus.DEAD_LETTERED);
        boolean anyProcessing = tasks.stream().anyMatch(t ->
                t.getStatus() == TaskStatus.PROCESSING || t.getStatus() == TaskStatus.QUEUED);

        if (allCompleted) response.setStatus("COMPLETED");
        else if (anyFailed) response.setStatus("FAILED");
        else if (anyProcessing) response.setStatus("RUNNING");
        else response.setStatus("PENDING");

        List<TaskResponse> taskResponses = tasks.stream().map(t -> {
            TaskResponse tr = new TaskResponse();
            tr.setTaskId(t.getId());
            tr.setTaskType(t.getTaskType().name());
            tr.setStatus(t.getStatus().name());
            tr.setDagId(dagId.toString());
            if (t.getParentTaskId() != null) tr.setParentTaskId(t.getParentTaskId().toString());
            return tr;
        }).toList();

        response.setTasks(taskResponses);
        return Optional.of(response);
    }

    private void enqueueTask(Task task, String payloadJson, UUID dagId) {
        TaskMessage message = new TaskMessage(
                task.getId(), task.getTenantId(), task.getTaskType().name(),
                task.getPriority(), payloadJson);
        message.setDagId(dagId);
        message.setRegion(task.getRegion());

        String routingKey = QueueConstants.routingKeyForPriority(task.getPriority());

        rabbitTemplate.convertAndSend(QueueConstants.TASK_EXCHANGE, routingKey, message, msg -> {
            msg.getMessageProperties().setPriority(task.getPriority() == 0 ? 10 : task.getPriority() == 1 ? 5 : 1);
            msg.getMessageProperties().setHeader("x-retry-count", 0);
            return msg;
        });
    }
}
