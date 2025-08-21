package com.klu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

@Entity
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

    // Default constructor for JSON serialization
    public Activity() {}

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
}