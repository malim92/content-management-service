# Content Management Service

A small Spring Boot CMS service for managing and retrieving website content.
Focus: API design, clean code, PostgreSQL, Redis caching, concurrency safety,
and high-performance data access.

- **Java 21**, Spring Boot 3.3, Maven
- **PostgreSQL** (via JPA/Hibernate + Flyway migrations)
- **Redis** (cache-aside with graceful degradation)
- **JUnit 5** unit, web-layer and Testcontainers integration tests

## Quick start

```bash
# Option A: full stack with Docker
docker compose up --build

# Option B: local run (needs PostgreSQL on 5432 and Redis on 6379)
mvn spring-boot:run
```

The service starts on `http://localhost:8080`.

## API

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/v1/cms/contents` | Create content (201) |
| GET    | `/api/v1/cms/contents/{id}` | Get content by id (Redis-cached) |
| PUT    | `/api/v1/cms/contents/{id}` | Update content (optimistic locking) |
| DELETE | `/api/v1/cms/contents/{id}` | Delete content (204) |
| GET    | `/api/v1/cms/contents` | Search: `language`, `status`, `key` (prefix), `page`, `size` |

Example content:

```json
{
  "key": "homepage.banner",
  "title": "Welcome",
  "content": "Welcome to our website",
  "language": "en",
  "status": "PUBLISHED"
}
```

Search example: `GET /api/v1/cms/contents?language=en&status=PUBLISHED&page=0&size=20`

Every success response uses a consistent envelope:

```json
{ "success": true, "message": "OK", "data": { ... }, "timestamp": "..." }
```

Every error uses a consistent envelope:

```json
{ "success": false, "code": "CONTENT_NOT_FOUND", "message": "...", "details": [], "path": "...", "timestamp": "..." }
```

## Design decisions

### Redis caching strategy

Cache-aside (lazy population) for the read-by-id path, implemented in
`ContentCache` / `ContentService`:

```
Request -> Redis --hit--> return content
                \--miss--> PostgreSQL -> write back to Redis -> Response
```

- Values are stored as JSON (`ContentResponse`) with a **10-minute TTL plus
  ±20% random jitter** to avoid a load of simultaneous expirations.
- TTL is only a safety net: every write explicitly invalidates the affected
  entry, so the cache is normally consistent within a single request of the
  database state.
- The search/list endpoint is **not** cached. Search traffic is diverse and
  paginated, so each page is a single cheap indexed query; caching page results
  would add cross-entry invalidation complexity for little gain.

### Cache invalidation

Write operations follow **database-first, then invalidate**:

1. Commit the change to PostgreSQL inside the transaction.
2. After the commit, delete the Redis entry (`cache.evict`).

Invalidation (delete, not update) is used because it is simpler and race-safe:
a concurrent reader that re-populates the cache between the commit and the
evict will simply populate with the now-current value. Creates *warm* the
cache with the freshly persisted value.

### Database indexing

Owned by Flyway migration `V1__init_schema.sql`:

- Primary key: identity `id`.
- **Unique constraint** on `(content_key, language)` — the same logical key
  may exist once per language; this also enforces duplicate-key detection at
  the database level (the application pre-check plus this constraint closes
  the check-then-insert race).
- Indexes on `status`, `language`, and `created_at` (the default search sort).
- `version` column for optimistic locking; status restricted by a CHECK
  constraint.

### Concurrent update handling

**Optimistic locking with the `@Version` column:**

- Every response includes `version`.
- `PUT` requires `expectedVersion` — the version the client last saw.
- If it no longer matches (another admin committed a change first), the update
  is rejected with **409 `CONCURRENT_UPDATE`** and the client is told to
  refresh and retry. One administrator can never silently overwrite another's
  changes; the last writer does **not** win automatically.
- The service compares versions before flushing and also converts
  `OptimisticLockException` (lost race between read and write) into 409.

### Redis failure handling

All Redis operations in `ContentCache` are wrapped: on any Redis exception the
cache degrades to "miss" (reads fall through to PostgreSQL, writes are skipped)
and the request continues normally — the application keeps working safely in
database-only mode while Redis is down, and picks the cache back up
automatically when it recovers. Redis failures are logged as warnings, never
propagated to clients.

### Performance considerations (10k+ reads/minute)

- **Redis cache-aside** absorbs the read load: cached reads cost no database
  connection at all.
- **HikariCP pool** sized moderately (20) — cached traffic doesn't consume
  connections; pool-too-large hurts, not helps.
- **Single-query search** via JPA Specifications with pagination (`page`/`size`,
  size capped at 100) instead of in-memory filtering.
- **Indexes** on all filter/sort columns; unique index doubles as the
  duplicate-key guard.
- **`open-in-view: false`** so no lazy-loading surprises or extra queries
  outside transactions.
- **Lean JSON payloads**: cached values are stored already-serialized DTOs, so
  cache hits skip entity mapping entirely.
- Flyway-owned schema (`ddl-auto: validate`) keeps startup predictable.

## Error handling

| Scenario | HTTP | Code |
|----------|------|------|
| Content not found | 404 | `CONTENT_NOT_FOUND` |
| Duplicate (key, language) | 409 | `DUPLICATE_CONTENT_KEY` |
| Stale `expectedVersion` | 409 | `CONCURRENT_UPDATE` |
| Bean validation failure (blank fields, bad language tag) | 400 | `INVALID_REQUEST` (with field details) |
| Invalid enum value (e.g. `status=UNKNOWN`) | 400 | `INVALID_REQUEST` |
| Database error | 500 | `DATABASE_ERROR` |
| Redis unavailable | *(none — degraded to database-only)* | |
| Anything unexpected | 500 | `INTERNAL_ERROR` |

## Tests

```bash
mvn test
```

- `ContentServiceTest` — unit tests for create/get/update/delete, cache hit and
  miss, invalidation on write, duplicate key (including the constraint race),
  concurrent update, pagination.
- `ContentCacheTest` — cache get/put/evict and Redis-unavailable degradation.
- `ContentControllerTest` — web-layer tests for all HTTP statuses and the
  consistent error envelope.
- `ContentApiIntegrationTest` — full end-to-end tests against real PostgreSQL
  and Redis (Testcontainers), covering the full lifecycle including cache
  invalidation and optimistic locking. Automatically skipped when Docker is
  unavailable (`@Testcontainers(disabledWithoutDocker = true)`), so unit test
  runs never fail for lack of Docker.

## Configuration

Environment variables (see `application.yml` for defaults):

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/cms` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `cms` / `cms` | Database credentials |
| `DB_POOL_SIZE` | `20` | HikariCP maximum pool size |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection |
| `SERVER_PORT` | `8080` | HTTP port |
