package com.klu;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.HashMap;
import jakarta.persistence.EntityManager; // new updates
import jakarta.persistence.criteria.CriteriaQuery; // new updates
import jakarta.persistence.criteria.Expression;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
@Service
public class ActivityServiceImpl implements ActivityService {

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private FileStorageService fileStorageService;

    private final PolicyFactory sanitizer = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS);
    
    // new updates
    @Autowired
    private EntityManager entityManager;
    @Override
    public List<Activity> getAllActivities() {
        return activityRepository.findAll();
    }

    @Override
    public Page<Activity> getAllActivities(String search, String state, String category, String dateRange, String sortBy, String sortDir, Pageable pageable) {
        Sort sort = Sort.by(sortDir.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        return activityRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!search.isEmpty()) {
                String searchLower = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("state")), searchLower),
                        cb.like(cb.lower(root.get("eventCategory")), searchLower),
                        cb.like(cb.lower(root.get("eventDescription")), searchLower)
                ));
            }
            if (!state.isEmpty()) {
                predicates.add(root.get("state").in(Arrays.asList(state.split(","))));
            }
            if (!category.isEmpty()) {
                predicates.add(root.get("eventCategory").in(Arrays.asList(category.split(","))));
            }
            if (!dateRange.isEmpty()) {
                String[] dates = dateRange.split(" to ");
                if (dates.length == 2) {
                    try {
                        LocalDate start = LocalDate.parse(dates[0]);
                        LocalDate end = LocalDate.parse(dates[1]);
                        predicates.add(cb.between(root.get("eventDate"), start, end));
                    } catch (Exception e) {
                        System.err.println("Invalid date range format: " + dateRange);
                    }
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, sortedPageable);
    }

    @Override
    public void save(Activity activity, MultipartFile[] files) {
        if (Objects.nonNull(activity)) {
            if (activity.getEventDescription() != null) {
                activity.setEventDescription(sanitizer.sanitize(activity.getEventDescription()));
            }
            if (activity.getRemarks() != null) {
                activity.setRemarks(sanitizer.sanitize(activity.getRemarks()));
            }
            if (activity.getNumberOfParticipants() < 1 || activity.getNumberOfParticipants() > 1000) {
                throw new IllegalArgumentException("Number of participants must be between 1 and 1000");
            }
            LocalDate startDate = LocalDate.of(2024, 10, 28);
            LocalDate endDate = LocalDate.of(2024, 11, 3);
            if (activity.getEventDate() != null && 
                (activity.getEventDate().isBefore(startDate) || activity.getEventDate().isAfter(endDate))) {
                throw new IllegalArgumentException("Event date must be between 2024-10-28 and 2024-11-03");
            }
            Activity savedActivity = activityRepository.save(activity);

            if (files != null && files.length > 0) {
                try {
                    String[] filePaths = fileStorageService.storeFiles(files, savedActivity.getId());
                    for (int i = 0; i < files.length; i++) {
                        MultipartFile file = files[i];
                        if (file != null && !file.isEmpty()) {
                            FileMetadata fileMetadata = new FileMetadata(
                                file.getOriginalFilename(),
                                filePaths[i],
                                file.getContentType(),
                                file.getSize(),
                                savedActivity
                            );
                            fileMetadataRepository.save(fileMetadata);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to store files: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void updateField(Long id, String field, Object value) {
        Optional<Activity> optionalActivity = activityRepository.findById(id);
        if (!optionalActivity.isPresent()) {
            throw new IllegalArgumentException("Activity not found with id: " + id);
        }
        Activity activity = optionalActivity.get();
        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);

        switch (field) {
            case "state":
                String state = (String) value;
                if (state == null || state.trim().isEmpty()) {
                    throw new IllegalArgumentException("State cannot be empty");
                }
                activity.setState(sanitizer.sanitize(state));
                break;
            case "stationName":
                String stationName = (String) value;
                if (stationName == null || stationName.trim().isEmpty()) {
                    throw new IllegalArgumentException("Station Name cannot be empty");
                }
                activity.setStationName(sanitizer.sanitize(stationName));
                break;
            case "activityType":
                String activityType = (String) value;
                if (activityType == null || activityType.trim().isEmpty()) {
                    throw new IllegalArgumentException("Activity Type cannot be empty");
                }
                activity.setActivityType(sanitizer.sanitize(activityType));
                break;
            case "eventCategory":
                String eventCategory = (String) value;
                if (eventCategory == null || eventCategory.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Category cannot be empty");
                }
                activity.setEventCategory(sanitizer.sanitize(eventCategory));
                break;
            case "participantCategory":
                String participantCategory = (String) value;
                if (participantCategory == null || participantCategory.trim().isEmpty()) {
                    throw new IllegalArgumentException("Participant Category cannot be empty");
                }
                activity.setParticipantCategory(sanitizer.sanitize(participantCategory));
                break;
            case "eventDescription":
                String eventDescription = (String) value;
                if (eventDescription == null || eventDescription.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Description cannot be empty");
                }
                activity.setEventDescription(sanitizer.sanitize(eventDescription));
                break;
            case "schoolOrCollegeOrPanchayatName":
                String schoolOrCollegeOrPanchayatName = (String) value;
                if (schoolOrCollegeOrPanchayatName == null || schoolOrCollegeOrPanchayatName.trim().isEmpty()) {
                    throw new IllegalArgumentException("School/College/Panchayat Name cannot be empty");
                }
                activity.setSchoolOrCollegeOrPanchayatName(sanitizer.sanitize(schoolOrCollegeOrPanchayatName));
                break;
            case "eventLocation":
                String eventLocation = (String) value;
                if (eventLocation == null || eventLocation.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Location cannot be empty");
                }
                activity.setEventLocation(sanitizer.sanitize(eventLocation));
                break;
            case "eventDate":
                String eventDateStr = value instanceof String ? (String) value : value.toString();
                try {
                    LocalDate eventDate = LocalDate.parse(eventDateStr);
                    if (eventDate.isBefore(startDate) || eventDate.isAfter(endDate)) {
                        throw new IllegalArgumentException("Event date must be between 2024-10-28 and 2024-11-03");
                    }
                    activity.setEventDate(eventDate);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid date format for eventDate: " + eventDateStr);
                }
                break;
            case "numberOfParticipants":
                int numberOfParticipants;
                try {
                    numberOfParticipants = value instanceof Integer ? (Integer) value : Integer.parseInt(value.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Number of participants must be a valid number");
                }
                if (numberOfParticipants < 1 || numberOfParticipants > 1000) {
                    throw new IllegalArgumentException("Number of participants must be between 1 and 1000");
                }
                activity.setNumberOfParticipants(numberOfParticipants);
                break;
            case "remarks":
                String remarks = (String) value;
                if (remarks == null || remarks.trim().isEmpty()) {
                    throw new IllegalArgumentException("Remarks cannot be empty");
                }
                activity.setRemarks(sanitizer.sanitize(remarks));
                break;
            default:
                throw new IllegalArgumentException("Invalid field: " + field);
        }
        activityRepository.save(activity);
    }

    @Override
    public void bulkUpdate(List<Long> ids, Map<String, Object> updates) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("No activities selected for update");
        }
        List<Activity> activities = activityRepository.findAllById(ids);
        if (activities.size() != ids.size()) {
            throw new IllegalArgumentException("Some activities could not be found");
        }

        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);
        for (Activity activity : activities) {
            if (updates.containsKey("state") && updates.get("state") != null) {
                String state = (String) updates.get("state");
                if (state.trim().isEmpty()) {
                    throw new IllegalArgumentException("State cannot be empty if provided");
                }
                activity.setState(sanitizer.sanitize(state));
            }
            if (updates.containsKey("stationName") && updates.get("stationName") != null) {
                String stationName = (String) updates.get("stationName");
                if (stationName.trim().isEmpty()) {
                    throw new IllegalArgumentException("Station Name cannot be empty if provided");
                }
                activity.setStationName(sanitizer.sanitize(stationName));
            }
            if (updates.containsKey("activityType") && updates.get("activityType") != null) {
                String activityType = (String) updates.get("activityType");
                if (activityType.trim().isEmpty()) {
                    throw new IllegalArgumentException("Activity Type cannot be empty if provided");
                }
                activity.setActivityType(sanitizer.sanitize(activityType));
            }
            if (updates.containsKey("eventCategory") && updates.get("eventCategory") != null) {
                String eventCategory = (String) updates.get("eventCategory");
                if (eventCategory.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Category cannot be empty if provided");
                }
                activity.setEventCategory(sanitizer.sanitize(eventCategory));
            }
            if (updates.containsKey("participantCategory") && updates.get("participantCategory") != null) {
                String participantCategory = (String) updates.get("participantCategory");
                if (participantCategory.trim().isEmpty()) {
                    throw new IllegalArgumentException("Participant Category cannot be empty if provided");
                }
                activity.setParticipantCategory(sanitizer.sanitize(participantCategory));
            }
            if (updates.containsKey("eventDescription") && updates.get("eventDescription") != null) {
                String eventDescription = (String) updates.get("eventDescription");
                if (eventDescription.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Description cannot be empty if provided");
                }
                activity.setEventDescription(sanitizer.sanitize(eventDescription));
            }
            if (updates.containsKey("schoolOrCollegeOrPanchayatName") && updates.get("schoolOrCollegeOrPanchayatName") != null) {
                String schoolOrCollegeOrPanchayatName = (String) updates.get("schoolOrCollegeOrPanchayatName");
                if (schoolOrCollegeOrPanchayatName.trim().isEmpty()) {
                    throw new IllegalArgumentException("School/College/Panchayat Name cannot be empty if provided");
                }
                activity.setSchoolOrCollegeOrPanchayatName(sanitizer.sanitize(schoolOrCollegeOrPanchayatName));
            }
            if (updates.containsKey("eventLocation") && updates.get("eventLocation") != null) {
                String eventLocation = (String) updates.get("eventLocation");
                if (eventLocation.trim().isEmpty()) {
                    throw new IllegalArgumentException("Event Location cannot be empty if provided");
                }
                activity.setEventLocation(sanitizer.sanitize(eventLocation));
            }
            if (updates.containsKey("eventDate") && updates.get("eventDate") != null) {
                String eventDateStr = updates.get("eventDate") instanceof String ? (String) updates.get("eventDate") : updates.get("eventDate").toString();
                try {
                    LocalDate eventDate = LocalDate.parse(eventDateStr);
                    if (eventDate.isBefore(startDate) || eventDate.isAfter(endDate)) {
                        throw new IllegalArgumentException("Event date must be between 2024-10-28 and 2024-11-03");
                    }
                    activity.setEventDate(eventDate);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid date format for eventDate: " + eventDateStr);
                }
            }
            if (updates.containsKey("numberOfParticipants") && updates.get("numberOfParticipants") != null) {
                int numberOfParticipants = updates.get("numberOfParticipants") instanceof Integer
                    ? (Integer) updates.get("numberOfParticipants")
                    : Integer.parseInt(updates.get("numberOfParticipants").toString());
                if (numberOfParticipants < 1 || numberOfParticipants > 1000) {
                    throw new IllegalArgumentException("Number of participants must be between 1 and 1000");
                }
                activity.setNumberOfParticipants(numberOfParticipants);
            }
            if (updates.containsKey("remarks") && updates.get("remarks") != null) {
                String remarks = (String) updates.get("remarks");
                if (remarks.trim().isEmpty()) {
                    throw new IllegalArgumentException("Remarks cannot be empty if provided");
                }
                activity.setRemarks(sanitizer.sanitize(remarks));
            }
        }
        activityRepository.saveAll(activities);
    }
    @Override
    public Map<String, Object> getDashboardStats(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> stats = new HashMap<>();
        
        // Initialize counts
        Map<String, Long> dateCounts = new HashMap<>();
        Map<String, Long> participantCounts = new HashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dateCounts.put(date.toString(), 0L);
            participantCounts.put(date.toString(), 0L);
        }

        // Fetch event counts per date
        List<Object[]> eventCounts = activityRepository.countByDate(startDate, endDate);
        for (Object[] result : eventCounts) {
            LocalDate date = (LocalDate) result[0];
            Long count = (Long) result[1];
            dateCounts.put(date.toString(), count);
        }

        // Fetch activities to compute participant counts
        List<Activity> activities = activityRepository.findByDateRange(startDate, endDate);
        for (Activity activity : activities) {
            String dateStr = activity.getEventDate().toString();
            participantCounts.compute(dateStr, (k, v) -> v + activity.getNumberOfParticipants());
        }

        // Calculate statistics
        Long totalParticipants = activityRepository.sumParticipants(startDate, endDate);
        Long totalActivities = activityRepository.countActivities(startDate, endDate);
        Double avgParticipants = (totalActivities != null && totalActivities > 0) 
            ? totalParticipants.doubleValue() / totalActivities 
            : 0.0;

        // Add stats to response
        stats.put("uniqueStates", activityRepository.countUniqueStates(startDate, endDate));
        stats.put("totalParticipants", totalParticipants);
        stats.put("totalActivities", totalActivities);
        stats.put("avgParticipants", avgParticipants); // Add average participants
        stats.put("dateCounts", dateCounts);
        stats.put("participantCounts", participantCounts);
        stats.put("categoryCounts", activityRepository.countByCategory(startDate, endDate).stream()
            .collect(Collectors.toMap(
                arr -> (String) arr[0],
                arr -> ((Long) arr[1]).intValue()
            )));

        return stats;
    }
    @Override
    public List<String> getDistinctStates() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<Activity> root = query.from(Activity.class);
        query.select(root.get("state")).distinct(true).where(
            cb.and(
                cb.isNotNull(root.get("state")),
                cb.notEqual(root.get("state"), "")
            )
        );
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<String> getDistinctActivityTypes() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<Activity> root = query.from(Activity.class);
        query.select(root.get("activityType")).distinct(true).where(
            cb.and(
                cb.isNotNull(root.get("activityType")),
                cb.notEqual(root.get("activityType"), "")
            )
        );
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<String> getDistinctEventCategories() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<Activity> root = query.from(Activity.class);
        query.select(root.get("eventCategory")).distinct(true).where(
            cb.and(
                cb.isNotNull(root.get("eventCategory")),
                cb.notEqual(root.get("eventCategory"), "")
            )
        );
        return entityManager.createQuery(query).getResultList();
    }

    // new method imp: Implement date range query with filters
 // new method imp: Updated to handle filterOperator
    @Override
   
    public CompletableFuture<Map<String, Object>> getActivitiesByDateRangeWithFilters(
        LocalDate startDate, LocalDate endDate, String state, String activityType, 
        String eventCategory, String filterOperator, List<Map<String, String>> conditions) {
        
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> response = new HashMap<>();
            try {
                long startTime = System.currentTimeMillis();

                CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                CriteriaQuery<Activity> query = cb.createQuery(Activity.class);
                Root<Activity> root = query.from(Activity.class);

                List<Predicate> mainPredicates = new ArrayList<>();

                // Always add date range
                mainPredicates.add(cb.between(root.get("eventDate"), startDate, endDate));

                // Add advanced filters (state, activityType, eventCategory)
                List<Predicate> advancedPredicates = new ArrayList<>();
                if (!state.isEmpty()) {
                    // Assuming single value; if multi, split by comma and use in()
                    advancedPredicates.add(cb.equal(root.get("state"), state));
                }
                if (!activityType.isEmpty()) {
                    advancedPredicates.add(cb.equal(root.get("activityType"), activityType));
                }
                if (!eventCategory.isEmpty()) {
                    advancedPredicates.add(cb.equal(root.get("eventCategory"), eventCategory));
                }

                if (!advancedPredicates.isEmpty()) {
                    Predicate advancedCombined;
                    if ("AND".equalsIgnoreCase(filterOperator)) {
                        advancedCombined = cb.and(advancedPredicates.toArray(new Predicate[0]));
                    } else if ("OR".equalsIgnoreCase(filterOperator)) {
                        advancedCombined = cb.or(advancedPredicates.toArray(new Predicate[0]));
                    } else {
                        throw new IllegalArgumentException("Unsupported filter operator: " + filterOperator);
                    }
                    mainPredicates.add(advancedCombined);
                }

                // Add dynamic conditions (chained with their logical operators)
                Predicate dynamicPredicate = null;
                for (int i = 0; i < conditions.size(); i++) {
                    Map<String, String> condition = conditions.get(i);
                    String field = condition.get("field");
                    String operator = condition.get("operator");
                    String value = condition.get("value");

                    Predicate predicate = buildPredicate(cb, root, field, operator, value);

                    if (i == 0) {
                        dynamicPredicate = predicate;
                    } else {
                        String prevLogical = conditions.get(i - 1).getOrDefault("logical", "AND");
                        if ("OR".equalsIgnoreCase(prevLogical)) {
                            dynamicPredicate = cb.or(dynamicPredicate, predicate);
                        } else if ("NOT".equalsIgnoreCase(prevLogical)) {
                            dynamicPredicate = cb.and(dynamicPredicate, cb.not(predicate));
                        } else {
                            dynamicPredicate = cb.and(dynamicPredicate, predicate);
                        }
                    }
                }

                if (dynamicPredicate != null) {
                    mainPredicates.add(dynamicPredicate);
                }

                // Apply all main predicates with AND
                if (!mainPredicates.isEmpty()) {
                    query.where(cb.and(mainPredicates.toArray(new Predicate[0])));
                }

                // Execute query
                List<Activity> activities = entityManager.createQuery(query).getResultList();

                long executionTimeMs = System.currentTimeMillis() - startTime;

                response.put("activities", activities);
                response.put("rowCount", activities.size());
                response.put("executionTimeMs", executionTimeMs);

            } catch (Exception e) {
                response.put("activities", new ArrayList<>());
                response.put("errorMessage", "Error processing query: " + e.getMessage());
                response.put("rowCount", 0);
                response.put("executionTimeMs", 0L);
            }

            return response;
        });
    }

    // Helper method to build predicate for a single condition (copied/adapted from performDynamicCalculation)
    private Predicate buildPredicate(CriteriaBuilder cb, Root<Activity> root, String field, String operator, String value) {
        try {
            switch (field) {
                case "numberOfParticipants":
                    int intValue = Integer.parseInt(value);
                    switch (operator) {
                        case "=": return cb.equal(root.get(field), intValue);
                        case "!=": return cb.notEqual(root.get(field), intValue);
                        case "<": return cb.lt(root.get(field), intValue);
                        case ">": return cb.gt(root.get(field), intValue);
                        case "<=": return cb.le(root.get(field), intValue);
                        case ">=": return cb.ge(root.get(field), intValue);
                        default: throw new IllegalArgumentException("Unsupported operator: " + operator);
                    }
                case "eventDate":
                    LocalDate dateValue = LocalDate.parse(value);
                    switch (operator) {
                        case "=": return cb.equal(root.get(field), dateValue);
                        case "!=": return cb.notEqual(root.get(field), dateValue);
                        case "<": return cb.lessThan(root.get(field), dateValue);
                        case ">": return cb.greaterThan(root.get(field), dateValue);
                        case "<=": return cb.lessThanOrEqualTo(root.get(field), dateValue);
                        case ">=": return cb.greaterThanOrEqualTo(root.get(field), dateValue);
                        default: throw new IllegalArgumentException("Unsupported operator: " + operator);
                    }
                default: // String fields like state, activityType, eventCategory
                    switch (operator) {
                        case "=": return cb.equal(root.get(field), value);
                        case "!=": return cb.notEqual(root.get(field), value);
                        default: throw new IllegalArgumentException("Unsupported operator for field " + field + ": " + operator);
                    }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid value for field " + field + ": " + value, e);
        }
    }
    @Override
    public Activity getById(Long id) {
        if (Objects.nonNull(id)) {
            Optional<Activity> optionalActivity = activityRepository.findById(id);
            if (optionalActivity.isPresent()) {
                return optionalActivity.get();
            } else {
                throw new RuntimeException("Activity not found with the id: " + id);
            }
        }
        return null;
    }

    @Override
    public void deleteById(Long id) {
        if (Objects.nonNull(id)) {
            List<FileMetadata> fileMetadatas = fileMetadataRepository.findByActivityId(id);
            for (FileMetadata fileMetadata : fileMetadatas) {
                try {
                    Files.deleteIfExists(Paths.get(fileMetadata.getFilePath()));
                    fileMetadataRepository.delete(fileMetadata);
                } catch (IOException e) {
                    System.err.println("Failed to delete file: " + e.getMessage());
                }
            }
            activityRepository.deleteById(id);
        }
    }

    @Override
    public void deleteActivities(List<Long> ids) {
        if (Objects.nonNull(ids)) {
            for (Long id : ids) {
                List<FileMetadata> fileMetadatas = fileMetadataRepository.findByActivityId(id);
                for (FileMetadata fileMetadata : fileMetadatas) {
                    try {
                        Files.deleteIfExists(Paths.get(fileMetadata.getFilePath()));
                        fileMetadataRepository.delete(fileMetadata);
                    } catch (IOException e) {
                        System.err.println("Failed to delete file: " + e.getMessage());
                    }
                }
            }
            activityRepository.deleteAllById(ids);
        }
    }

    @Override
    public List<FileMetadata> getFilesByActivityId(Long activityId) {
        return fileMetadataRepository.findByActivityId(activityId);
    }

    @Override
    public void deleteFile(Long fileId) {
        FileMetadata fileMetadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
        try {
            Files.deleteIfExists(Paths.get(fileMetadata.getFilePath()));
            fileMetadataRepository.delete(fileMetadata);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file: " + e.getMessage());
        }
    }

    @Override
    public List<Activity> getActivitiesByStateAndEventCategory(String state, String eventCategory, String operator) {
        return activityRepository.findByStateAndEventCategoryWithOperator(state, eventCategory, operator);
    }

    @Override
    public List<Activity> getActivitiesByDateRange(LocalDate startDate, LocalDate endDate) {
        return activityRepository.findByDateRange(startDate, endDate);
    }

    @Override
    public Map<String, Object> getAiSuggestions() {
        Map<String, Object> response = new HashMap<>();
        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);
        List<Activity> activities = activityRepository.findByDateRange(startDate, endDate);

        if (activities.isEmpty()) {
            response.put("errorMessage", "No historical data available for AI suggestions.");
            return response;
        }

        Map<LocalDate, Long> dateParticipantCount = new HashMap<>();
        Map<LocalDate, Integer> dateEventCount = new HashMap<>();
        Map<String, Integer> categoryCount = new HashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dateParticipantCount.put(date, 0L);
            dateEventCount.put(date, 0);
        }

        for (Activity activity : activities) {
            LocalDate date = activity.getEventDate();
            String category = activity.getEventCategory();
            dateParticipantCount.compute(date, (k, v) -> v + activity.getNumberOfParticipants());
            dateEventCount.compute(date, (k, v) -> v + 1);
            categoryCount.compute(category, (k, v) -> (v == null ? 0 : v) + 1);
        }

        String popularCategory = categoryCount.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("Unknown");

        Map<LocalDate, Double> dateScores = new HashMap<>();
        long maxParticipants = dateParticipantCount.values().stream().mapToLong(Long::longValue).max().orElse(1);
        int maxEvents = dateEventCount.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int maxCategoryCount = categoryCount.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            final LocalDate finalDate = date;
            double participantScore = (double) dateParticipantCount.get(date) / maxParticipants * 0.5;
            double eventScore = (double) dateEventCount.get(date) / maxEvents * 0.3;
            double categoryScore = activities.stream()
                .filter(a -> a.getEventDate().equals(finalDate) && a.getEventCategory().equals(popularCategory))
                .count() / (double) maxCategoryCount * 0.2;
            dateScores.put(date, participantScore + eventScore + categoryScore);
        }

        LocalDate suggestedDate = dateScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(startDate);

        List<Activity> sameDateActivities = activities.stream()
            .filter(a -> a.getEventDate().equals(suggestedDate))
            .collect(Collectors.toList());
        double avgParticipants = sameDateActivities.isEmpty() ? 0 :
            sameDateActivities.stream().mapToLong(Activity::getNumberOfParticipants).average().orElse(0);

        String reason = String.format(
            "Recommended date %s based on high participation (%d participants) and event frequency (%d events). " +
            "The category '%s' is popular, enhancing suitability.",
            suggestedDate, dateParticipantCount.get(suggestedDate), dateEventCount.get(suggestedDate), popularCategory
        );

        response.put("suggestedDate", suggestedDate.toString());
        response.put("predictedParticipants", Math.round(avgParticipants));
        response.put("reason", reason);
        return response;
    }
    @Override
    public void reorderActivities(List<Map<String, Long>> orderList) {
        for (Map<String, Long> orderMap : orderList) {
            Long id = orderMap.get("id");
            Integer newOrder = orderMap.get("orderIndex").intValue();
            Activity activity = getById(id);
            activity.setOrderIndex(newOrder);
            activityRepository.save(activity);
        }
    }

    // new methods imp: Perform custom calculation (example: sum or avg on column)
    @Override
    public Map<String, Object> performCustomCalculation(String column, String formula) {
        List<Activity> activities = getAllActivities();
        Map<String, Object> result = new HashMap<>();
        if ("sum".equals(formula) && "numberOfParticipants".equals(column)) {
            // new changes: Ensure sum is returned as long (integer equivalent)
            long sum = activities.stream().mapToLong(Activity::getNumberOfParticipants).sum();
            result.put("result", sum);
        } else if ("avg".equals(formula) && "numberOfParticipants".equals(column)) {
            double avg = activities.stream().mapToLong(Activity::getNumberOfParticipants).average().orElse(0);
            result.put("result", avg);
        }
        return result;
    }
    // new methods imp: get Summary
    @Override
    public Map<String, Object> getSummary() {
        List<Activity> activities = getAllActivities();
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalActivities", activities.size());
        // new changes: Ensure totalParticipants is returned as long (integer equivalent)
        summary.put("totalParticipants", activities.stream().mapToLong(Activity::getNumberOfParticipants).sum());
        return summary;
    }
    // new updates
    @Override
    public long getTotalParticipants(String search, String state, String category, String dateRange) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Activity> root = query.from(Activity.class);
        query.select(cb.sum(root.get("numberOfParticipants")));

        List<Predicate> predicates = new ArrayList<>();
        if (!search.isEmpty()) {
            String searchLower = "%" + search.toLowerCase() + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("state")), searchLower),
                    cb.like(cb.lower(root.get("eventCategory")), searchLower),
                    cb.like(cb.lower(root.get("eventDescription")), searchLower)
            ));
        }
        if (!state.isEmpty()) {
            predicates.add(root.get("state").in(Arrays.asList(state.split(","))));
        }
        if (!category.isEmpty()) {
            predicates.add(root.get("eventCategory").in(Arrays.asList(category.split(","))));
        }
        if (!dateRange.isEmpty()) {
            String[] dates = dateRange.split(" to ");
            if (dates.length == 2) {
                try {
                    LocalDate start = LocalDate.parse(dates[0]);
                    LocalDate end = LocalDate.parse(dates[1]);
                    predicates.add(cb.between(root.get("eventDate"), start, end));
                } catch (Exception e) {
                    System.err.println("Invalid date range format: " + dateRange);
                }
            }
        }
        query.where(cb.and(predicates.toArray(new Predicate[0])));

        Long result = entityManager.createQuery(query).getSingleResult();
        return result != null ? result : 0L;
    }
    @Override
    public Map<String, Object> performDynamicCalculation(List<Map<String, String>> aggregates, List<String> groupBy, List<Map<String, String>> conditions) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<Activity> root = query.from(Activity.class);

        // Build aggregate expressions
        List<Selection<?>> selections = new ArrayList<>();
        List<String> aggregateKeys = new ArrayList<>();
        for (Map<String, String> aggregate : aggregates) {
            String function = aggregate.get("function").toUpperCase();
            String column = aggregate.get("column");

            // Validate column and function compatibility
            boolean isNumeric = "numberOfParticipants".equals(column);
            boolean isDate = "eventDate".equals(column);
            boolean isString = "state".equals(column) || "stationName".equals(column) ||
                              "eventCategory".equals(column) || "participantCategory".equals(column);

            if (isString && !function.equals("COUNT")) {
                throw new IllegalArgumentException("Aggregate function " + function + " is not supported for string column: " + column);
            }
            if (isDate && !(function.equals("COUNT") || function.equals("MIN") || function.equals("MAX"))) {
                throw new IllegalArgumentException("Aggregate function " + function + " is not supported for date column: " + column);
            }

            Expression<?> aggregateExpr;
            switch (function) {
                case "COUNT":
                    aggregateExpr = cb.count(root.get(column));
                    break;
                case "SUM":
                    if (!isNumeric) throw new IllegalArgumentException("SUM is only supported for numeric columns");
                    aggregateExpr = cb.sum(root.get(column));
                    break;
                case "AVG":
                    if (!isNumeric) throw new IllegalArgumentException("AVG is only supported for numeric columns");
                    aggregateExpr = cb.avg(root.get(column));
                    break;
                case "MIN":
                    aggregateExpr = cb.min(root.get(column));
                    break;
                case "MAX":
                    aggregateExpr = cb.max(root.get(column));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported aggregate function: " + function);
            }
            selections.add(aggregateExpr);
            aggregateKeys.add(function + "_" + column);
        }

        // Add GROUP BY columns
        List<Expression<?>> groupByExpressions = new ArrayList<>();
        if (groupBy != null && !groupBy.isEmpty()) {
            for (String group : groupBy) {
                if (!group.isEmpty()) {
                    selections.add(0, root.get(group));
                    groupByExpressions.add(root.get(group));
                }
            }
            query.groupBy(groupByExpressions.toArray(new Expression[0]));
        }

        // Build selection
        // Fix: Convert List<Selection<?>> to array for multiselect
        query.multiselect(selections.toArray(new Selection[0]));

        // Build WHERE conditions
        List<Predicate> predicates = new ArrayList<>();
        Predicate currentPredicate = null;
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, String> condition = conditions.get(i);
            String field = condition.get("field");
            String operator = condition.get("operator");
            String value = condition.get("value");
            String logical = condition.getOrDefault("logical", "");

            Predicate predicate;
            try {
                switch (field) {
                    case "numberOfParticipants":
                        int intValue = Integer.parseInt(value);
                        switch (operator) {
                            case "=":
                                predicate = cb.equal(root.get(field), intValue);
                                break;
                            case "!=":
                                predicate = cb.notEqual(root.get(field), intValue);
                                break;
                            case "<":
                                predicate = cb.lt(root.get(field), intValue);
                                break;
                            case ">":
                                predicate = cb.gt(root.get(field), intValue);
                                break;
                            case "<=":
                                predicate = cb.le(root.get(field), intValue);
                                break;
                            case ">=":
                                predicate = cb.ge(root.get(field), intValue);
                                break;
                            default:
                                throw new IllegalArgumentException("Unsupported operator: " + operator);
                        }
                        break;
                    case "eventDate":
                        LocalDate dateValue = LocalDate.parse(value);
                        switch (operator) {
                            case "=":
                                predicate = cb.equal(root.get(field), dateValue);
                                break;
                            case "!=":
                                predicate = cb.notEqual(root.get(field), dateValue);
                                break;
                            case "<":
                                predicate = cb.lessThan(root.get(field), dateValue);
                                break;
                            case ">":
                                predicate = cb.greaterThan(root.get(field), dateValue);
                                break;
                            case "<=":
                                predicate = cb.lessThanOrEqualTo(root.get(field), dateValue);
                                break;
                            case ">=":
                                predicate = cb.greaterThanOrEqualTo(root.get(field), dateValue);
                                break;
                            default:
                                throw new IllegalArgumentException("Unsupported operator: " + operator);
                        }
                        break;
                    default: // String fields: state, stationName, eventCategory, participantCategory
                        switch (operator) {
                            case "=":
                                predicate = cb.equal(root.get(field), value);
                                break;
                            case "!=":
                                predicate = cb.notEqual(root.get(field), value);
                                break;
                            default:
                                throw new IllegalArgumentException("Unsupported operator for field " + field + ": " + operator);
                        }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for field " + field + ": " + value);
            }

            if (i == 0) {
                currentPredicate = predicate;
            } else {
                String prevLogical = conditions.get(i - 1).get("logical");
                if ("OR".equalsIgnoreCase(prevLogical)) {
                    currentPredicate = cb.or(currentPredicate, predicate);
                } else if ("NOT".equalsIgnoreCase(prevLogical)) {
                    currentPredicate = cb.and(currentPredicate, cb.not(predicate));
                } else {
                    currentPredicate = cb.and(currentPredicate, predicate);
                }
            }
        }

        if (currentPredicate != null) {
            query.where(currentPredicate);
        }

        // Execute query
        List<Object[]> results = entityManager.createQuery(query).getResultList();
        Map<String, Object> response = new HashMap<>();
        if (groupBy != null && !groupBy.isEmpty()) {
            List<Map<String, Object>> resultList = new ArrayList<>();
            for (Object[] result : results) {
                Map<String, Object> resultMap = new HashMap<>();
                Map<String, Object> groups = new HashMap<>();
                int offset = 0;
                for (String group : groupBy) {
                    if (!group.isEmpty()) {
                        groups.put(group, result[offset] != null ? result[offset].toString() : "N/A");
                        offset++;
                    }
                }
                Map<String, Object> values = new HashMap<>();
                for (int i = 0; i < aggregates.size(); i++) {
                    values.put(aggregateKeys.get(i), result[offset + i] != null ? result[offset + i] : "N/A");
                }
                resultMap.put("groups", groups);
                resultMap.put("values", values);
                resultList.add(resultMap);
            }
            response.put("results", resultList);
        } else {
            Map<String, Object> values = new HashMap<>();
            if (!results.isEmpty()) {
                Object[] result = results.get(0);
                for (int i = 0; i < aggregates.size(); i++) {
                    values.put(aggregateKeys.get(i), result[i] != null ? result[i] : "N/A");
                }
            }
            response.put("values", values);
        }

        return response;
    }
    @Override
    @Async
    public CompletableFuture<List<Map<String, Object>>> executeBatchQueries(List<Map<String, String>> queries) {
        List<Map<String, Object>> results = new ArrayList<>();
        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);

        for (Map<String, String> query : queries) {
            String state = query.getOrDefault("state", "");
            String eventCategory = query.getOrDefault("eventCategory", "");
            String operator = query.getOrDefault("operator", "AND");

            long startTime = System.currentTimeMillis();
            List<Activity> activities = activityRepository.findByStateAndEventCategoryWithOperator(state, eventCategory, operator);
            long executionTime = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("activities", activities);
            result.put("totalActivities", activities.size());
            result.put("totalParticipants", activities.stream().mapToLong(Activity::getNumberOfParticipants).sum());
            result.put("executionTimeMs", executionTime);
            result.put("query", Map.of("state", state, "eventCategory", eventCategory, "operator", operator));
            results.add(result);
        }

        return CompletableFuture.completedFuture(results);
    }
    
  
       }