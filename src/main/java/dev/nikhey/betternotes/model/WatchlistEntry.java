package dev.nikhey.betternotes.model;

import java.util.UUID;

public record WatchlistEntry(
        UUID targetUuid,
        String targetName,
        long time,
        UUID authorUuid,
        String authorName,
        String reason,
        long expireTime) {

    public boolean expired(long now) {
        return expireTime > 0 && expireTime <= now;
    }
}
