CREATE TABLE IF NOT EXISTS tasks (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    task_type       VARCHAR(32) NOT NULL,
    priority        INT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    payload         JSONB NOT NULL,
    result          JSONB,
    retry_count     INT DEFAULT 0,
    error_message   TEXT,
    region          VARCHAR(32) DEFAULT 'us-east',
    dag_id          UUID,
    parent_task_id  UUID,
    cost            DOUBLE PRECISION DEFAULT 0.0,
    storage_tier    VARCHAR(16) DEFAULT 'HOT',
    worker_version  VARCHAR(32),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tasks_tenant_status ON tasks(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_dag_id ON tasks(dag_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent_id ON tasks(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_tasks_storage_tier ON tasks(storage_tier);
