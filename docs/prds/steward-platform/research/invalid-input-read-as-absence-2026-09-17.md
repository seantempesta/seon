---
type: research
date: 2026-09-17
---

# An invalid input read as an ordinary row (issue indexing, config reads)

Two defects, one class: **a reader receives an error value or an invalid
input and treats it as an ordinary or absent row.** Both were fixed at the
root, each with one regression asserting the wanted behaviour.

## Fix 1 — one invalid note refused the entire publication

`seon.issue/index-tx` derived per-note results that correctly carried a
refusal and `:seon.issue/tx []` for an invalid note, and then minted an
identity row for **every parsed slug**, invalid ones included:

```clojure
present (set (map :seon.issue/id parsed))
…
tx (into (mapv #(hash-map :seon.issue/id %)
               (remove by-slug (sort present)))
         …)
```

An identity-only `{:seon.issue/id "…"}` row carries no `:seon.issue/title`,
which `:seon.issue/issue` requires, so the whole-entity validator refused the
transaction and **the entire publication failed on one bad note**. Observed:
batch 116's base publication and every development adoption that night —

```
refused transaction data at [46549 :seon.issue/title]: expected the required
key :seon.issue/title … Entity: #:seon.issue{:id
"the-default-clusters-effective-configuration-lost-every-required-fact"}
```

(full report:
`/var/folders/d6/78_m9wb92wg1f3qbt85s1r400000gn/T/clojure-18090810938834736676.edn`).
That note declared `type: defect`; only `issue` is valid.

**Root fix.** Validity is now decided once, before anything derives datoms:
`invalid-reason` is lifted out of the per-note loop, `valid` and `admitted`
are derived from it, and

- minting uses `admitted` (valid slugs only) — an invalid note contributes
  its refusal diagnostic and **no datoms at all**;
- `classes` and `members-by-class` are built from `valid`, so an invalid note
  can never be named by a lookup ref that nothing asserts;
- `removed` still uses `present` (every parsed slug), so a note that is
  present on disk but refused **is not retracted**: a prose defect never
  deletes indexed facts. Stated in the docstring.

Exact bytes: `src/seon/issue.clj` +38 / −16 (66,254 bytes at HEAD of this
lane's edit).

**Regression** — `seon.issue-test/an-invalid-note-refuses-itself-and-mints-no-identity-only-row`
(canonical `seon.test-support/with-database`, write through
`seon.test-support/transacted!`): two synthetic notes, one `type: issue`, one
`type: defect`. Asserts the tx contains no `{:seon.issue/id
"probe-defect-typed-note"}` row, exactly one refusal naming that path with
`:seon.issue/reason :invalid-type`, that the whole tx **admits through the
real writer**, that the valid issue has its title, and that the invalid slug
has no entity at all.

Population measured today: 383 open notes + 1,391 archived notes reach
`index-tx`; `README.md`, `index.md` and `AGENTS.md` are excluded by name at
`seon.issue/notes` (`src/seon/issue.clj:128`). Zero invalid notes remain
after `9d2d2d5da` fixed the one offender — which is exactly why the class
regression, not the note fix, is the durable repair.

## Fix 2 — a refused read reported as missing facts / as no such entity

`seon.db/pull` returns its **database argument unchanged** when that argument
is itself an error value (`pull-call`, `src/seon/db.clj:1841-1843`). Two
config readers bound that answer as data:

1. `seon.config/effective-in` bound the pull as `row` and derived `missing`
   from its keys. During the hour every pull returned
   `:seon.db/invalid-read`, the refusal map's own keys were read as the config
   row, so every dial reported missing and the refusal named **the facts**
   instead of **the read**. Fixed: an answer carrying `:seon.error/kind` is
   returned unchanged — the cause is the read.
2. `seon.config/population-transaction-data` read `(:db/id (db/pull database
   [:db/id] identity))` as "no such entity" and minted a tempid for an
   identity the database already holds; Datahike then refused the conflicting
   upsert. Fixed: a pull error refuses the operation through the namespace's
   existing `refuse!` with the new rule `:seon.config/read-refused`, carrying
   the identity and the read error as `:seon.config/read-error`.

Exact bytes: `src/seon/config.clj` +42 / −30 (24,666 bytes after the edit).

**Regression** — `seon.config-test/a-refused-read-is-returned-as-the-cause-not-reported-as-missing-facts`:
on the canonical database fixture, after a real `config/apply!`, the private
`effective-in` is called through its var with the fixture's projection and a
flat `{:seon.error/kind :seon.db/invalid-read …}` as the database — the
honest construction of a refused read, because `pull-call` returns that value
unchanged. Asserts the result's kind is `:seon.db/invalid-read`, not
`:seon.config/missing-effective`, and that it carries no
`:seon.config/missing-effective` key.

## Verification boundary

`bin/test-fast --paths src/seon/issue.clj src/seon/config.clj
test/seon/issue_test.clj test/seon/config_test.clj -- seon.issue-test
seon.config-test`:

```
Ran 31 tests containing 238 assertions.
1 failures, 0 errors.
```

Both new regressions passed. The one red is **foreign and pre-existing**:
`seon.issue-test/indexed-issues-replace-facts-and-retract-removed-notes`
(`test/seon/issue_test.clj:65`) expects `seon.issue/render-ai` to contain
`(my.issue/status`. `render-ai` delegates to `status-text`
(`src/seon/issue.clj:730-736`) and the string `my.issue/status` does not occur
in `src/seon/issue.clj` at all — it lives in `src/seon/issue/opening.clj:77`.
Neither file is touched by this lane; the failure reproduces independently of
this change.

This is an **iteration** result, not the isolated gate's proof: `bin/test-fast`
shares the worker's contract arming in one JVM but provides no per-worker
isolation, retained run roots, platform tier, or recorded result facts. The
cold proof still owed is the orchestrator's `bin/test --paths src/seon/issue.clj
src/seon/config.clj test/seon/issue_test.clj test/seon/config_test.clj --
seon.issue-test seon.config-test` plus `bin/test --platform`. No live-cluster
observation was made: this lane did not start, stop, reset or adopt on
`default`.

Unrelated tree state seen and preserved: `test/seon/test/runner_test.clj` is
`UU` (an unresolved merge conflict) and does not parse; it is another
session's residue, untouched here.
