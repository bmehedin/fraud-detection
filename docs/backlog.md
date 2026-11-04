# Backlog

Ideas that are out of scope for the current batch. Nothing here is a commitment.

## Deferred from Batch 0

- **Alert lifecycle** (acknowledge / resolve / confirmed-vs-false-positive). Rejected for now
  because nothing consumes an alert; it would model a support process we are not building.
- **Multi-currency + FX table.** All amounts are EUR minor units. Revisit only if a rule needs
  it — none does.
- **Blocklist feedback loop.** Whether slow-path alerts should write into F1's denylist. Real
  systems do this; it introduces a cycle worth its own ADR.
- **Refunds / negative amounts, chargebacks, recurring mandates.** Business complexity, no
  new stack concept.

*(The `Origin` ADT question is settled — see [ADR 0003](decisions/0003-origin-sum-type.md).)*

## Rules dropped on 2026-10-31

- **F2 amount cap**, **S1 amount deviation**, **S3 unusual category spend.** Cut to keep the
  project light. S1 and S3 also need card profiles, and S3 needs cards with favourite
  categories, which the generator does not model.
- **Batch job computing card profiles** (Batch 3). Moved 2026-11-04: no rule in scope uses
  card profiles once S1 and S3 are gone.

## Rule ideas (rules 7, 8, 9 — not in scope)

All three came out of modelling `Origin`, and all three are signals real systems use. They
are deferred because we agreed on six rules, not because they are bad.

- **IP velocity.** The IP on a card's online transactions jumps around within a short window.
  This is F3 applied to a different field.
- **Online location drift.** The geographic region implied by successive IPs for one card
  changes faster than plausible. Needs a GeoIP database, which ADR 0003 rejected.
- **Channel switching.** Rapid alternation between `Physical` and `Remote` origins with
  distant locations. Genuinely used in practice, and the most interesting of the three
  because it needs both branches of the `Origin` sum type at once.

## Out of scope until Batch 6 is done

Frontend, ML models, multi-region, auth, cloud deployment.

## Later ideas

- **IPv6 addresses.** `IpAddress.parse` accepts IPv4 only. Validating IPv6 means handling `::`
  compression and counting groups on both sides — real work for a format the generator does
  not produce. A real payment system would see IPv6 and send those records to the dead-letter
  topic.
- Compare fast-path F3 (processing time) against slow-path F3 (event time) under load —
  planned as a Batch 5 experiment.
- Avro + Schema Registry instead of JSON (optional Batch 2, skipped). Spark needs the 5-byte
  Confluent header stripped, or ABRiS, to read it.
- Batch 2 experiments, skipped: kill a consumer and watch the rebalance; produce with null
  keys and observe ordering; crash a consumer before committing and count duplicates.
