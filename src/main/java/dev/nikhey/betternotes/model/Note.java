package dev.nikhey.betternotes.model;

import java.util.UUID;

public record Note(
        long id,
        long time,
        UUID targetUuid,
        String targetName,
        UUID authorUuid,
        String authorName,
        Severity severity,
        boolean pinned,
        String text,
        long deletedTime,
        UUID deletedByUuid,
        String deletedByName) {

    public boolean deleted() {
        return deletedTime > 0;
    }
}
