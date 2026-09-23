---
type: issue
status: resolved
severity: defect
created: 2026-09-23
tags: [issue, database, read, performance, profile, agent-platform]
---

# A lookup-ref pull sorts the whole installed schema on the happy path

This note owns the profile line `seon.db/with-declarations ×310,198, 405 s`
reported by the plan status audit
(`docs/research/agent-platform/plan-status-audit-2026-09-23.md`, "Live defects").

## Evidence (default pid 90963, read-only MCP `eval_clj` jvm, throwaway ns `tmp.live-defects-probe`)

- Profile samples:

  | time (Z) | `with-declarations` calls | total ms | `db/pull` calls | `db/q` calls | `seon.test/run` |
  |---|---|---|---|---|---|
  | 04:23:40 | 592,184 | 673,965 | 205,031 | 36,060 | 35 |
  | 04:28:47 | 775,474 | 819,718 | 264,180 | 43,996 | 38 |

  The count grows by about 36 k calls a minute while test runs execute.
- `with-declarations` (db.clj:1532) wraps every `seon.db` read: `q` (db.clj:2283),
  `pull`/`entity` (db.clj:2438), `datoms` (db.clj:2675), `index-page` (db.clj:2737),
  replay (db.clj:994) and `read-evidence-changes` (db.clj:1070). Its total
  therefore includes the time of each read it wraps. Measured on its own it
  costs 9.4 µs armed, and `read-declarations` costs 5.4 µs. The count equals
  the number of reads, and the time is the reads' own time.
- Per-call cost against Datahike, trivial read, warm (2,000 or 500 iterations):

  | read | `seon.db` | Datahike |
  |---|---|---|
  | `q` (`[:find ?e . :where [?e :seon.cluster/name "default"]]`) | 88–164 µs | `d/q` 4.2 µs; `q-with-evidence` 4.8 µs |
  | `pull` by lookup ref `[:seon.cluster/name "default"]` | 962 µs | `d/pull` 9.6 µs |
  | `pull` by eid | 37 µs | |

- A stack sample of a thread looping on the lookup-ref `pull` (200 samples,
  2 ms apart) shows these top frames:

  | frame | samples |
  |---|---|
  | `installed-attribute-declarations` (db.clj:1634-1635) | 136 |
  | `registered-attribute-candidates` (db.clj:1658) | 38 |

  The installed schema holds 2,429 keys.
- Timing the pieces: `attribute-observation db :seon.cluster/name` costs 1,054 µs.
  `(get (dbi/-schema schema-db) :seon.cluster/name)` costs 0.16 µs.

## Cause (verified in source)

- `lookup-ref-error` (db.clj:1683-1700) runs before every lookup-ref `pull`/`entity`
  (db.clj:2435-2436).
- It calls `attribute-observation` (db.clj:1660). That function builds a
  `sorted-map` of all installed attributes and a 12-element candidate list,
  only to read one attribute's declaration.
- Both values are needed only in the error branch (`unknown-attribute-error`).
- The cost of each call is proportional to the whole installed schema, and it
  is paid per read. The happy path needs one map lookup.

## Smallest fix at the owner (not implemented)

- In `lookup-ref-error`, read `(get (dbi/-schema (schema-database database)) attribute)`
  for the declaration.
- Call `attribute-observation` only inside the `(nil? declaration)` branch.
- The same shape sits in `attribute-installed?` (db.clj:1640-1650), which builds
  the sorted map to answer one `get`. Replace that body with the direct lookup.
- The seam is Datahike's installed schema map (`dbi/-schema`). It is already the
  authority, so no cache is needed.
- Expected: a lookup-ref `pull` drops from about 1 ms to about 40 µs, like the
  eid path. The `pull` total (213 s of this JVM's 819 s `with-declarations`
  time) falls by about 95 %.
- The remaining 20–40× gap on `q` (88–164 µs vs 4 µs) is not attributed here. It
  needs its own stack sample before any fix is named.

## Is the work per call where per evaluation would suffice?

Yes. The observation is a function of the database value's installed schema,
and the happy path needs none of it. Deletion is the fix, not caching.

## Resolution (lane m4-write-bound, 2026-09-23)

- `lookup-ref-error` reads the one declaration from `dbi/-schema`; the whole-schema
  `attribute-observation` is built only inside its two refusal branches and
  `unknown-attribute-error`. `attribute-installed?` is the same direct `get`.
- Measured on default pid 90963 (`pull-probe`, 300 warm iterations each, same
  form before and after adoption):

  | read | parent | fixed | `d/pull` |
  |---|---|---|---|
  | `seon.db/pull` by lookup ref `[:seon.cluster/name "default"]` | 606–1,328 µs | 67.6 µs | 6.3–7.8 µs |
  | `seon.db/pull` by eid | 52.6–110 µs | 60.1 µs | |
  | `attribute-installed?` | 429–1,008 µs | 6.3 µs | |

- The lookup-ref path now costs the eid path. The 2×-of-`d/pull` target is NOT met:
  the remaining ~55 µs is shared by every `seon.db/pull` (armed `pull`/`pull-call`/
  `with-declarations`, result decoding and validation), not attributed here; the
  same holds for `q` (~130 µs vs 4 µs). Neither is profiled below the armed cells.
- Refusal branches verified at the REPL: non-unique, wrong value type and
  uninstalled attribute each still carry `::installed-declaration` and 8–12
  registered candidates.
