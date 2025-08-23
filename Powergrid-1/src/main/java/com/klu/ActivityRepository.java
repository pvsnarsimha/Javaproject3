package com.klu;

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
}