-- Feeds an admin added. Only these URLs are ever fetched.
CREATE TABLE feed (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    url              text        NOT NULL UNIQUE,
    kind             text        NOT NULL CHECK (kind IN ('ICAL', 'JSONLD')),
    default_category text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_fetched_at  timestamptz,
    last_status      text,
    -- Kept for conditional requests, so an unchanged feed costs the publisher 1 short reply.
    etag             text,
    last_modified    text
);

-- array_to_string is only STABLE, and a generated column needs an IMMUTABLE
-- expression. For text[] the result never changes, so this wrapper is safe.
CREATE FUNCTION eventken_join(text[]) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN array_to_string($1, ' ');

-- 1 row per real-world event. When 2 feeds list the same event, both point at 1 row here.
CREATE TABLE event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       text        NOT NULL,
    description text,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz,
    all_day     boolean     NOT NULL DEFAULT false,
    time_zone   text,
    venue       text,
    address     text,
    latitude    double precision CHECK (latitude BETWEEN -90 AND 90),
    longitude   double precision CHECK (longitude BETWEEN -180 AND 180),
    categories  text[]      NOT NULL DEFAULT '{}',
    url         text,
    organiser   text,
    cancelled   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Title counts most, then categories, then place and organiser, then the description.
    search      tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', title), 'A') ||
        setweight(to_tsvector('english', eventken_join(categories)), 'B') ||
        setweight(to_tsvector('english', coalesce(venue, '') || ' ' || coalesce(address, '') || ' ' || coalesce(organiser, '')), 'C') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'D')
    ) STORED,
    CHECK ((latitude IS NULL) = (longitude IS NULL))
);

CREATE INDEX event_search_idx ON event USING gin (search);
CREATE INDEX event_starts_at_idx ON event (starts_at);
CREATE INDEX event_categories_idx ON event USING gin (categories);
-- The distance search first narrows rows to a box around the point with this index.
CREATE INDEX event_lat_lon_idx ON event (latitude, longitude) WHERE latitude IS NOT NULL;

-- What each feed said about an event. The event row is rebuilt from these rows.
-- No attendee data is ever stored: the parsers drop it before it gets here.
CREATE TABLE event_source (
    feed_id       bigint      NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
    external_id   text        NOT NULL,
    event_id      bigint      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    data          jsonb       NOT NULL,
    content_hash  text        NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (feed_id, external_id)
);

CREATE INDEX event_source_event_idx ON event_source (event_id);
