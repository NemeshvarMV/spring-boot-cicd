package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HelloController {

    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Spring Boot CI/CD application is running successfully on Docker + EC2!");
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("endpoints", Map.of(
                "students_list", "/api/students",
                "create_student", "/api/students (POST)"
        ));
        return response;
    }
}
