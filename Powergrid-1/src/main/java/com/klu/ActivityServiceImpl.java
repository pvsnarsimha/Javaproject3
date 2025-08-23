package com.klu;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ActivityServiceImpl implements ActivityService {

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private FileStorageService fileStorageService;

    private final PolicyFactory sanitizer = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS);

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
}