package com.learntrack.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Represents a planned or executed study session in the daily schedule.
 * Plan Section 3.
 */
public class DailyLogEntry {
    private Integer logId;
    private LocalDate logDate;
    private String itemType; // 'TOPIC_LEARN' | 'TOPIC_REVISE' | 'SKILL' | 'PYQ_PRACTICE'
    private int itemId;      // topicId / skillId, or examId for PYQ_PRACTICE
    private LocalTime slotStart;
    private LocalTime slotEnd;
    private String status = "PENDING"; // 'PENDING' | 'DONE' | 'CONTINUING' | 'MISSED'

    public DailyLogEntry() {
    }

    public DailyLogEntry(Integer logId, LocalDate logDate, String itemType, int itemId,
                         LocalTime slotStart, LocalTime slotEnd, String status) {
        this.logId = logId;
        this.logDate = logDate;
        this.itemType = itemType;
        this.itemId = itemId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.status = status != null ? status : "PENDING";
    }

    public Integer getLogId() {
        return logId;
    }

    public void setLogId(Integer logId) {
        this.logId = logId;
    }

    public LocalDate getLogDate() {
        return logDate;
    }

    public void setLogDate(LocalDate logDate) {
        this.logDate = logDate;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public LocalTime getSlotStart() {
        return slotStart;
    }

    public void setSlotStart(LocalTime slotStart) {
        this.slotStart = slotStart;
    }

    public LocalTime getSlotEnd() {
        return slotEnd;
    }

    public void setSlotEnd(LocalTime slotEnd) {
        this.slotEnd = slotEnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getDurationHours() {
        if (slotStart == null || slotEnd == null) {
            return 0.0;
        }
        long minutes = java.time.Duration.between(slotStart, slotEnd).toMinutes();
        return Math.max(0.0, minutes / 60.0);
    }
}
