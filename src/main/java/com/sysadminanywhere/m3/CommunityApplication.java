package com.sysadminanywhere.m3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CommunityApplication {
    public static void main(String[] args) { SpringApplication.run(CommunityApplication.class, args); }
}
