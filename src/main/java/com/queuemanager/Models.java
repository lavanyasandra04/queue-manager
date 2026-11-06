package com.queuemanager;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response objects. JSON field names match what the frontend expects. */
public final class Models {
    private Models() {}

    /** "waiting" is only filled in when listing queues. */
    public record QueueInfo(
            long id,
            String name,
            @JsonProperty("created_at") long createdAt,
            Long waiting) {}

    public record Token(
            long id,
            @JsonProperty("queue_id") long queueId,
            int number,
            String name,
            String status,
            int position,
            @JsonProperty("created_at") long createdAt,
            @JsonProperty("started_at") Long startedAt,
            @JsonProperty("completed_at") Long completedAt,
            @JsonProperty("cancelled_at") Long cancelledAt) {}

    public record TrendPoint(long t, int length) {}

    public record WaitPoint(int number, long waitMs) {}

    public record ArrivalPoint(long t, long count) {}
}
