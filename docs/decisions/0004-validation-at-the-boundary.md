# ADR 0004: Validation lives at the boundary, not in domain constructors

Date: 2026-09-15. Status: **superseded by [ADR 0005](0005-smart-constructors.md)** the same day.

## Context

`CardId("")` compiles and runs. So does a transaction with a negative amount. The domain types
as first written accept values the business rules forbid.

Two places could stop that: smart constructors on the types themselves, or one check at the
edge where untrusted bytes become a `Transaction`.

## Decision

Validate at the boundary. Domain types stay plain case classes.

## Why

The validation list in `event-schema.md` already belongs to the boundary: malformed records go
to `transactions.dlq`. A smart constructor returning `Either` does not remove that work.
Meanwhile every construction site pays for it, including a generator that builds values it
knows are valid.

## Why this was reversed

Batch 1 adopted test-driven development, and the first thing to test is behaviour. Plain case
classes have no behaviour — nothing to write a test against. Smart constructors give each type
one small, testable decision, which is where a TDD cycle can actually start.

The counter-argument above still stands and was accepted as a cost: the generator now handles
`Either` at every construction site. See ADR 0005.
