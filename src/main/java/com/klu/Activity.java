package com.klu;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "activity",
        indexes = {
                @Index(name = "idx_event_date", columnList = "eventDate"),
                @Index(name = "idx_state", columnList = "state"),
                @Index(name = "idx_event_category", columnList = "eventCategory"),
                @Index(name = "idx_activity_type", columnList = "activityType")
        })
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String state;
    private String stationName;
    private String activityType;
    private String eventCategory;
    private String participantCategory;

    @Column(length = 1000)
    private String eventDescription;

    private String schoolOrCollegeOrPanchayatName;
    private String eventLocation;
    private LocalDate eventDate;
    private int numberOfParticipants;

    @Column(length = 1000)
    private String remarks;

    private Integer orderIndex = 0;

    // Default constructor for JSON serialization
    public Activity() {}

    // Constructor for findByDateRangeWithFilters query
    public Activity(Long id, String state, String stationName, String activityType, String eventCategory,
                    String participantCategory, String eventDescription, String schoolOrCollegeOrPanchayatName,
                    String eventLocation, LocalDate eventDate, int numberOfParticipants, String remarks, Integer orderIndex) {
        this.id = id;
        this.state = state;
        this.stationName = stationName;
        this.activityType = activityType;
        this.eventCategory = eventCategory;
        this.participantCategory = participantCategory;
        this.eventDescription = eventDescription;
        this.schoolOrCollegeOrPanchayatName = schoolOrCollegeOrPanchayatName;
        this.eventLocation = eventLocation;
        this.eventDate = eventDate;
        this.numberOfParticipants = numberOfParticipants;
        this.remarks = remarks;
        this.orderIndex = orderIndex;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getActivityType() {
        return activityType;
    }

    public void setActivityType(String activityType) {
        this.activityType = activityType;
    }

    public String getEventCategory() {
        return eventCategory;
    }

    public void setEventCategory(String eventCategory) {
        this.eventCategory = eventCategory;
    }

    public String getParticipantCategory() {
        return participantCategory;
    }

    public void setParticipantCategory(String participantCategory) {
        this.participantCategory = participantCategory;
    }

    public String getEventDescription() {
        return eventDescription;
    }

    public void setEventDescription(String eventDescription) {
        this.eventDescription = eventDescription;
    }

    public String getSchoolOrCollegeOrPanchayatName() {
        return schoolOrCollegeOrPanchayatName;
    }

    public void setSchoolOrCollegeOrPanchayatName(String schoolOrCollegeOrPanchayatName) {
        this.schoolOrCollegeOrPanchayatName = schoolOrCollegeOrPanchayatName;
    }

    public String getEventLocation() {
        return eventLocation;
    }

    public void setEventLocation(String eventLocation) {
        this.eventLocation = eventLocation;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public int getNumberOfParticipants() {
        return numberOfParticipants;
    }

    public void setNumberOfParticipants(int numberOfParticipants) {
        this.numberOfParticipants = numberOfParticipants;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(Integer orderIndex) {
        this.orderIndex = orderIndex;
    }
}