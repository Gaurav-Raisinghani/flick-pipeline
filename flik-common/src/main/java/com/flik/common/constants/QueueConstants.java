package com.flik.common.constants;

public final class QueueConstants {

    private QueueConstants() {}

    public static final String TASK_EXCHANGE = "flik.tasks";
    public static final String RETRY_EXCHANGE_5S = "flik.retry.5s";
    public static final String RETRY_EXCHANGE_15S = "flik.retry.15s";
    public static final String RETRY_EXCHANGE_60S = "flik.retry.60s";
    public static final String DLQ_EXCHANGE = "flik.dlq";

    public static final String QUEUE_P0 = "flik.tasks.p0";
    public static final String QUEUE_P1 = "flik.tasks.p1";
    public static final String QUEUE_P2 = "flik.tasks.p2";

    public static final String ROUTING_KEY_P0 = "task.p0";
    public static final String ROUTING_KEY_P1 = "task.p1";
    public static final String ROUTING_KEY_P2 = "task.p2";

    public static final String RETRY_QUEUE_5S = "flik.retry.wait-5s";
    public static final String RETRY_QUEUE_15S = "flik.retry.wait-15s";
    public static final String RETRY_QUEUE_60S = "flik.retry.wait-60s";

    public static final String DEAD_LETTER_QUEUE = "flik.dead-letter";

    public static final int MAX_PRIORITY = 10;
    public static final int MAX_RETRY_COUNT = 3;

    public static String routingKeyForPriority(int priority) {
        return switch (priority) {
            case 0 -> ROUTING_KEY_P0;
            case 1 -> ROUTING_KEY_P1;
            case 2 -> ROUTING_KEY_P2;
            default -> ROUTING_KEY_P2;
        };
    }

    public static String queueForPriority(int priority) {
        return switch (priority) {
            case 0 -> QUEUE_P0;
            case 1 -> QUEUE_P1;
            case 2 -> QUEUE_P2;
            default -> QUEUE_P2;
        };
    }
}
