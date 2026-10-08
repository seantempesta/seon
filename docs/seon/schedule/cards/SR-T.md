---
title: "SR-T: shell output and web bodies use the one blob threshold"
type: reference
status: retired
created: 2026-10-08
---

# SR-T: Shell output and web bodies use the one blob threshold

**Retired.** Superseded by [SP-3](SP-3.md) (shell and web), [SP-4](SP-4.md) (reasoning) and [SP-1a](SP-1a.md)/[SP-1c](SP-1c.md)
(error evidence), lane cards-storage-point (`08ab3b0de`). Do not implement this card.

Ledger row SR-T (group E). Source: `storage-audit/storage-audit-opus.md` finding 14, slice T; owner: "inline/blob
4,096 bytes". Model: Sonnet.

**Scope, corrected while carding.** Of the five dials the audit named, only two decide *storage* (inline text versus a
blob): `:seon.config.shell/inline-output-bytes` (4096) and `:seon.config.web/max-inline-bytes` (8192).
`:seon.config.fs/max-inline-bytes` is the agent-facing read window of `my.fs/read` (`fs/jvm.clj:518`) and the edit
transform bound (`edit/jvm.clj:137`), not storage: it stays a dial. `:seon.config.error/max-evidence-bytes` bounds the
fault family's inline `:seon.error/data-edn`; E-SDELETE deletes that whole path (fault evidence becomes shown text by
SR-D's rule), which deletes more than converting it, so it is not here.

The storage-roles rule (ledger §3.17) has one threshold: `:seon.config.eval.result/blob-threshold`, compared in UTF-8
bytes.

## Files

- `src/seon/shell/jvm.clj`, `src/seon/web/jvm.clj`
- `resources/seon/schemas/seon.config.shell.edn`, `resources/seon/schemas/seon.config.web.edn`, `config/default.edn`
  (delete the two decision lines, `:226` and `:378`)
- `test/seon/blob_threshold_test.clj`

## Functions changed

- `seon.shell.jvm/output-descriptor` (private, `shell/jvm.clj:303`): `inline-limit` reads
  `(:seon.config.eval.result/blob-threshold effective)` instead of `:seon.config.shell/inline-output-bytes`. The
  preview dial `:seon.config.shell/preview-bytes` stays (an agent-facing preview, not storage).
- `seon.web.jvm/fetch` (`web/jvm.clj:284`) and the search caller (`:417`): `max-inline` reads
  `(:seon.config.eval.result/blob-threshold effective)`. `capture-body!` (`:193`) is unchanged (it takes the number).
- Delete `:inline-output-bytes` from `seon.config.shell.edn` (`:7`) and `:max-inline-bytes` from
  `seon.config.web.edn` (`:1`) with their decision lines; `seon.config`'s `apply-compiled!` retires a removed dial
  (seon-context-config skill, "Deleting a dial").
- Before editing, `rg -n "inline-output-bytes|web/max-inline-bytes" src test resources config docs/seon .agents` and
  convert every hit in the same commit (tests that set the dial set `blob-threshold` instead).

Check first, at the REPL on a throwaway cluster, that `(:seon.config.eval.result/blob-threshold (seon.config/effective
db cluster))` answers 4096 (it is a dial; `seon.blob/inline-or-stage` reads the same attribute, `blob.clj:217-237`).

## Proof

New `seon.blob-threshold-test/shell-and-web-use-the-one-threshold` (data only): with the cluster's blob threshold at
4096, a 5,000-byte shell output stores `:my.shell.output/blob` and its digest and size, no `:my.shell.output/text`;
a 4,000-byte one stores text; the same pair for a web body served by the test's local server (reuse the fixture the
existing web tests use). The existing `seon.blob-threshold-test/text-at-threshold-is-inline-over-is-staged` stays green.

`--test seon.blob-threshold-test --test seon.shell.jvm-test --test seon.web.jvm-test --test seon.config-defaults-test`

## Cost

D: one dial read instead of another; web bodies between 4 and 8 KB now become blobs (one staged write each).

## Commit

`bin/lane commit <lane> -m "SR-T: shell output and web bodies store inline or as a blob by the one blob threshold" --test seon.blob-threshold-test --test seon.shell.jvm-test --test seon.web.jvm-test --test seon.config-defaults-test src/seon/shell/jvm.clj src/seon/web/jvm.clj resources/seon/schemas/seon.config.shell.edn resources/seon/schemas/seon.config.web.edn config/default.edn test/seon/blob_threshold_test.clj`

## Dependencies

`config/default.edn` is held by dials-16gb (and DIAL-1 rewrites it as an Aero file): claim after dials-16gb releases,
and land before or inside DIAL-1. SR-P first (same storage family).
