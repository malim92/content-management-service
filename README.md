# Content Management Service

A small Spring Boot service for managing website content (CMS).
Built with Java 21, Spring Boot, PostgreSQL and Redis.

## Setup and run

You need Docker. Then just run:

```bash
docker compose up --build
```

This starts PostgreSQL, Redis and the app. Once everything is up the API
is on `http://localhost:8080`. Stop with `Ctrl+C` (or `docker compose down`).

For local development you can also run the app directly against the
database and redis containers:

```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

Config is done through environment variables (see `application.yml` for the
defaults): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`, `SERVER_PORT`.

## API

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/v1/cms/contents` | Create content (returns 201) |
| GET    | `/api/v1/cms/contents/{id}` | Get one content (served from cache) |
| PUT    | `/api/v1/cms/contents/{id}` | Update content (needs `expectedVersion`) |
| DELETE | `/api/v1/cms/contents/{id}` | Delete content (returns 204) |
| GET    | `/api/v1/cms/contents` | Search by `language`, `status`, `key`, with `page` and `size` |

Example request body:

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

All success responses look like this:

```json
{ "success": true, "message": "OK", "data": { ... }, "timestamp": "..." }
```

And all errors like this:

```json
{ "success": false, "code": "CONTENT_NOT_FOUND", "message": "...",
  "details": [], "path": "...", "timestamp": "..." }
```

Error codes: 404 `CONTENT_NOT_FOUND`, 409 `DUPLICATE_CONTENT_KEY`,
409 `CONCURRENT_UPDATE`, 400 `INVALID_REQUEST` (with field details),
500 `DATABASE_ERROR` / `INTERNAL_ERROR`.

Quick smoke test:

```bash
curl -X POST http://localhost:8080/api/v1/cms/contents \
  -H 'Content-Type: application/json' \
  -d '{"key":"homepage.banner","title":"Welcome","content":"Welcome to our website","language":"en","status":"PUBLISHED"}'

curl http://localhost:8080/api/v1/cms/contents/1
curl "http://localhost:8080/api/v1/cms/contents?language=en&status=PUBLISHED&page=0&size=20"

curl -X PUT http://localhost:8080/api/v1/cms/contents/1 \
  -H 'Content-Type: application/json' \
  -d '{"title":"New title","content":"New body","status":"PUBLISHED","expectedVersion":0}'

curl -X DELETE http://localhost:8080/api/v1/cms/contents/1
```

## How it works

### Redis caching

Reads by id go through a simple cache-aside flow: check Redis first,
and only hit the database when the key is not there. On a miss the result
is written back to Redis so the next read is a hit.

Cached entries live for 10 minutes. I added a random jitter of up to 20%
on top of that so entries don't all expire at the same time and stampede
the database. The TTL is just a backup though - the real consistency comes
from invalidation on every write (see below).

The search endpoint is not cached on purpose. Searches vary a lot and each
page is already a fast indexed query, so caching page results would cause
more problems than it solves.

### Cache invalidation

Writes go to the database first, and only then the Redis entry is deleted.
I delete instead of update because it's simpler and handles races well -
if a reader re-populates the cache a moment before the delete, they just
cache the new value. Creating content also puts it straight into the cache,
so a freshly created item is a cache hit on its very first read.

### Database indexing

The schema is in `src/main/resources/db/migration/V1__init_schema.sql`
(managed by Flyway, Hibernate only validates it).

- `id` is the primary key (auto-increment)
- unique constraint on `(content_key, language)` so the same key can only
  exist once per language. The app checks for duplicates before inserting to
  return a nice 409, but this constraint is what actually guarantees it -
  it even covers the case where two requests insert at the exact same time
- indexes on `status`, `language` and `created_at` (the search sort order)

### Concurrent updates

The entity has a `version` column with optimistic locking. Every response
includes the current version, and a PUT must send it back as
`expectedVersion`. If it doesn't match, someone else saved a change in
between and the update is rejected with 409 `CONCURRENT_UPDATE`. The client
just needs to fetch the content again and retry.

So if two admins edit the same content at the same time, one write wins and
the other gets a clear error instead of silently overwriting the first change.

### Redis failures

If Redis goes down, the app keeps working. Every cache call is wrapped, so
on a Redis error the read just falls through to the database and the write
is skipped. Requests are never failed because of Redis; you just lose the
caching benefit until it comes back. There is a warning in the logs when
this happens.

### Performance

- cached reads never touch the database, so they don't use connections either
- HikariCP pool is 20 connections - more wouldn't help since reads mostly
  come from cache
- search runs as a single SQL query with pagination, no in-memory filtering
- `open-in-view` is off so nothing lazy-loads outside transactions
- cache hits skip entity mapping, the cached value is already the response DTO

## Testing

### Unit and integration tests

```bash
mvn test
```

- `ContentServiceTest` - create/get/update/delete, cache hit & miss,
  invalidation, duplicate key, concurrent update, pagination
- `ContentCacheTest` - cache put/get/evict, and behavior when Redis is down
- `ContentControllerTest` - HTTP status codes and the error response shape
- `ContentApiIntegrationTest` - full end-to-end tests against real PostgreSQL
  and Redis (Testcontainers), including a 10-thread concurrent update race

The integration tests skip themselves automatically if Docker is not
running, so `mvn test` passes anywhere and runs them fully in CI.

### Test scripts

```bash
# 10 parallel PUTs with the same version - exactly one should succeed,
# the rest get 409
bash scripts/test-concurrent-update.sh http://localhost:8080

# ~170 requests/sec (~10k/min) against the cached read endpoint for 2 min,
# using k6 in Docker
bash scripts/load-test-10k-per-min.sh 1
```

While the load test runs, open `docker stats` in another terminal. If
PostgreSQL stays near idle while the app serves thousands of requests,
the cache is doing its job.

### Checking the cache by hand

```bash
# see every command the app sends to Redis
docker exec -it content-management-service-redis-1 redis-cli monitor

# look at a cached entry and its TTL (600-720s = 10min + jitter)
docker exec -it content-management-service-redis-1 redis-cli GET cms:content:1
docker exec -it content-management-service-redis-1 redis-cli TTL cms:content:1

# overall hit/miss counters
docker exec -it content-management-service-redis-1 redis-cli INFO stats | grep keyspace
```
On a cache hit you should see `CACHE HIT` in the logs and no SQL query.