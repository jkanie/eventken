# Roadmap

Planned work after the seed, with an hour estimate per item. Item 1 stands alone and fits in 1 sitting. The total is 34 hours.

## 1. Search as you type, 2 hours

- What: a `GET /api/suggest?q=` endpoint with prefix matching (`to_tsquery` with `:*`) over titles, venues, categories, plus a debounced suggestion list in both apps.
- Why: whole-word search misses "jaz" for "jazz". Suggestions while typing save keystrokes, above all on a phone keyboard.

## 2. Map view in both apps, 4 hours

- What: a map tab with a pin per result, built on OpenStreetMap tiles, with the list and the map sharing 1 search state. Tapping a pin opens the event.
- Why: distance as a number says little about where an event is. A map shows clusters, such as several events in 1 park.

## 3. Spatial index for large data sets, 3 hours

- What: a migration that adds PostGIS (or earthdistance where PostGIS is not available) with a GiST index, a benchmark with 1 million generated events, then a switch to the faster query when the benchmark shows a gain.
- Why: the bounding box on a B-tree index works for a city's events. A country's worth of events needs a spatial index to keep radius searches fast.

## 4. Feed health and back-off, 2.5 hours

- What: count failures per feed, wait longer between attempts after each failure, pause a feed after 10 failures in a row, show this on the organiser screen with a button to retry.
- Why: today a dead feed is fetched every hour forever. Back-off saves both servers the traffic, and the organiser sees what needs fixing.

## 5. More event formats in pages, 3 hours

- What: read schema.org Events written as Microdata and RDFa, plus `h-event` microformats, next to JSON-LD.
- Why: many older venue sites mark up events without JSON-LD. Each extra format widens what an admin can add without asking the venue to change its site.

## 6. Caching and rate limits for the public API, 2 hours

- What: an `ETag` and `Cache-Control` on search and event responses, plus a per-address request limit with Ktor's rate limit plugin, both covered by API tests.
- Why: the read endpoints are open to anyone. Caching cuts repeat work, and a limit keeps 1 client from slowing search for everyone.

## 7. Organiser accounts with scoped tokens, 4 hours

- What: tokens per organiser, stored as hashes, each limited to the feeds that organiser added, with an audit log of every admin change.
- Why: 1 shared admin token does not scale past 1 person. Scoped tokens let a venue manage its own feeds without power over the rest.

## 8. Saved-event updates on the device, 4 hours

- What: a periodic WorkManager job (and a desktop timer) that re-loads saved events, updates the cache, moves reminders when a time changes, notifies when a saved event is cancelled.
- Why: saved copies go stale. A person who saved a concert needs to hear that it moved or was cancelled.

## 9. Feed fetches through an HTTP proxy, 2 hours

- What: proxy support that resolves and checks the target address on the server, then asks the proxy to connect to that checked address (with the host name kept for TLS).
- Why: many company networks allow outbound traffic only through a proxy. Today feed fetches skip proxies, because a plain proxy would resolve names out of the address guard's sight.

## 10. Accessibility pass for both apps, 3 hours

- What: content descriptions for every control, larger touch targets, tests at 200 % font scale, colour contrast checks in light and dark themes, TalkBack and screen-reader walkthroughs written up as test cases.
- Why: event listings matter to everyone in a community. Screen-reader users and people who need large text should get the same app.

## 11. Release builds and installers in CI, 2.5 hours

- What: desktop installers (`.deb`, `.msi`, `.dmg`) from Compose's packaging tasks on 3 runner types, plus a signed Android release build with R8, attached to GitHub releases on a tag.
- Why: today only debug builds come out of CI. A release on a tag lets people try the apps without building them.

## 12. Time zone names from Windows calendars, 2 hours

- What: map Windows zone names (such as "Pacific Standard Time") to IANA names with the Unicode CLDR table, and read the offsets of a `VTIMEZONE` block when the name is unknown.
- Why: calendars exported from Outlook use Windows names, which the parser cannot place today, so those events fall back to the default zone.
