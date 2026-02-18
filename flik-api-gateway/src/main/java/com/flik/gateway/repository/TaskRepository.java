package com.flik.gateway.repository;

import com.flik.common.model.Task;
import com.flik.common.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findByTenantIdAndStatus(String tenantId, TaskStatus status);
    List<Task> findByDagId(UUID dagId);
    List<Task> findByParentTaskIdAndStatus(UUID parentTaskId, TaskStatus status);
    List<Task> findByParentTaskId(UUID parentTaskId);

    @Modifying
    @Query("UPDATE Task t SET t.storageTier = :tier, t.updatedAt = :now WHERE t.storageTier = :fromTier AND t.completedAt < :before")
    int migrateStorageTier(String fromTier, String tier, Instant before, Instant now);

    @Query("SELECT t FROM Task t WHERE t.dagId = :dagId ORDER BY t.createdAt ASC")
    List<Task> findDagTasks(UUID dagId);
}
