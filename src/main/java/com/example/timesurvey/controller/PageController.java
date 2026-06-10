package com.example.timesurvey.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {

    /** 調查連結：/s/{調查ID} → 調查填寫頁 */
    @GetMapping("/s/{id}")
    public String surveyPage(@PathVariable String id) {
        return "forward:/survey.html";
    }
}
