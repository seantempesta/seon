---
type: landing
lane: fix-schema-projection-rules
status: landed-installed
created: 2026-09-24
---

# Lane fix-schema-projection-rules — landing (2026-09-24)

`seon.schema` owns the projection build and the one-replacement admission
(`projection-with-schema`). Following a70688cc8, authoring rules judge
admission only; a build compiles what it holds so as-of views stay readable.

## Broken or unresolved first

- `seon.schema-test` has 9 reds on this HEAD that this lane did not cause
  (present before the first edit, run a9c7439efd32, and unchanged after,
  run 0678ab03192c): a-component-bearing-row…, a-projection-with-no-bound-
  predicates…, a-refused-projection-source…, an-incremental-projection-equals-
  its-full-build (also 16–19 s against a 5 s bound), error-declared-schemas…
  (observation identity), every-predicate-schema-declares-what-it-accepts,
  one-derivation-owns-the-projection-predicate-bindings, pulled-forms-derive…,
  render-declarations-require-a-contract…
- `seon.config-test` has 9 reds (run 000b6410d752) in refusal data shape,
  apply/reconcile and cluster-branch writes. None names the display-label rule.
  I have no pre-edit baseline for this namespace.
- Publication: every hook edit ran `seon.cluster/full-source-refresh!`, taking
  14–20 s (`seon.fn/analyzed-artifacts` 44–46 s inclusive across six calls).
  Publication twice refused "The source head changed before publication"
  while other lanes were editing, so one rename reached default only on a
  later edit. This is foreign whole-program work and needs a root issue.
- Candidate deletion, not verified: once `projection-delta` is gone, nothing
  in src produces a divergence delta. The `build-projection` branch that
  treats `forms` as a projection and `function-contracts` as a delta
  (`compose-projection-data`) may have no live caller. Probe that before
  deleting it.
- No RESET NEEDED.

## Items

1. **Deleted `seon.schema/projection-delta`** (commit 53a038419). The only
   caller left was one schema_test round trip; `rg` over src, test, script
   and bin found no others. The test now materializes the changed
   projection's own pure data. It also declares
   `:seon.program/partition :seon.data`, which f4c56ad12 requires; without it
   the test was already red.
   Proof: `pure-projection-data-materializes-compiled-entity-indexes` is green.
2. **Moved `assert-config-display!` to admission** (commit e9f6dc805). It is
   off `declaration-projection`, `build-projection` and the incremental
   derivation. It now runs in `projection-with-schema` for a changed
   definition, and in `schema.edn/admit`'s no-projection branch.
   Live checks on default:
   - Admission refuses `:seon.config.agent/show-all-settings` without a
     label (0.57 ms).
   - A whole build of that same population is admitted (278 ms).
   - Bootstrap `admit` refuses the unlabelled dial and admits the labelled
     one (86 ms).
   `every-agent-dial-has-a-declared-settings-row` is green (run e170f7f86b46,
   74 pass). By reflecting over its compiled classes I confirmed the loaded
   body is the new one.
3. **Refuted: ab7cc7190 had already fixed the `:inst` false refusal.** Live
   `admit` with no projection admits `:inst`, `:int` and `:seon.error/at`
   (237 ms). Its regression was red only because the runner hands each test
   a projection, so the bootstrap path never ran. The body now binds
   `schema/*projection*` nil (commit 1cc45977a). It is green in run
   0678ab03192c.
4. **Refuted: a70688cc8 had already fixed the `[:maybe X]` item.** Live
   `projection-with-schema` refuses `[:maybe :int]` and `[:or :nil :int]` as
   `:seon.schema/nilable-value-schema` and admits `:int` (7 ms). Its
   regression is `a-nilable-value-schema-is-refused-at-admission-never-at-build`.

## Size

Net source lines: −38 (item 1), +10 (item 2). Test: −2 (item 1), +5 (item 2),
+3 (item 3).

## Verification limits

I ran only focused `bin/test-check default --ns` and `--test` requests, with
no platform tier. The default cluster's adoption baseline is red (foreign), so
the recorded evidence reuses nothing.
