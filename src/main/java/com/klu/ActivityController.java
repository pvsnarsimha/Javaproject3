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
import java.util.concurrent.CompletableFuture;


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

    public static class ActivityUpdate {
        private String action;
        private Activity activity;
        private List<Activity> activities; // Added for bulk updates
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

        public ActivityUpdate(String action, List<Activity> activities, List<FileMetadata> files) {
            this.action = action;
            this.activities = activities;
            this.files = files;
        }

        public String getAction() { return action; }
        public Activity getActivity() { return activity; }
        public List<Activity> getActivities() { return activities; }
        public List<Long> getDeletedIds() { return deletedIds; }
        public List<FileMetadata> getFiles() { return files; }
    }
    @GetMapping("/")
    public String viewHomePage(Model model) {
        model.addAttribute("companyName", "KLU Solutions");
        return "home";
    }
    @GetMapping("/activity")
    public String viewHomePage(Model model,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "10") int size,
                              @RequestParam(defaultValue = "") String search,
                              @RequestParam(defaultValue = "") String state,
                              @RequestParam(defaultValue = "") String category,
                              @RequestParam(defaultValue = "") String dateRange,
                              @RequestParam(defaultValue = "id") String sortBy,
                              @RequestParam(defaultValue = "asc") String sortDir,
                              HttpSession session) {
        // Ensure size is valid
        size = Math.max(1, Math.min(size, 1000)); // Limit size to 1-1000
        session.setAttribute("pageSize", size); // Persist page size in session

        Sort sort = Sort.by(sortDir.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Activity> activityPage = activityService.getAllActivities(search, state, category, dateRange, sortBy, sortDir, pageable);
        
        // Get dashboard stats
        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);
        Map<String, Object> dashboardStats = activityService.getDashboardStats(startDate, endDate);
        
        // new changes: Get summary for total participants
        Map<String, Object> summary = activityService.getSummary();

        // Add attributes to model
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
        model.addAttribute("dashboardStats", dashboardStats);
        model.addAttribute("summary", summary); // new changes: Pass summary to frontend
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
    @PostMapping(value = "/inlineUpdate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> inlineUpdate(@RequestBody Map<String, Object> updateRequest) {
        try {
            Long id = Long.valueOf(updateRequest.get("id").toString());
            String field = (String) updateRequest.get("field");
            Object value = updateRequest.get("value");
            activityService.updateField(id, field, value);
            Activity updatedActivity = activityService.getById(id);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(id);
            scheduleBroadcast(new ActivityUpdate("UPDATE", updatedActivity, fileMetadatas));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
        }
    }

    @PostMapping(value = "/bulkUpdate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> bulkUpdate(@RequestBody Map<String, Object> updateRequest) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> ids = (List<Long>) updateRequest.get("ids");
            Map<String, Object> updates = new HashMap<>(updateRequest);
            updates.remove("ids");
            if (updates.containsKey("eventDate") && updates.get("eventDate") != null) {
                updates.put("eventDate", LocalDate.parse((String) updates.get("eventDate")));
            }
            activityService.bulkUpdate(ids, updates);
            List<Activity> updatedActivities = activityService.getAllActivities().stream()
                .filter(a -> ids.contains(a.getId()))
                .collect(Collectors.toList());
            List<FileMetadata> allFiles = updatedActivities.stream()
                .flatMap(a -> activityService.getFilesByActivityId(a.getId()).stream())
                .collect(Collectors.toList());
            scheduleBroadcast(new ActivityUpdate("BULK_UPDATE", updatedActivities, allFiles));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
        }
    }

    @GetMapping("/confirmDelete/{id}")
    public String confirmDelete(@PathVariable("id") Long id, Model model) {
        model.addAttribute("activityId", id);
        return "confirmDelete";
    }
    @GetMapping("/careers")
    public String viewCareersPage(Model model) {
        // Placeholder job data (to be replaced with real data from ActivityService)
        List<Map<String, Object>> jobs = new ArrayList<>();
        Map<String, Object> job1 = new HashMap<>();
        job1.put("id", 1L);
        job1.put("title", "Senior Electrical Engineer");
        job1.put("location", "Gurugram, Haryana");
        job1.put("postedDate", LocalDate.now());
        jobs.add(job1);
        Map<String, Object> job2 = new HashMap<>();
        job2.put("id", 2L);
        job2.put("title", "Project Manager");
        job2.put("location", "Mumbai, Maharashtra");
        job2.put("postedDate", LocalDate.now().minusDays(3));
        jobs.add(job2);
        model.addAttribute("jobs", jobs);
        return "careers";
    }
    @GetMapping("/services")
    public String viewServicesPage(Model model) {
        // Placeholder service data (to be replaced with real data from ActivityService)
        List<Map<String, Object>> services = new ArrayList<>();
        Map<String, Object> service1 = new HashMap<>();
        service1.put("name", "Power Transmission");
        service1.put("description", "Reliable transmission of electricity across India.");
        service1.put("icon", "transmission");
        services.add(service1);
        Map<String, Object> service2 = new HashMap<>();
        service2.put("name", "Renewable Energy Solutions");
        service2.put("description", "Integration of solar and wind energy into the grid.");
        service2.put("icon", "renewable");
        services.add(service2);
        Map<String, Object> service3 = new HashMap<>();
        service3.put("name", "Consultancy Services");
        service3.put("description", "Expert advice on power infrastructure projects.");
        service3.put("icon", "consultancy");
        services.add(service3);
        model.addAttribute("services", services);
        return "services";
    }
    @GetMapping("/projects")
    public String getProjectsPage(Model model) {
        // For now, serve static content; can be extended with dynamic data later
        return "projects";
    }
    @GetMapping("/networks")
    public String getNetworkPage(Model model) {
        // Serve static network page; can be extended with dynamic data later
        return "networks";
    }
    @PostMapping("/delete")
    public String deleteById(@RequestParam("id") Long id, @RequestParam("confirm") String confirm) {
        if ("yes".equalsIgnoreCase(confirm)) {
            activityService.deleteById(id);
            scheduleBroadcast(new ActivityUpdate("DELETE", List.of(id)));
        }
        return "redirect:/";
    }

    @PostMapping(value = "/delete/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> deleteBulk(@RequestBody List<Long> ids) {
        try {
            activityService.deleteActivities(ids);
            scheduleBroadcast(new ActivityUpdate("DELETE", ids));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "Failed to delete activities: " + e.getMessage()));
        }
    }

    @PostMapping("/update")
    @ResponseBody
    public ResponseEntity<?> updateActivity(@RequestBody Activity activity) {
        try {
            activityService.save(activity, null);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activity.getId());
            scheduleBroadcast(new ActivityUpdate("UPDATE", activity, fileMetadatas));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", e.getMessage()));
        }
    }

    @GetMapping("/update/{id}")
    public String updateForm(@PathVariable("id") Long id, Model model) {
        Activity activity = activityService.getById(id);
        List<FileMetadata> files = activityService.getFilesByActivityId(id);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        fileMap.put(activity.getId(), files);
        model.addAttribute("activity", activity);
        model.addAttribute("files", files);
        model.addAttribute("fileMap", fileMap); // Added for consistency with index.html
        model.addAttribute("isUpdate", true);
        return "updateActivity";
    }

    @GetMapping("/save/saveSuccess")
    public String showSaveSuccess() {
        return "saveSuccess";
    }

    @GetMapping("/save/updateSuccess")
    public String showUpdateSuccess() {
        return "updateSuccess";
    }
    @GetMapping(value = "/api/aiSuggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getAiSuggestions() {
        return activityService.getAiSuggestions();
    }
  
    @GetMapping("/api/dashboardStats")
    @ResponseBody
    public Map<String, Object> getDashboardStats() {
        LocalDate startDate = LocalDate.of(2024, 10, 28);
        LocalDate endDate = LocalDate.of(2024, 11, 3);
        return activityService.getDashboardStats(startDate, endDate);
    }
    @GetMapping(value = "/api/dashboardStats", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getDashboardStats(@RequestParam("startDate") String startDate,
                                                @RequestParam("endDate") String endDate) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            return activityService.getDashboardStats(start, end);
        } catch (DateTimeParseException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("errorMessage", "Invalid date format. Please use YYYY-MM-DD.");
            return response;
        }
    }
    @GetMapping("/ai-suggestions")
    public String showAiSuggestions(Model model) {
        try {
            Map<String, Object> suggestions = activityService.getAiSuggestions();
            model.addAttribute("suggestedDate", suggestions.get("suggestedDate"));
            model.addAttribute("predictedParticipants", suggestions.get("predictedParticipants"));
            model.addAttribute("reason", suggestions.get("reason"));
            // Fetch additional data for analytics chart
            LocalDate startDate = LocalDate.now().minusDays(30);
            LocalDate endDate = LocalDate.now().plusDays(30);
            List<Activity> activities = activityService.getActivitiesByDateRange(startDate, endDate);
            
            // Prepare data for chart
            Map<LocalDate, Long> dateParticipantCount = new HashMap<>();
            Map<LocalDate, Integer> dateEventCount = new HashMap<>();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                dateParticipantCount.put(date, 0L);
                dateEventCount.put(date, 0);
            }
            for (Activity activity : activities) {
                LocalDate date = activity.getEventDate();
                dateParticipantCount.compute(date, (k, v) -> v + activity.getNumberOfParticipants());
                dateEventCount.compute(date, (k, v) -> v + 1);
            }
            
            model.addAttribute("dateLabels", dateParticipantCount.keySet().stream()
                .map(LocalDate::toString)
                .collect(Collectors.toList()));
            model.addAttribute("participantData", dateParticipantCount.values());
            model.addAttribute("eventData", dateEventCount.values());
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Failed to load AI suggestions: " + e.getMessage());
        }
        return "ai-suggestions";
    }

    @GetMapping(value = "/api/download/{fileId}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
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
    @GetMapping(value = "/export", produces = "text/csv")
    @ResponseBody
    public String exportCsv(@RequestParam(defaultValue = "") String search,
                            @RequestParam(defaultValue = "") String state,
                            @RequestParam(defaultValue = "") String category,
                            @RequestParam(defaultValue = "") String dateRange,
                            @RequestParam(defaultValue = "id") String sortBy,
                            @RequestParam(defaultValue = "asc") String sortDir) {
        Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE);
        Page<Activity> activityPage = activityService.getAllActivities(search, state, category, dateRange, sortBy, sortDir, pageable);
        StringBuilder csv = new StringBuilder();
        csv.append("ID,State,Station Name,Activity Type,Event Category,Participant Category,Event Description,School/College/Panchayat Name,Event Location,Event Date,Number of Participants,Remarks,Images\n");
        for (Activity activity : activityPage.getContent()) {
            csv.append(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    activity.getId(),
                    activity.getState() != null ? activity.getState().replace("\"", "\"\"") : "",
                    activity.getStationName() != null ? activity.getStationName().replace("\"", "\"\"") : "",
                    activity.getActivityType() != null ? activity.getActivityType().replace("\"", "\"\"") : "",
                    activity.getEventCategory() != null ? activity.getEventCategory().replace("\"", "\"\"") : "",
                    activity.getParticipantCategory() != null ? activity.getParticipantCategory().replace("\"", "\"\"") : "",
                    activity.getEventDescription() != null ? activity.getEventDescription().replace("\"", "\"\"") : "",
                    activity.getSchoolOrCollegeOrPanchayatName() != null ? activity.getSchoolOrCollegeOrPanchayatName().replace("\"", "\"\"") : "",
                    activity.getEventLocation() != null ? activity.getEventLocation().replace("\"", "\"\"") : "",
                    activity.getEventDate() != null ? activity.getEventDate().toString() : "",
                    activity.getNumberOfParticipants(),
                    activity.getRemarks() != null ? activity.getRemarks().replace("\"", "\"\""): ""));
        }
        return csv.toString();
    }
    @GetMapping(value = "/activities/updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public ResponseBodyEmitter streamUpdates(HttpSession session) {
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
    @PostMapping(value = "/api/dynamicCalculation", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> dynamicCalculation(@RequestBody Map<String, Object> calcRequest) {
        try {
            List<Map<String, String>> aggregates = (List<Map<String, String>>) calcRequest.get("aggregates");
            List<String> groupBy = (List<String>) calcRequest.get("groupBy");
            List<Map<String, String>> conditions = (List<Map<String, String>>) calcRequest.get("conditions");
            return activityService.performDynamicCalculation(aggregates, groupBy, conditions);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("errorMessage", "Error performing calculation: " + e.getMessage());
            return response;
        }
    }

    @PostMapping("/extend-session")
    @ResponseBody
    public ResponseEntity<?> extendSession(HttpSession session) {
        session.setMaxInactiveInterval(20 * 60); // Extend session to 20 minutes
        return ResponseEntity.ok().build();
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
        model.addAttribute("state", "");
        model.addAttribute("activityType", "");
        model.addAttribute("eventCategory", "");
        model.addAttribute("filterOperator", "AND");
        model.addAttribute("activities", new ArrayList<Activity>());
        return "dateRangeQuery";
    }

    @PostMapping("/dateRangeQuery")
    public String dateRangeQuery(@RequestParam("startDate") String startDate,
                                @RequestParam("endDate") String endDate,
                                @RequestParam(value = "state", defaultValue = "") String state,
                                @RequestParam(value = "activityType", defaultValue = "") String activityType,
                                @RequestParam(value = "eventCategory", defaultValue = "") String eventCategory,
                                @RequestParam(value = "filterOperator", defaultValue = "AND") String filterOperator,
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
                    // Handle CompletableFuture synchronously
                    CompletableFuture<Map<String, Object>> futureResult = activityService.getActivitiesByDateRangeWithFilters(
                        start, end, state, activityType, eventCategory, filterOperator, new ArrayList<>());
                    Map<String, Object> result = futureResult.join(); // Block to get the result
                    activities = (List<Activity>) result.getOrDefault("activities", new ArrayList<>());
                    String errorMessage = (String) result.get("errorMessage");
                    if (errorMessage != null) {
                        model.addAttribute("errorMessage", errorMessage);
                    }
                }
            }
        } catch (DateTimeParseException e) {
            model.addAttribute("errorMessage", "Invalid date format. Please use YYYY-MM-DD.");
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Error processing query: " + e.getMessage());
        }
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("state", state);
        model.addAttribute("activityType", activityType);
        model.addAttribute("eventCategory", eventCategory);
        model.addAttribute("filterOperator", filterOperator);
        model.addAttribute("activities", activities);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activities.forEach(activity ->
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "dateRangeQuery";
    }

    @PostMapping(value = "/api/dateRangeQuery", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseBodyEmitter dateRangeQueryAjax(@RequestParam("startDate") String startDate,
                                                 @RequestParam("endDate") String endDate,
                                                 @RequestParam(value = "state", defaultValue = "") String state,
                                                 @RequestParam(value = "activityType", defaultValue = "") String activityType,
                                                 @RequestParam(value = "eventCategory", defaultValue = "") String eventCategory,
                                                 @RequestParam(value = "filterOperator", defaultValue = "AND") String filterOperator,
                                                 @RequestParam(value = "conditions", defaultValue = "[]") String conditionsJson) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        try {
            if (startDate.isEmpty() || endDate.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("activities", new ArrayList<>());
                response.put("errorMessage", "Please provide both start and end dates.");
                response.put("rowCount", 0);
                response.put("executionTimeMs", 0L);
                emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                emitter.complete();
                return emitter;
            }
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            if (start.isAfter(end)) {
                Map<String, Object> response = new HashMap<>();
                response.put("activities", new ArrayList<>());
                response.put("errorMessage", "Start date must be before or equal to end date.");
                response.put("rowCount", 0);
                response.put("executionTimeMs", 0L);
                emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                emitter.complete();
                return emitter;
            }
            List<Map<String, String>> conditions = objectMapper.readValue(conditionsJson, List.class);
            activityService.getActivitiesByDateRangeWithFilters(start, end, state, activityType, eventCategory, filterOperator, conditions)
                .thenAccept(response -> {
                    try {
                        emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                .exceptionally(throwable -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("activities", new ArrayList<>());
                    response.put("errorMessage", "Error processing async query: " + throwable.getMessage());
                    response.put("rowCount", 0);
                    response.put("executionTimeMs", 0L);
                    try {
                        emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                    return null;
                });
            return emitter;
        } catch (DateTimeParseException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("activities", new ArrayList<>());
            response.put("errorMessage", "Invalid date format. Please use YYYY-MM-DD.");
            response.put("rowCount", 0);
            response.put("executionTimeMs", 0L);
            try {
                emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
            return emitter;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("activities", new ArrayList<>());
            response.put("errorMessage", "Error processing query: " + e.getMessage());
            response.put("rowCount", 0);
            response.put("executionTimeMs", 0L);
            try {
                emitter.send(objectMapper.writeValueAsString(response), MediaType.APPLICATION_JSON);
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
            return emitter;
        }
    }
  
    // new methods imp: Endpoint for Drag-and-Drop Row Reordering
    @PostMapping(value = "/reorder", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> reorderActivities(@RequestBody List<Map<String, Long>> orderList) {
        activityService.reorderActivities(orderList); // Assume new method in service
        scheduleBroadcast(new ActivityUpdate("REORDER"));
        return ResponseEntity.ok().build();
    }

    // new methods imp: Endpoint for Custom Column Calculations
    @PostMapping(value = "/api/customCalculation", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> customCalculation(@RequestBody Map<String, String> calcRequest) {
        String formula = calcRequest.get("formula");
        String column = calcRequest.get("column");
        return activityService.performCustomCalculation(column, formula); // Assume new method
    }
    @PostMapping(value = "/api/batchQuery", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> batchQuery(@RequestBody Map<String, List<Map<String, String>>> batchRequest) {
        try {
            List<Map<String, String>> queries = batchRequest.get("queries");
            if (queries == null || queries.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("errorMessage", "No queries provided."));
            }

            LocalDate startDate = LocalDate.of(2024, 10, 28);
            LocalDate endDate = LocalDate.of(2024, 11, 3);
            List<Map<String, Object>> results = new ArrayList<>();

            for (Map<String, String> query : queries) {
                String state = query.getOrDefault("state", "");
                String eventCategory = query.getOrDefault("eventCategory", "");
                String operator = query.getOrDefault("operator", "AND");

                long startTime = System.currentTimeMillis();
                Map<String, Object> result = activityService.getActivitiesByDateRangeWithFilters(
                    startDate, endDate, state, "", eventCategory, operator, new ArrayList<>()
                ).join(); // Block to get synchronous results
                long executionTime = System.currentTimeMillis() - startTime;

                List<Activity> activities = (List<Activity>) result.getOrDefault("activities", new ArrayList<>());
                Map<String, Object> queryResult = new HashMap<>();
                queryResult.put("activities", activities);
                queryResult.put("totalActivities", activities.size());
                queryResult.put("totalParticipants", activities.stream().mapToLong(Activity::getNumberOfParticipants).sum());
                queryResult.put("executionTimeMs", executionTime);
                queryResult.put("query", Map.of("state", state, "eventCategory", eventCategory, "operator", operator));
                results.add(queryResult);
            }

            return ResponseEntity.ok(Map.of("results", results));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("errorMessage", "Error processing batch query: " + e.getMessage()));
        }
    }
    
    @GetMapping("/confirmExit")
    public String confirmExit() {
        return "confirmExit";
    }

    @GetMapping("/exitProgram")
    public String exitProgram(@RequestParam("confirmExit") String confirmExit) {
        if ("yes".equalsIgnoreCase(confirmExit)) {
            return "redirect:/";
        } else {
            return "redirect:/activity";
        }
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
}