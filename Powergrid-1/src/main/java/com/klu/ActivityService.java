package com.klu;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

public interface ActivityService {
    List<Activity> getAllActivities();
    Page<Activity> getAllActivities(String search, String state, String category, String dateRange, String sortBy, String sortDir, Pageable pageable);
    void save(Activity activity, MultipartFile[] files);
    Activity getById(Long id);
    void deleteById(Long id);
    void deleteActivities(List<Long> ids);
    List<String> getSearchSuggestions(String term);
    List<FileMetadata> getFilesByActivityId(Long activityId);
    void deleteFile(Long fileId);
    List<Activity> getActivitiesByStateAndEventCategory(String state, String eventCategory);
    List<Activity> getActivitiesByDateRange(LocalDate startDate, LocalDate endDate);
}