package com.klu;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        // Add file metadata for each activity
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
                              Model model) throws IllegalArgumentException {
        try {
            boolean isUpdate = activity.getId() != null;
            activityService.save(activity, files);
            List<FileMetadata> fileMetadatas = activityService.getFilesByActivityId(activity.getId());
            scheduleBroadcast(new ActivityUpdate(isUpdate ? "UPDATE" : "ADD", activity, fileMetadatas));
            return isUpdate ? "redirect:/save/updateSuccess" : "redirect:/save/saveSuccess";
        } catch (RuntimeException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("activity", activity);
            model.addAttribute("isUpdate", activity.getId() != null); // Add isUpdate to model
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
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
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
            return ResponseEntity.ok(Map.of("message", "All files deleted successfully for activity ID: " + activityId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to delete files: " + e.getMessage()));
        }
    }

    @GetMapping("/activities/search-suggestions")
    @ResponseBody
    public List<String> getSearchSuggestions(@RequestParam("term") String term) {
        return activityService.getSearchSuggestions(term);
    }

    @PostMapping("/extend-session")
    @ResponseBody
    public void extendSession(HttpSession session) {
        session.setMaxInactiveInterval(20 * 60);
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
        csv.append("ID,State,Station Name,Activity Type,Event Category,Participant Category,Event Description,School/College/Panchayat Name,Event Location,Event Date,Number of Participants,Remarks\n");
        for (Activity activity : activityPage.getContent()) {
            csv.append(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
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
                    activity.getRemarks() != null ? activity.getRemarks().replace("\"", "\"\"") : ""));
        }
        return csv.toString();
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
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to upload files: " + e.getMessage()));
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
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to delete file: " + e.getMessage()));
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
        model.addAttribute("activities", List.of());
        return "stateCategoryQuery";
    }

    @PostMapping("/stateCategoryQuery")
    public String stateCategoryQuery(@RequestParam("state") String state, 
                                    @RequestParam("eventCategory") String eventCategory, 
                                    Model model) {
        List<Activity> activities = activityService.getActivitiesByStateAndEventCategory(state, eventCategory);
        model.addAttribute("state", state);
        model.addAttribute("eventCategory", eventCategory);
        model.addAttribute("activities", activities);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activities.forEach(activity -> 
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "stateCategoryQuery";
    }

    @GetMapping("/dateRangeQuery")
    public String dateRangeQueryForm(Model model) {
        model.addAttribute("startDate", "");
        model.addAttribute("endDate", "");
        model.addAttribute("activities", List.of());
        return "dateRangeQuery";
    }

    @PostMapping("/dateRangeQuery")
    public String dateRangeQuery(@RequestParam("startDate") String startDate, 
                                 @RequestParam("endDate") String endDate, 
                                 Model model) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        List<Activity> activities = activityService.getActivitiesByDateRange(start, end);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("activities", activities);
        Map<Long, List<FileMetadata>> fileMap = new HashMap<>();
        activities.forEach(activity -> 
            fileMap.put(activity.getId(), activityService.getFilesByActivityId(activity.getId())));
        model.addAttribute("fileMap", fileMap);
        return "dateRangeQuery";
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