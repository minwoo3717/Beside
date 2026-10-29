package com.example.mockbackend.repository;

import com.example.mockbackend.domain.ClientMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Imported app measurements (docs/METRICS.md §3.1). Written only by ClientMetricService's import. */
public interface ClientMetricRepository extends JpaRepository<ClientMetric, Long> {

    /** Import order (= the order of the app's file). */
    List<ClientMetric> findAllByOrderByIdAsc();

    /** Stored rows of these jobs, for the duplicate check of an import. */
    List<ClientMetric> findByJobIdIn(Collection<String> jobIds);
}
