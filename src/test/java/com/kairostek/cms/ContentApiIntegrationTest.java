package com.kairostek.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API test against real PostgreSQL and Redis containers.
 *
 * <p>Skipped automatically when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}), so `mvn test` still
 * works on machines without Docker; in CI with Docker these tests provide full
 * cache-hit/miss, invalidation, optimistic-lock and pagination coverage
 * against the real stack.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentApiIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cms")
            .withUsername("cms")
            .withPassword("cms");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.data.redis.core.RedisTemplate<String, com.kairostek.cms.dto.ContentResponse> redisTemplate;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static long createdId;
    private static long createdVersion;

    @Test
    @Order(1)
    @DisplayName("Create content returns 201")
    void create() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cms/contents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "Welcome",
                                  "content": "Welcome to our website",
                                  "language": "en",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key").value("homepage.banner"))
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        createdId = body.at("/data/id").asLong();
        createdVersion = body.at("/data/version").asLong();
        assertThat(createdId).isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("Duplicate key returns 409")
    void duplicateKey() throws Exception {
        mockMvc.perform(post("/api/v1/cms/contents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "Welcome again",
                                  "content": "duplicate",
                                  "language": "en",
                                  "status": "DRAFT"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT_KEY"));
    }

    @Test
    @Order(3)
    @DisplayName("Get by id works and is served from Redis after the first read")
    void getWithCache() throws Exception {
        // First read: cache miss -> database -> cache populated
        mockMvc.perform(get("/api/v1/cms/contents/" + createdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Welcome"));

        // The entry must now exist in Redis with the correct value and a TTL.
        assertThat(redisTemplate.hasKey("cms:content:" + createdId)).isTrue();
        assertThat(redisTemplate.opsForValue().get("cms:content:" + createdId).title()).isEqualTo("Welcome");
        assertThat(redisTemplate.getExpire("cms:content:" + createdId)).isPositive();

        // Second read must be a cache hit; result identical.
        mockMvc.perform(get("/api/v1/cms/contents/" + createdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Welcome"));
    }

    @Test
    @Order(4)
    @DisplayName("Update with the correct version succeeds; stale version returns 409")
    void optimisticLocking() throws Exception {
        // Stale version first: must be rejected.
        mockMvc.perform(put("/api/v1/cms/contents/" + createdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Stale admin",
                                  "content": "must lose",
                                  "status": "DRAFT",
                                  "expectedVersion": %d
                                }
                                """.formatted(createdVersion + 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        // Correct version: succeeds, version increments.
        MvcResult result = mockMvc.perform(put("/api/v1/cms/contents/" + createdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Updated title",
                                  "content": "Updated body",
                                  "status": "PUBLISHED",
                                  "expectedVersion": %d
                                }
                                """.formatted(createdVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Updated title"))
                .andReturn();

        createdVersion = mapper.readTree(result.getResponse().getContentAsString()).at("/data/version").asLong();
    }

    @Test
    @Order(5)
    @DisplayName("Cache invalidation: after an update, reads return the new value")
    void cacheInvalidationAfterUpdate() throws Exception {
        mockMvc.perform(get("/api/v1/cms/contents/" + createdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Updated title"));

        // The re-populated cache entry must hold the new value, not the stale one.
        assertThat(redisTemplate.opsForValue().get("cms:content:" + createdId).title())
                .isEqualTo("Updated title");
    }

    @Test
    @Order(6)
    @DisplayName("Search with filters and pagination returns matching results")
    void search() throws Exception {
        mockMvc.perform(get("/api/v1/cms/contents")
                        .param("language", "en")
                        .param("status", "PUBLISHED")
                        .param("key", "homepage")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].key").value("homepage.banner"));
    }

    @Test
    @Order(7)
    @DisplayName("Delete works; subsequent reads return 404")
    void deleteContent() throws Exception {
        mockMvc.perform(delete("/api/v1/cms/contents/" + createdId))
                .andExpect(status().isNoContent());

        // The cache entry must have been evicted together with the row.
        assertThat(redisTemplate.hasKey("cms:content:" + createdId)).isFalse();

        mockMvc.perform(get("/api/v1/cms/contents/" + createdId))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(8)
    @DisplayName("Concurrent updates: N admins PUT the same version at once - exactly one wins")
    void concurrentUpdates() throws Exception {
        // A dedicated item for this test.
        MvcResult created = mockMvc.perform(post("/api/v1/cms/contents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "concurrency.test",
                                  "title": "Base title",
                                  "content": "Base body",
                                  "language": "en",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long targetId = mapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();

        // All attackers read the same current version...
        MvcResult current = mockMvc.perform(get("/api/v1/cms/contents/" + targetId))
                .andExpect(status().isOk())
                .andReturn();
        long version = mapper.readTree(current.getResponse().getContentAsString()).at("/data/version").asLong();

        // ...and fire PUTs with that same expectedVersion simultaneously.
        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < attackers; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(put("/api/v1/cms/contents/" + targetId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "title": "Winner writes this",
                                          "content": "only one commit may succeed",
                                          "status": "PUBLISHED",
                                          "expectedVersion": %d
                                        }
                                        """.formatted(version)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        long successes = 0;
        for (Future<Integer> f : futures) {
            if (f.get(30, TimeUnit.SECONDS) == 200) {
                successes++;
            }
        }
        pool.shutdownNow();

        // Optimistic locking must guarantee: exactly one winner, everyone else 409.
        assertThat(successes).isEqualTo(1);

        // And the stored value must be a single consistent update.
        mockMvc.perform(get("/api/v1/cms/contents/" + targetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Winner writes this"));
    }
}
