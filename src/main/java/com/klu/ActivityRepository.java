package com.klu;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long>, JpaSpecificationExecutor<Activity> {
    @Query("SELECT a FROM Activity a WHERE " +
           "(:operator = 'AND' AND a.state = :state AND a.eventCategory = :eventCategory) OR " +
           "(:operator = 'OR' AND (a.state = :state OR a.eventCategory = :eventCategory)) OR " +
           "(:operator = 'NOT' AND a.state != :state AND a.eventCategory != :eventCategory)")
    List<Activity> findByStateAndEventCategoryWithOperator(@Param("state") String state, 
                                                          @Param("eventCategory") String eventCategory, 
                                                          @Param("operator") String operator);

    @Query("SELECT a FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate")
    List<Activity> findByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(DISTINCT TRIM(a.state)) FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate AND a.state IS NOT NULL AND a.state != ''")
    Long countUniqueStates(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(a.numberOfParticipants) FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate")
    Long sumParticipants(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(a) FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate")
    Long countActivities(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT a.eventCategory, COUNT(a) as count FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate GROUP BY a.eventCategory")
    List<Object[]> countByCategory(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT a.eventDate, COUNT(a) as count FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate GROUP BY a.eventDate")
    List<Object[]> countByDate(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT DISTINCT TRIM(a.state) FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate AND a.state IS NOT NULL AND a.state != ''")
    List<String> findDistinctStates(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT DISTINCT TRIM(a.state) FROM Activity a WHERE a.state IS NOT NULL AND a.state != ''")
    List<String> findDistinctStates();

    @Query("SELECT DISTINCT TRIM(a.activityType) FROM Activity a WHERE a.activityType IS NOT NULL AND a.activityType != ''")
    List<String> findDistinctActivityTypes();

    @Query("SELECT DISTINCT TRIM(a.eventCategory) FROM Activity a WHERE a.eventCategory IS NOT NULL AND a.eventCategory != ''")
    List<String> findDistinctEventCategories();

    @Query("SELECT new com.klu.Activity(a.id, a.state, a.stationName, a.activityType, a.eventCategory, a.participantCategory, a.eventDescription, a.schoolOrCollegeOrPanchayatName, a.eventLocation, a.eventDate, a.numberOfParticipants, a.remarks, a.orderIndex) " +
           "FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate " +
           "AND (:state IS NULL OR a.state = :state) " +
           "AND (:activityType IS NULL OR a.activityType = :activityType) " +
           "AND (:eventCategory IS NULL OR a.eventCategory = :eventCategory) " +
           "ORDER BY a.orderIndex ASC")
    List<Activity> findByDateRangeWithFilters(@Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate,
                                             @Param("state") String state,
                                             @Param("activityType") String activityType,
                                             @Param("eventCategory") String eventCategory);
    
}