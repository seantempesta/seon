---
type: landing
status: landed-unverified
lane: review-f3-builder-key-authority
created: 2026-09-24
review: docs/research/agent-platform/review-landings-2026-09-24.md (F3)
---

# Lane review-f3-builder-key-authority (2026-09-24)

`seon.db/carried-projection` keyed projections by the builder digest of the
commit named by the *read value's* first `:seon.source/commit-id` datom
(d8951c477). That is the branch's historical program, while the loaded code
builds the projection. The key now comes from the process owner:
`seon.sci.eval/loaded-identity` (development adoption's commit, set after its
record commits), else the running instances' boot commit. The one
`projection-cache` and its `[::builder commit]` memo are kept; no stamp.

## Broken or unverified first

- **Nothing ran under the load rule.** Root runs, serially:
  - `bin/test-check default --test seon.db-test/a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program`
  - `bin/test-check default --ns seon.db-test` (key-sharing tests and the d8951c477 regression).
- **Not a refusal in the adoption window.** F3 asked to refuse mixed-program
  construction. The adopter itself reads projections inside
  `call-with-program-change` (its record transaction's db-after, `config/effective`),
  so a refusal would make every builder-changing adoption fail. Instead, under an
  unknown identity: if every adopting cluster's live record names a program with
  the target's builder digest, the target digest is used (code unchanged, exact);
  otherwise the key is a per-adoption `::mixed-program` digest, never reused after
  the window, and `nearest-base` refuses to base on it. Cost: builder-changing
  adoptions rebuild read contents from `{}` (~400 ms each) inside the window.
  This window path has no regression: it needs a real adoption.
- **Refusal by name:** running instances with different boot commits and no
  adoption identity throw "the loaded projection builder is ambiguous".
- **Transition on the current default JVM:** `loaded-identity` is `{}` although
  the record moved (22:28Z adoption ran pre-7f53d0809 code). The key names boot
  commit `6ab59be6`; its builder digest equals the record `6ab5a419`'s
  (`14fefa8d…0d32`), so no stale projection. The next adoption sets the identity.
- A detached value read first after an adoption, before any store-backed read
  memoizes `[::builder C]`, refuses "loaded source commit is unavailable"; the
  adopter's own post-record read memoizes it first.
- The regression proves the public wiring ignores a value's record. It cannot
  change the JVM identity (global state), so a true adoption across a builder
  change is proven only by the window reasoning above.

## Evidence (default, JVM `eval_clj`, read-only)

- New authority read inline (identity + instances + memo lookup): 0.0007 ms per
  call (3,000 calls). Old read (schema lookup + aevt datom) was part of the
  0.028–0.043 ms hit; root repeats
  `(let [db (datahike.api/db (seon.cluster.boot/connection "default"))] (time (dotimes [_ 3000] (seon.db/carried-projection db))))`
  after adoption (before: 0.0435 ms per hit under current load).
- Equal-content rebuild from its own base: 71 ms; `{}` rebuild ~400 ms (d8951c477).
- Live record read per instance: 0.017 ms (used only in the adoption window).

## Change

- `src/seon/db.clj` (+53 / −24): `loaded-builder` takes the loaded identity and
  instances; `carried-projection` reads the identity instead of the value's datom.
- `test/seon/db_test.clj` (+25): a retained branch whose record names a program
  with another builder digest gets the identical projection the loaded builder made.
- Net +54 lines. RESET NEEDED: no.
