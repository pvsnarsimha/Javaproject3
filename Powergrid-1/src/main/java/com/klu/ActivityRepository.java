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
    // Custom query to retrieve activities by state and event category
    @Query("SELECT a FROM Activity a WHERE a.state = :state AND a.eventCategory = :eventCategory")
    List<Activity> findByStateAndEventCategory(@Param("state") String state, @Param("eventCategory") String eventCategory);

    // Custom query to retrieve activities by date range
    @Query("SELECT a FROM Activity a WHERE a.eventDate BETWEEN :startDate AND :endDate")
    List<Activity> findByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}