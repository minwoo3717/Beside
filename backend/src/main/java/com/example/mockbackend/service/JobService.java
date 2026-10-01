package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;

public interface JobService {
    Job createJob(Job job);
    Job getJob(String id);
}
