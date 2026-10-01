# Mock Backend for 3D Generation (Spring Boot)

Simple mock backend that accepts pet photos, creates a Job, and after a delay returns a sample GLB file.

Run:

1. Build and run with Gradle wrapper (or your IDE):

```bash
./gradlew bootRun
```

2. Create job (curl example):

```bash
curl -v -F "photos=@/path/to/pet1.jpg" -F "photos=@/path/to/pet2.jpg" http://localhost:8080/api/jobs
```

Response: 202 Accepted, Location header contains `/api/jobs/{jobId}`

3. Check status:

```bash
curl http://localhost:8080/api/jobs/{jobId}
```

4. Download result (only when status is COMPLETED):

```bash
curl -O http://localhost:8080/api/jobs/{jobId}/result
```
