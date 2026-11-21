package com.example.mockbackend.repository;

import com.example.mockbackend.domain.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Share links in the H2 database (table "share_links"), keyed by token. */
public interface ShareLinkRepository extends JpaRepository<ShareLink, String> {

    /** Every link of a job, active or not (revoking all links when the job goes away). */
    List<ShareLink> findByJobId(String jobId);
}
