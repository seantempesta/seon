# Lane fix-src-singles-prompt-web-mcp-config — landing (2026-09-25)

Census: docs/research/agent-platform/red-baseline-census-2026-09-25b.md (aab55ddd3),
FIX-SRC items 7–10 and §E prompt_test bounds. Probes on default pid 67823, JVM mode.

## Not fixed (broken things first)

- **Item 7, MCP value window (NOT FIXED; wrong owner file).** Reproduced: 25,130 bytes, 631 ms
  against 8,192. The cut lives in `seon.cluster/mcp-project` (src/seon/cluster.clj), which builds
  the MCP profile from the agent profile's 15,000-token budget, so a string keeps a 24,000-char
  prefix. script/seon/dev/mcp.clj does no windowing. Needs the cluster.clj owner plus a new dial
  (schema resource + config/default.edn). Filed:
  docs/seon/issues/mcp-value-window-inherits-the-agent-token-budget.md. mcp_test.clj:172
  (elision paths) is unprobed.
- **prompt_test bound overruns: phase NOT named.** Stored test evidence keeps no per-phase
  timings (`:seon.test/timings` acquire/resolve/run/release lives only in the run result; the
  database has only `:seon.test/long-ms`). prompt_test did not run in this JVM's life, so no
  profile cells exist for seon.cluster.prompt/*. The load rule forbids running it here. What the
  JVM's counters do show: `seon.test-support/with-branched-database` averages 2,767 ms over 268
  calls (max 43.6 s), including bodies, and `seon.cluster.agent/acquire-context!` averages 313 ms.
- Item 9's second half, `seon.schema/canonical-definition` returning non-EDN
  (config_application_test), belongs to schema.clj (fix-src-singles-db-schema). Not touched.

## Fixed

1. **prompt.clj capture-mismatch (e6a86ea8d).** Probe: `explain-candidate-value` on the produced
   value against `:seon.schema/validation-refusal` fails at `[:seon.schema/expected-value]`: the
   captured prompt string sits where a `:seon.schema/key` belongs. It now returns
   `:seon.cluster.prompt/error`, with `:seon.context.capture/prompt` as the
   `derivation-observation` evidence. Probe after the change: explain is nil (valid). The caller
   lost its assoc (−5 lines). The test asserts the named evidence.
2. **web.clj:719 nil registry (18192143c; test only).** The producer of the nil was the test's
   hand-built `{:seon.schema.projection/forms …}`. It has no registry, and
   `attribute-description` has read the compiled registry since 0f5f849bd. Production is
   correct. The probe on the carried projection plus `projection-with-schema` answers
   ["What this attribute means." same nil]. The test now builds its projection through the
   owner. A by-name refusal needs `:seon.schema/projection` to require the registry. That schema
   admits a registry-less map (probe: true). Filed:
   docs/seon/issues/projection-schema-admits-a-projection-without-a-registry.md.
3. **config manifest (428125c44).** The census's claim is stale: the agent/*, ai.backup/* and
   ai.retry/* dials are present. The probe's dial-set difference was exactly
   `#{:seon.config.maintenance/collect?}` (declared in ba173845c, never shipped). It is now
   shipped as `:seon.config/absent`, which matches the owner ruling "absent = off". After the
   change the difference is empty both ways, and `default-decisions` compiles in 288 ms.
   `no-provider true` and `escalate-to :seon.config/absent` are unchanged.

Net lines: prompt.clj +8/−10; prompt_test +5/−1; web_test +22/−13; default.edn +4.
Verification limits: lint is clean (0 errors). No namespace was reloaded into default, and no
test request was run (load rule). RESET NEEDED: no.

## Root runs

- bin/test-check default --test seon.cluster.prompt-test/a-replay-under-a-selecting-budget-reconstructs-its-own-capture
- bin/test-check default --test seon.render.web-test/an-attribute-description-comes-from-the-projection-form
- bin/test-check default --test seon.config-test/the-default-document-has-one-canonical-complete-location
- bin/test-check default --ns seon.cluster.prompt-test   (read :seon.test/timings per member to name the 6.5–7.9 s phase)
- after the cluster.clj fix: bin/test-check default --ns seon.cluster.mcp-test
