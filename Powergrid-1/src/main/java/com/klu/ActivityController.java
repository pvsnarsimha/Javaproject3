package com.klu;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Controller
public class ActivityController {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    private final ConcurrentLinkedQueue<ResponseBodyEmitter> emitters = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        objectMapper.registerModule(new JavaTimeModule());
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public static class ActivityUpdate {
        private String action;
        private Activity activity;
        private List<Long> deletedIds;
        private List<FileMetadata> files;

        public ActivityUpdate(String action, Activity activity) {
            this.action = action;
            this.activity = activity;
        }

        public ActivityUpdate(String action, List<Long> deletedIds) {
            this.action = action;
            this.deletedIds = deletedIds;
        }

        public ActivityUpdate(String action) {
            this.action = action;
        }

        public ActivityUpdate(String action, Activity activity, List<FileMetadata> files) {
            this.action = action;
            this.activity = activity;
            this.files = files;
        }

        public String getAction() { return action; }
        public Activity getActivity() { return activity; }
        public List<Long> getDeletedIds() { return deletedIds; }
        public List<FileMetadata> getFiles() { return files; }
    }

    @GetMapping("/")
    public String viewHomePage(Model model,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "10") int size,
                               @RequestParam(defaultValue = "") String search,
                               @RequestParam(defaultValue = "") String state,
                               @RequestParam(defaultValue = "") String category,
                               @RequestParam(defaultValue = "") String dateRange,
                               @RequestParam(defaultValue = "id") String sortBy,
                               @RequestParam(defaultValue = "asc") String sortDir) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Activity> activityPage = activityService.getAllActivities(search, state, category, dateRange, sortBy, sortDir, pageable);
        model.addAttribute("activities", activityPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", activityPage.getTotalPages());
        model.addAttribute("size", size);
        model.addAttribute("search", search);
        model.addAttribute("state", state);
        model.addAttribute("category", category);
        model.addAttribute("dateRange", dateRange);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activityPage.getContent().forEach(activity -> 
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "index";
    }

    @GetMapping("/add")
    public String addNewActivity(Model model) {
        Activity activity = new Activity();
        model.addAttribute("activity", activity);
        return "addActivity";
    }

    @PostMapping(value = "/save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String saveActivity(@ModelAttribute("activity") Activity activity, 
                              @RequestParam(value = "files", required = false) MultipartFile[] files, 
                              Model model) {
        try {
            boolean isUpdate = activity.getId() != null;
            activityService.save(activity, files);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activity.getId());
            scheduleBroadcast(new ActivityUpdate(isUpdate ? "UPDATE" : "ADD", activity, fileMetadatas));
            return isUpdate ? "redirect:/save/updateSuccess" : "redirect:/save/saveSuccess";
        } catch (RuntimeException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("activity", activity);
            model.addAttribute("isUpdate", activity.getId() != null);
            return activity.getId() != null ? "updateActivity" : "addActivity";
        }
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> saveActivityAjax(@ModelAttribute Activity activity) {
        try {
            boolean isUpdate = activity.getId() != null;
            activityService.save(activity, null);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activity.getId());
            scheduleBroadcast(new ActivityUpdate(isUpdate ? "UPDATE" : "ADD", activity, fileMetadatas));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
        }
    }

    @GetMapping("/confirmDelete/{id}")
    public String confirmDelete(@PathVariable("id") Long id, Model model) {
        model.addAttribute("activityId", id);
        return "confirmDelete";
    }

    @PostMapping("/delete")
    public String deleteById(@RequestParam("id") Long id, @RequestParam("confirm") String confirm) {
        if ("yes".equalsIgnoreCase(confirm)) {
            activityService.deleteById(id);
            scheduleBroadcast(new ActivityUpdate("DELETE", new Activity() {{ setId(id); }}));
        }
        return "redirect:/";
    }

    @PostMapping("/delete/bulk")
    @ResponseBody
    public void deleteBulk(@RequestBody List<Long> ids) {
        activityService.deleteActivities(ids);
        scheduleBroadcast(new ActivityUpdate("DELETE", ids));
    }

    @PostMapping("/update")
    @ResponseBody
    public void updateActivity(@RequestBody Activity activity) {
        activityService.save(activity, null);
        scheduleBroadcast(new ActivityUpdate("UPDATE", activity));
    }

    @GetMapping("/activities/stats")
    @ResponseBody
    public Map<String, Object> getStats() {
        List<Activity> activities = activityService.getAllActivities();
        Map<String, Object> stats = new HashMap<>();
        stats.put("stateLabels", List.of("Andhra Pradesh", "Telangana", "Karnataka"));
        stats.put("stateValues", List.of(
                activities.stream().filter(a -> "Andhra Pradesh".equals(a.getState())).count(),
                activities.stream().filter(a -> "Telangana".equals(a.getState())).count(),
                activities.stream().filter(a -> "Karnataka".equals(a.getState())).count()
        ));
        stats.put("categoryLabels", List.of("Integrity Pledge", "Vendors Meet", "Elocution", "Debate", "Drawing", "Quiz", "Slogan"));
        stats.put("categoryValues", List.of(
                activities.stream().filter(a -> "Integrity Pledge".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Vendors Meet".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Elocution".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Debate".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Drawing".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Quiz".equals(a.getEventCategory())).count(),
                activities.stream().filter(a -> "Slogan".equals(a.getEventCategory())).count()
        ));
        return stats;
    }

    @DeleteMapping("/activities/files/{activityId}")
    @ResponseBody
    public ResponseEntity<?> deleteAllFilesForActivity(@PathVariable Long activityId) {
        try {
            Activity activity = activityService.getById(activityId);
            List<FileMetadata> files = activityService.getFilesByActivityId(activityId);
            if (files.isEmpty()) {
                return ResponseEntity.ok(Map.of("message", "No files to delete for activity ID: " + activityId));
            }
            for (FileMetadata file : files) {
                activityService.deleteFile(file.getId());
            }
            List<FileMetadata> updatedFileMetadatas = activityService.getFilesByActivityId(activityId);
            scheduleBroadcast(new ActivityUpdate("UPDATE", activity, updatedFileMetadatas));
            return ResponseEntity.ok(Map.of("message", "Files deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "Failed to delete files: " + e.getMessage()));
        }
    }

    @GetMapping("/save/saveSuccess")
    public String showSaveSuccess() {
        return "saveSuccess";
    }

    @GetMapping("/save/updateSuccess")
    public String showUpdateSuccess() {
        return "updateSuccess";
    }

    @GetMapping("/update/{id}")
    public String updateForm(@PathVariable(value = "id") Long id, Model model) {
        Activity activity = activityService.getById(id);
        List<FileMetadata> files = activityService.getFilesByActivityId(id);
        model.addAttribute("activity", activity);
        model.addAttribute("files", files);
        return "updateActivity";
    }

    @PostMapping(value = "/api/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<?> uploadFile(@RequestParam("files") MultipartFile[] files, @RequestParam("activityId") Long activityId) {
        try {
            Activity activity = activityService.getById(activityId);
            activityService.save(activity, files);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activityId);
            scheduleBroadcast(new ActivityUpdate("UPDATE", activity, fileMetadatas));
            return ResponseEntity.ok(Map.of("message", "Files uploaded successfully", "fileNames", 
                fileMetadatas.stream().map(FileMetadata::getFileName).toArray()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "Failed to upload files: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/file/{fileId}")
    @ResponseBody
    public ResponseEntity<?> deleteFile(@PathVariable Long fileId) {
        try {
            FileMetadata fileMetadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
            Long activityId = fileMetadata.getActivity().getId();
            activityService.deleteFile(fileId);
            Activity activity = activityService.getById(activityId);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activityId);
            scheduleBroadcast(new ActivityUpdate("UPDATE", activity, fileMetadatas));
            return ResponseEntity.ok(Map.of("message", "File deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "Failed to delete file: " + e.getMessage()));
        }
    }

    @GetMapping("/api/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId) {
        FileMetadata fileMetadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
        
        File file = new File(fileMetadata.getFilePath());
        if (!file.exists()) {
            throw new RuntimeException("File not found on server: " + fileMetadata.getFilePath());
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileMetadata.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(fileMetadata.getContentType()))
                .contentLength(fileMetadata.getFileSize())
                .body(resource);
    }

    @GetMapping(value = "/activities/updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter streamUpdates() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(60000L);
        synchronized (emitters) {
            emitters.add(emitter);
        }
        emitter.onCompletion(() -> {
            synchronized (emitters) {
                emitters.remove(emitter);
            }
        });
        emitter.onError((throwable) -> {
            System.err.println("SSE Error: " + throwable.getMessage());
            throwable.printStackTrace();
            synchronized (emitters) {
                emitters.remove(emitter);
            }
        });
        emitter.onTimeout(() -> {
            System.err.println("SSE Timeout for emitter");
            synchronized (emitters) {
                emitters.remove(emitter);
            }
        });
        try {
            emitter.send("data: " + objectMapper.writeValueAsString(new ActivityUpdate("HEARTBEAT")) + "\n\n", MediaType.TEXT_EVENT_STREAM);
        } catch (Exception e) {
            System.err.println("Failed to send initial heartbeat: " + e.getMessage());
            e.printStackTrace();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @GetMapping("/stateCategoryQuery")
    public String stateCategoryQueryForm(Model model) {
        model.addAttribute("state", "");
        model.addAttribute("eventCategory", "");
        model.addAttribute("operator", "AND");
        model.addAttribute("activities", new ArrayList<Activity>());
        return "stateCategoryQuery";
    }

    @PostMapping("/stateCategoryQuery")
    public String stateCategoryQuery(@RequestParam("state") String state, 
                                    @RequestParam("eventCategory") String eventCategory, 
                                    @RequestParam(value = "operator", defaultValue = "AND") String operator,
                                    Model model) {
        List<Activity> activities = new ArrayList<>();
        if (state.isEmpty() || eventCategory.isEmpty()) {
            model.addAttribute("errorMessage", "Please select both a state and an event category.");
        } else {
            activities = activityService.getActivitiesByStateAndEventCategory(state, eventCategory, operator);
        }
        model.addAttribute("state", state);
        model.addAttribute("eventCategory", eventCategory);
        model.addAttribute("operator", operator);
        model.addAttribute("activities", activities);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activities.forEach(activity -> 
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "stateCategoryQuery";
    }

    @PostMapping(value = "/api/stateCategoryQuery", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> stateCategoryQueryAjax(@RequestParam("state") String state,
                                                     @RequestParam("eventCategory") String eventCategory,
                                                     @RequestParam(value = "operator", defaultValue = "AND") String operator,
                                                     @RequestParam(value = "search", defaultValue = "") String search,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size,
                                                     @RequestParam(defaultValue = "id") String sortBy,
                                                     @RequestParam(defaultValue = "asc") String sortDir) {
        Map<String, Object> response = new HashMap<>();
        if (operator.equals("NOT") && state.isEmpty() && eventCategory.isEmpty()) {
            response.put("activities", new ArrayList<>());
            response.put("errorMessage", "Please select at least one of state or event category for NOT operator.");
            response.put("totalElements", 0);
            response.put("uniqueStates", 0);
            response.put("totalParticipants", 0);
            return response;
        } else if (!operator.equals("NOT") && (state.isEmpty() || eventCategory.isEmpty())) {
            response.put("activities", new ArrayList<>());
            response.put("errorMessage", "Please select both a state and an event category for AND/OR operators.");
            response.put("totalElements", 0);
            response.put("uniqueStates", 0);
            response.put("totalParticipants", 0);
            return response;
        }

        Sort sort = Sort.by(sortDir.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Activity> activityPage = activityService.getAllActivities(search, state, eventCategory, "", sortBy, sortDir, pageable);
        List<Activity> activities = activityService.getActivitiesByStateAndEventCategory(state, eventCategory, operator);
        
        // Filter activities based on pagination
        int start = page * size;
        int end = Math.min(start + size, activities.size());
        List<Activity> paginatedActivities = activities.subList(Math.min(start, activities.size()), end);

        // Calculate summary statistics
        long totalElements = activities.size();
        Set<String> uniqueStates = activities.stream()
                .map(Activity::getState)
                .filter(stateVal -> stateVal != null)
                .collect(Collectors.toSet());
        long totalParticipants = activities.stream()
                .mapToLong(Activity::getNumberOfParticipants)
                .sum();

        response.put("activities", paginatedActivities);
        response.put("totalElements", totalElements);
        response.put("uniqueStates", uniqueStates.size());
        response.put("totalParticipants", totalParticipants);
        return response;
    }

    @GetMapping("/dateRangeQuery")
    public String dateRangeQueryForm(Model model) {
        model.addAttribute("startDate", "");
        model.addAttribute("endDate", "");
        model.addAttribute("activities", new ArrayList<Activity>());
        return "dateRangeQuery";
    }

    @PostMapping("/dateRangeQuery")
    public String dateRangeQuery(@RequestParam("startDate") String startDate,
                                @RequestParam("endDate") String endDate,
                                Model model) {
        List<Activity> activities = new ArrayList<>();
        try {
            if (startDate.isEmpty() || endDate.isEmpty()) {
                model.addAttribute("errorMessage", "Please provide both start and end dates.");
            } else {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end = LocalDate.parse(endDate);
                if (start.isAfter(end)) {
                    model.addAttribute("errorMessage", "Start date must be before or equal to end date.");
                } else {
                    activities = activityService.getActivitiesByDateRange(start, end);
                }
            }
        } catch (DateTimeParseException e) {
            model.addAttribute("errorMessage", "Invalid date format. Please use YYYY-MM-DD.");
        }
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("activities", activities);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activities.forEach(activity -> 
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "dateRangeQuery";
    }

    @PostMapping(value = "/api/dateRangeQuery", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> dateRangeQueryAjax(@RequestParam("startDate") String startDate,
                                                 @RequestParam("endDate") String endDate) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (startDate.isEmpty() || endDate.isEmpty()) {
                response.put("activities", new ArrayList<>());
                response.put("errorMessage", "Please provide both start and end dates.");
            } else {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end = LocalDate.parse(endDate);
                if (start.isAfter(end)) {
                    response.put("activities", new ArrayList<>());
                    response.put("errorMessage", "Start date must be before or equal to end date.");
                } else {
                    List<Activity> activities = activityService.getActivitiesByDateRange(start, end);
                    response.put("activities", activities);
                }
            }
        } catch (DateTimeParseException e) {
            response.put("activities", new ArrayList<>());
            response.put("errorMessage", "Invalid date format. Please use YYYY-MM-DD.");
        }
        return response;
    }

    private void sendHeartbeat() {
        try {
            String heartbeatData = objectMapper.writeValueAsString(new ActivityUpdate("HEARTBEAT"));
            synchronized (emitters) {
                emitters.removeIf(emitter -> {
                    try {
                        emitter.send("data: " + heartbeatData + "\n\n", MediaType.TEXT_EVENT_STREAM);
                        return false;
                    } catch (Exception e) {
                        System.err.println("Error sending heartbeat: " + e.getMessage());
                        e.printStackTrace();
                        emitter.completeWithError(e);
                        return true;
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("Failed to serialize heartbeat: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void scheduleBroadcast(ActivityUpdate update) {
        try {
            String data = objectMapper.writeValueAsString(update);
            scheduler.schedule(() -> broadcastUpdate(data), 100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.err.println("Failed to serialize update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void broadcastUpdate(String data) {
        synchronized (emitters) {
            emitters.removeIf(emitter -> {
                try {
                    emitter.send("data: " + data + "\n\n", MediaType.TEXT_EVENT_STREAM);
                    return false;
                } catch (Exception e) {
                    System.err.println("Error sending update: " + e.getMessage());
                    e.printStackTrace();
                    emitter.completeWithError(e);
                    return true;
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        synchronized (emitters) {
            emitters.forEach(ResponseBodyEmitter::complete);
            emitters.clear();
        }
    }

    @GetMapping("/confirmExit")
    public String confirmExit() {
        return "confirmExit";
    }

    @GetMapping("/exitProgram")
    public String exitProgram(@RequestParam("confirmExit") String confirmExit) {
        if ("yes".equalsIgnoreCase(confirmExit)) {
            return "redirect:https://www.google.com";
        } else {
            return "redirect:/";
        }
    }
}