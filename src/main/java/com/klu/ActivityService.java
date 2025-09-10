package com.klu;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ActivityService {
    List<Activity> getAllActivities();
    Page<Activity> getAllActivities(String search, String state, String category, String dateRange, String sortBy, String sortDir, Pageable pageable);
    void save(Activity activity, MultipartFile[] files);
    Activity getById(Long id);
    void deleteById(Long id);
    void deleteActivities(List<Long> ids);
    List<FileMetadata> getFilesByActivityId(Long activityId);
    void deleteFile(Long fileId);
    List<Activity> getActivitiesByStateAndEventCategory(String state, String eventCategory, String operator);
    List<Activity> getActivitiesByDateRange(LocalDate startDate, LocalDate endDate);
    void updateField(Long id, String field, Object value);
    void bulkUpdate(List<Long> ids, Map<String, Object> updates);
    Map<String, Object> getDashboardStats(LocalDate startDate, LocalDate endDate);
    void reorderActivities(List<Map<String, Long>> orderList);
    Map<String, Object> performCustomCalculation(String column, String formula);
    Map<String, Object> getSummary();
    long getTotalParticipants(String search, String state, String category, String dateRange);
    List<String> getDistinctStates();
    List<String> getDistinctActivityTypes();
    List<String> getDistinctEventCategories();
    Map<String, Object> performDynamicCalculation(List<Map<String, String>> aggregates, List<String> groupBy, List<Map<String, String>> conditions);
    Map<String, Object> getAiSuggestions();
    CompletableFuture<Map<String, Object>> getActivitiesByDateRangeWithFilters(LocalDate startDate, LocalDate endDate, String state, String activityType, String eventCategory, String filterOperator, List<Map<String, String>> conditions);
    // new imp: Method for batch query execution
    CompletableFuture<List<Map<String, Object>>> executeBatchQueries(List<Map<String, String>> queries);
    
}