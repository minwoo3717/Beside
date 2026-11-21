package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The secondary indexes the hot queries rely on (ddl-auto=update creates them, also in existing files):
 * the job list (keyset paging), the queue position asked by every PENDING poll, the library's jobs of a pet, and the
 * job_runs lookups by job and by time (job page, admin runs/stats).
 */
@SpringBootTest
class SchemaIndexTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void hotQueriesHaveTheirIndexes() {
        assertThat(indexes("JOBS")).contains("IDX_JOBS_CREATED_AT_ID", "IDX_JOBS_STATUS_QUEUED_AT_ID", "IDX_JOBS_PET_ID");
        assertThat(indexes("JOB_RUNS")).contains("IDX_JOB_RUNS_JOB_ID", "IDX_JOB_RUNS_FINISHED_AT");
    }

    private List<String> indexes(String table) {
        return jdbc.queryForList("select index_name from information_schema.indexes where table_name = ?", String.class, table)
                .stream().map(name -> name.toUpperCase(Locale.ROOT)).toList();
    }
}
