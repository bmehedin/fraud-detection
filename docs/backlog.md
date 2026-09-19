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
- Avro + Schema Registry instead of JSON (optional Batch 2).
