package com.learntrack.model;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Represents the wake-up and sleep routine for a day of the week in a semester.
 * User Guide §1 & Implementation Plan extension.
 */
public class WeekdayRoutine {

    private int semesterId;
    private String dayOfWeek;
    private LocalTime wakeTime;
    private LocalTime sleepTime;

    public WeekdayRoutine() {
    }

    public WeekdayRoutine(int semesterId, String dayOfWeek, LocalTime wakeTime, LocalTime sleepTime) {
        this.semesterId = semesterId;
        this.dayOfWeek = dayOfWeek != null ? dayOfWeek.toUpperCase() : null;
        this.wakeTime = wakeTime;
        this.sleepTime = sleepTime;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(String dayOfWeek) {
        this.dayOfWeek = dayOfWeek != null ? dayOfWeek.toUpperCase() : null;
    }

    public LocalTime getWakeTime() {
        return wakeTime;
    }

    public void setWakeTime(LocalTime wakeTime) {
        this.wakeTime = wakeTime;
    }

    public LocalTime getSleepTime() {
        return sleepTime;
    }

    public void setSleepTime(LocalTime sleepTime) {
        this.sleepTime = sleepTime;
    }

    /**
     * Sleep time at or before wake time means sleep occurs after midnight (e.g. wake 07:00, sleep 01:00).
     */
    public boolean isSleepAfterMidnight() {
        if (wakeTime == null || sleepTime == null) {
            return false;
        }
        return !sleepTime.isAfter(wakeTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WeekdayRoutine that = (WeekdayRoutine) o;
        return semesterId == that.semesterId &&
                Objects.equals(dayOfWeek, that.dayOfWeek) &&
                Objects.equals(wakeTime, that.wakeTime) &&
                Objects.equals(sleepTime, that.sleepTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(semesterId, dayOfWeek, wakeTime, sleepTime);
    }

    @Override
    public String toString() {
        return "WeekdayRoutine{" +
                "semesterId=" + semesterId +
                ", dayOfWeek='" + dayOfWeek + '\'' +
                ", wakeTime=" + wakeTime +
                ", sleepTime=" + sleepTime +
                '}';
    }
}
