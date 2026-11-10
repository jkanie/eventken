# eventken

Event search in Kotlin. A server reads public event listings from iCalendar feeds and from schema.org Event data in web pages, merges the same event seen in 2 sources, then serves full-text search with filters for distance, dates, categories. An Android app and a desktop app, built on 1 shared Kotlin Multiplatform module, search events, keep saved events for offline use, set reminders.

The name: "ken" is the range of what you can see and know (Old English *cennan*, Old Norse *kenna*), and eventken widens it for what is on near you.

## What it does

- Reads 2 kinds of source, only from URLs an admin adds:
  - iCalendar (`.ics`) files as RFC 5545 describes them: folded lines, quoted parameters, escaped text, `TZID`, all-day dates, `DURATION`, `GEO`, `CATEGORIES`, `STATUS:CANCELLED`.
  - Recurring events: `RRULE` (`DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY` with `INTERVAL`, `COUNT`, `UNTIL`, `WKST`, `BYDAY` such as `2TU` or `-1FR`, `BYMONTHDAY`, `BYMONTH`), `RDATE`, `EXDATE`. Each occurrence that has not ended and starts within the next 12 months becomes its own listing, with the external id `UID#start` (the start in UTC, or the date of an all-day event). A changed instance (`RECURRENCE-ID`) replaces the occurrence it names. Each occurrence keeps the wall-clock time of the first one in its time zone, so a class at 18:00 stays at 18:00 when daylight saving time starts or ends.
  - Web pages with schema.org `Event` objects in `<script type="application/ld+json">` tags: single objects, arrays, `@graph` lists, every Event subtype (`MusicEvent` becomes the category `music`, `SportsEvent` becomes `sport`), online events.
- Merges the same event from 2 feeds into 1 search result. 2 listings match when they pass 3 tests:
  - They start within 30 minutes of each other.
  - Their titles share at least half the words of the shorter title (4 words in 5 when there is no place to compare).
  - Their places agree: within 1 km, or the same venue name.

  The feed added first leads, gaps fill from the others, the longest description wins, categories add up.
- Searches with PostgreSQL full-text search: web search syntax (`"lantern walk"`, `trail -beginner`), title words ranked above description words.
- Adds distance from a point, filters by radius, sorts nearest first. Filters by date range (by default, events that have not ended yet) and by category. Hides cancelled events unless asked.
- Fetches all feeds on a schedule, up to 4 at once, with conditional requests (`ETag`, `Last-Modified`) so an unchanged feed costs the publisher 1 short reply. Listings that leave a feed leave the search too.
- Refuses feed URLs that point at private, loopback, link-local, reserved addresses. It checks when an admin saves the URL, then again on every connection, after DNS and after each redirect. `ALLOW_PRIVATE_TARGETS` turns this off for local work.
- Obeys robots.txt (RFC 9309) and names itself in every request: `eventken/0.1 (+https://github.com/jkanie/eventken)`.
- Protects admin endpoints with a bearer token from the environment. With no token set, they answer 503.
- Describes the JSON API in OpenAPI 3, with a Swagger UI at `/docs`.

The apps:

- Android (Jetpack Compose): a list of results, "Near me" from the device location, saved events that open without a connection, reminders through WorkManager notifications, add to calendar through the system calendar app (no calendar permission needed).
- Desktop (Compose Multiplatform): search with filters for location, radius, category, an event pane, saved events, plus an organiser screen to add feeds, fetch them now, remove them, all with the admin token.

## How it fits together

```
 Android app (Compose)           desktop app (Compose Multiplatform)
          \                                 /
           +--- shared (Kotlin Multiplatform: JVM + Android) ---+
           |  models, SearchQuery rules, reminder rules,         |
           |  Ktor client, SQLDelight saved-event cache,         |
           |  SearchModel + OrganiserModel screen state          |
           +------------------------+----------------------------+
                                    | JSON over HTTP
           +------------------------v----------------------------+
           |  server (Ktor, Netty)                                |
           |  api: search, event, categories, admin feeds         |
           |  ingest: fetch (guard, robots.txt) -> parse ->       |
           |          match -> merge -> store                     |
           +------------------------+----------------------------+
                                    |
                             PostgreSQL (tsvector, haversine SQL)
```

The server uses the shared module too, for the API models and for `SearchQuery`, so the apps and the server check a query by the same rules and give the same messages.

## Built with

Every version is the newest stable release in its repository (Maven Central, Google Maven, the Gradle plugin portal) as of 2026-11-09.

| Part | Version | Why |
|---|---|---|
| Gradle (wrapper, checked by SHA-256) | 9.9.0 | Builds every module with 1 command. |
| Kotlin | 2.4.21 | 1 language for server, shared code, both apps. |
| JDK | 21 | The toolchain for every JVM module. CI uses Temurin 21. |
| Ktor (server on Netty, client on OkHttp) | 3.6.1 | A small, coroutine-native HTTP stack on both sides. |
| kotlinx.coroutines | 1.11.0 | Parallel feed fetches, the ingest schedule, app state. |
| kotlinx.serialization | 1.12.0 | JSON for the API and for stored listings. |
| kotlinx-datetime | 0.8.0 | Time zones for reminders and display. Ktor's OpenAPI schema module asks for the `0.8.0-0.6.x-compat` build, so Gradle resolves that build. |
| PostgreSQL | 17 (Docker, CI), also tested on 16 | Full-text search, arrays, advisory locks. |
| PostgreSQL JDBC driver | 42.7.14 | |
| HikariCP | 7.1.0 | Connection pool. |
| Flyway | 13.12.1 | Plain SQL migrations in `server/src/main/resources/db/migration`. |
| OkHttp | 5.5.1 | Feed fetches, with a DNS hook for the address guard. |
| jsoup | 1.24.1 | Finds JSON-LD script tags in HTML. |
| Logback | 1.6.6 | Logging. |
| SQLDelight | 2.4.1 | Typed SQL for the saved-event cache on both apps. |
| Compose Multiplatform | 1.12.2 | Desktop UI. |
| Compose Material 3 (multiplatform) | 1.9.0 | Desktop components. Compose Multiplatform 1.12.2 pairs with a 1.12.0 alpha, so the build uses the newest stable release instead. |
| Android Gradle plugin | 9.5.1 | Android build, with built-in Kotlin. |
| Jetpack Compose BOM | 2026.09.00 | Android UI. |
| AndroidX Activity Compose, Core, WorkManager | 1.13.0, 1.19.1, 2.12.0 | Activity, notifications, reminders. |
| Android SDK | compileSdk 37, targetSdk 37, minSdk 26, build-tools 36.0.0 | Compose 1.12 from the BOM needs compileSdk 37 or later: AGP's AAR metadata check refuses 36. GitHub's `ubuntu-latest` image ships android-37.0 with build-tools 36.0.0 ([runner image list](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)), so CI downloads no platform. |
| JUnit | 6.1.3 (server), 4.13.2 (Robolectric) | |
| Testcontainers | 2.0.5 | A real PostgreSQL for server tests. |
| Robolectric | 4.17 | Android tests on the JVM, no emulator, on the Android 17 (API 37) runtime. |
| AndroidX Test Espresso | 3.7.0 | Compose UI tests tap through Espresso. The 3.5.0 that the Compose test library brings in fails on the API 37 runtime. |
| ktlint (Gradle plugin) | 1.8.0 (14.2.0) | 1 code style, checked in CI. |

### Why plain JDBC

The queries that matter here are PostgreSQL SQL: `websearch_to_tsquery` with weighted `tsvector` ranking, a haversine distance with a bounding-box filter, `text[]` overlap for categories, `pg_advisory_xact_lock` around ingest. An ORM such as Exposed would wrap these in raw SQL fragments anyway, and jOOQ pays off with generated code, which is more machinery than 3 tables need. So the SQL stays as SQL text. A helper of about 150 lines (`server/.../db/Db.kt`) opens connections, binds parameters, runs transactions. Every value from a request goes in as a parameter.

### Why haversine SQL, not earthdistance or PostGIS

The distance is the haversine formula in plain SQL, the same formula the apps use (`shared/.../geo/Geo.kt`), so both sides show the same number. It needs no extension, so it runs on any PostgreSQL, including managed services that limit extensions. A latitude and longitude box narrows rows first through a B-tree index. The spherical model is correct to within 0.5% ([haversine formula](https://en.wikipedia.org/wiki/Haversine_formula)), which is fine for "how far is this event". ROADMAP.md item 3 plans a spatial index for large data sets.

## Set-up

You need JDK 21 and Docker (or a PostgreSQL 16 or newer). The Android app also needs the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`) with its licence accepted. If you only work on the server or the desktop app, you can skip that: without an SDK, the build leaves out `:androidApp` and the Android target of `shared`, prints 1 line saying so, builds everything else. `-Peventken.android=false` does the same on a machine that has an SDK.

Start PostgreSQL, load the sample feeds, then run the server:

```sh
docker compose up -d
./gradlew :server:devSeed
export EVENTKEN_ADMIN_TOKEN='example-token-change-me-0123456789'   # made-up example value
./gradlew :server:run
```

Search:

```sh
curl 'http://localhost:8080/api/events?q=concert&from=2027-01-01T00:00:00Z'
curl 'http://localhost:8080/api/events?lat=48.4236&lon=-123.3702&radiusKm=2&sort=distance&from=2027-01-01T00:00:00Z'
curl 'http://localhost:8080/api/events?category=sport&from=2027-07-01T00:00:00Z&to=2027-07-31T23:59:59Z'
curl 'http://localhost:8080/api/categories'
```

The sample events take place in July 2027, so the examples pass `from`. Open http://localhost:8080/docs for the API in Swagger UI.

To fetch the sample feeds over real HTTP instead of from disk, serve the fixtures folder and let the server reach loopback addresses:

```sh
./gradlew :server:fixtureHost &          # serves fixtures/ on http://127.0.0.1:8089
ALLOW_PRIVATE_TARGETS=true ./gradlew :server:run
curl -X POST -H "Authorization: Bearer $EVENTKEN_ADMIN_TOKEN" http://localhost:8080/api/admin/ingest
```

The seed already registered those URLs as feeds. The report lists 1 result per feed.

Add a feed of your own:

```sh
curl -X POST -H "Authorization: Bearer $EVENTKEN_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/events.ics","kind":"ICAL","defaultCategory":"community"}' \
  http://localhost:8080/api/admin/feeds
```

### Desktop app

```sh
./gradlew :desktopApp:run                                   # talks to http://localhost:8080
EVENTKEN_API_URL=https://events.example.org ./gradlew :desktopApp:run
```

Saved events live in `~/.eventken/saved-events.db`. The admin token on the organiser screen stays in memory only.

### Android app

```sh
./gradlew :androidApp:assembleDebug                                  # API at http://10.0.2.2:8080 (the host, from the emulator)
./gradlew :androidApp:assembleDebug -Peventken.apiUrl=https://events.example.org
```

The app allows plain HTTP only to `10.0.2.2` and `localhost`, for local work.

### Settings

All settings are environment variables, so no secret has to live in a file. `.env.example` lists them with empty values.

| Variable | Default | Meaning |
|---|---|---|
| `EVENTKEN_DB_URL` | `jdbc:postgresql://localhost:5432/eventken` | PostgreSQL. |
| `EVENTKEN_DB_USER`, `EVENTKEN_DB_PASSWORD` | `eventken`, `eventken` | Match `compose.yaml`, for local work only. |
| `EVENTKEN_ADMIN_TOKEN` | empty (admin endpoints off) | At least 24 characters, else the server refuses to start. |
| `ALLOW_PRIVATE_TARGETS` | `false` | `true` lets feeds use private, loopback, link-local, reserved addresses. |
| `EVENTKEN_INGEST_INTERVAL_MINUTES` | `60` | `0` turns the schedule off. |
| `EVENTKEN_FETCH_CONCURRENCY` | `4` | Feeds fetched at the same time (1 to 32). |
| `EVENTKEN_DEFAULT_TIME_ZONE` | `UTC` | Zone for feed times that carry none. |
| `EVENTKEN_HOST`, `EVENTKEN_PORT` | `0.0.0.0`, `8080` | Where the server listens. |
| `EVENTKEN_API_URL` | `http://localhost:8080` | The server the desktop app uses. |

## API

| Method and path | Who | What |
|---|---|---|
| `GET /api/events` | anyone | Search. Parameters: `q`, `lat`, `lon`, `radiusKm`, `from`, `to`, `category` (repeat it, or use commas), `sort` (`relevance`, `start`, `distance`), `limit`, `offset`, `includeCancelled`. |
| `GET /api/events/{id}` | anyone | 1 event with its description, organiser, every feed that lists it. |
| `GET /api/categories` | anyone | Categories of events that have not ended, with counts. |
| `GET /api/admin/feeds` | admin | Feeds with their last fetch status. |
| `POST /api/admin/feeds` | admin | Add a feed: `{"url": "...", "kind": "ICAL" or "JSONLD", "defaultCategory": "..."}`. |
| `DELETE /api/admin/feeds/{id}` | admin | Remove a feed with its listings, then the events no other feed lists. |
| `POST /api/admin/feeds/{id}/ingest` | admin | Fetch 1 feed now. |
| `POST /api/admin/ingest` | admin | Fetch every feed now. |
| `GET /health` | anyone | `{"status":"ok","database":"ok"}`, or 503. |
| `GET /openapi.yaml`, `GET /docs` | anyone | The OpenAPI file and Swagger UI. |

A bad query gets 400 with every problem in 1 message, for example `{"error":"invalid_query","message":"radiusKm needs lat and lon."}`.

## Safety and privacy

- Only admins add feed URLs, and the server fetches nothing else.
- The address guard (`server/.../net/TargetGuard.kt`) refuses private, loopback, link-local, shared, documentation, benchmarking, multicast, reserved ranges for IPv4 and IPv6, plus IPv6 forms that carry an IPv4 address (`::ffff:127.0.0.1`, NAT64, 6to4). It refuses a host when any of its addresses is blocked. OkHttp then connects only to the addresses the guard approved, so DNS cannot change the answer between check and connection. Redirects are followed by hand, at most 5, each through the guard and the robots.txt of its own site.
- Feed fetches skip any HTTP proxy, because a proxy would resolve names itself, out of the guard's sight. ROADMAP.md item 9 plans proxy support that keeps the guard.
- Limits: 5 MiB per feed, 5,000 events per feed, 60 seconds per fetch. A feed over a limit fails, and the feed status records the error.
- The token check runs in constant time (`MessageDigest.isEqual`).
- No attendee data is stored. The iCalendar parser drops `ATTENDEE` lines and keeps only the organiser's display name, never the e-mail address. A test checks the database for addresses after a seed.
- All sample data in `fixtures/` is made up: organisations, venues, events. Addresses use the reserved `example.org` and `.example` domains. The sample events sit around Victoria, BC, so the distance search has real coordinates to work with.

## Tests

```sh
./gradlew :server:test                     # unit tests, then PostgreSQL through Testcontainers
EVENTKEN_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/eventken ./gradlew :server:test   # a running PostgreSQL instead
./gradlew :shared:desktopTest :desktopApp:test
./gradlew :androidApp:testDebugUnitTest :shared:testAndroidHostTest   # Robolectric, needs the Android SDK
./gradlew :androidApp:lintDebug
./gradlew ktlintCheck
./gradlew build                            # all of the above that this machine can run
```

| Module | Tests | What they cover |
|---|---|---|
| server | 124 | iCalendar and JSON-LD parsing of the sample feeds, recurring rules (a moved and a cancelled week, a daylight-saving change, monthly and yearly rules, the 12-month window), matching, merging, the address guard, robots.txt, the fetcher against a local HTTP server (redirects, 304, size limit, robots.txt), ingest and search against PostgreSQL, the HTTP API through the shared client. |
| shared on the JVM | 37 | Query rules, reminder rules across time zones, the API client against a mock engine, the SQLite cache, the screen state models, date and distance text. |
| desktopApp | 4 | The real Compose screens, rendered off-screen against a fake server: search, open, save, the saved list, the organiser screen with the token. |
| shared on Android | 28 | The common tests again, on the Android target, so the shared code is checked on both platforms. |
| androidApp | 11 | Robolectric and Compose UI tests on the API 37 runtime: search, "Near me" with a fake location provider, save, reminders through WorkManager's test driver, the calendar intent, the notification worker, the location provider. Android lint reports 0 issues. |

## Project layout

```
server/       Ktor server: api, ingest (parsers, matcher, merger, store), net (guard, robots.txt, fetcher), db
shared/       Kotlin Multiplatform: model, search, reminder, api, cache (SQLDelight), app (screen state), format, geo
desktopApp/   Compose Multiplatform desktop app
androidApp/   Android app (Compose, WorkManager)
fixtures/     Made-up sample feeds: 2 .ics files, 2 HTML pages with JSON-LD, 1 page robots.txt blocks
```

## Known limits

- A recurring rule with any of the parts `BYSETPOS`, `BYWEEKNO`, `BYYEARDAY`, `BYHOUR`, `BYMINUTE`, `BYSECOND`, or with a `FREQ` finer than `DAILY`, is not expanded: the event shows its first date only. `RDATE` periods (start/end) are not read.
- `TZID` values must be IANA names, such as `America/Vancouver`. `VTIMEZONE` blocks are read past, not interpreted.
- Ingest writes take 1 PostgreSQL advisory lock, so 2 server instances never store at the same time. Fetching still runs in parallel.

## Licence

MIT, see [LICENSE](LICENSE).
