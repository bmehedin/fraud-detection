# 0002 — Location is latitude/longitude, not a country code

Date: 2026-09-15
Status: Accepted

## Context

S2 (impossible travel) needs to compare two locations for a card and decide whether the
implied speed is physically plausible. How location is modelled decides whether that rule is
a computation or a lookup.

Options considered:

1. `country` only — the rule degenerates to "two different countries within N minutes."
2. A fixed city enum plus a precomputed pairwise distance table.
3. `lat`/`lon` plus `country`.

## Decision

`GeoPoint(lat: Double, lon: Double, country: CountryCode)`. Implied speed is haversine
distance divided by elapsed event time, with a threshold of 900 km/h.

## Consequences

- S2 is real arithmetic over real state, which is the interesting version of the rule. The
  threshold is tunable and false positives are explainable ("930 km/h").
- `country` is kept alongside the coordinates even though haversine does not need it: it is
  cheap, it makes alert evidence readable, and `homeCountry` in `CardProfile` uses it.
- The generator carries more weight: each card gets a home coordinate and normal
  transactions are sampled near it, so that fraud injection can produce a genuine outlier.
  This is a Batch 1 cost, paid once.
- Latitude and longitude are validated at ingest (§ "Validation" in `event-schema.md`);
  out-of-range values are malformed, not clamped.

## Alternatives rejected

- **Country only.** Trivial to generate, but the rule becomes a set lookup and the
  event-time-ordering lesson that motivates S2 survives only in weakened form.
- **City enum + distance table.** Reproducible and readable, but the table is invented data
  to maintain, and it teaches nothing that haversine does not.
