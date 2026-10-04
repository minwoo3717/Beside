package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;

import java.util.List;

/** One page of the job list; {@code nextCursor} is null on the last page. */
public record JobPage(List<Job> items, String nextCursor) {
}
