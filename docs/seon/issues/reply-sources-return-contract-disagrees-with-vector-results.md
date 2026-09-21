---
type: issue
status: open
severity: friction
tags: [issue, contract, reply, testing]
---

# Reply sources return a vector member under a map contract

The bridge's armed 55-namespace snapshot at
`acb39cb6372919d94f409ea61c1c4e07526fc35f` printed two errors in
`seon.ai-stream-fold-test/an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal`.
`seon.cluster.reply/sources` refused its return: a collection member was a
vector where the declared contract requires a map. Evidence:
`tmp/bridge-step2-retirement-fast.log:259` and `:295`.

The run stopped at the separately ruled turn-identity/error-facet decision,
so no completed tally exists. This observation has not been attributed to
the bridge conversion or reproduced against a baseline. The current fast
snapshot cannot enter tests until the published base no longer contains the
old bootstrap error identity members.

Owner: reply parsing and its declared output contract. Acceptance: the same
existing malformed/empty-stop regression completes under armed contracts,
with parser results and declared member shape agreeing. Do not suppress the
return check or infer the intended shape from this error alone.

## Authored-shape scratch observation — 2026-09-23

Re-observed during the owned scratch cluster's ordinary boot and Juniper
installer. `seon.cluster.reply/sources` refused a vector member under its map
contract from `seon.turn` at `turn.clj:3168`, signature
`73e324b026fd14e51cc9adc1d1bc4a6115ad2356c549face29b3ddb24b068df0`.
Juniper installation then reported `agent-already-running`; its agent row
exists but example schema installation did not complete. No attribution to
the authored-shape change is made. Publication and reader evidence are in
[the landing note](../../prds/steward-platform/research/schema-shape-authored-2026-09-23.md).
The scratch root was shut down and removed; this paragraph preserves the
bounded diagnostic instead of retaining its oversized disposable log.


## Fresh-default reproduction and bounded repair — 2026-09-21

After the authorized fresh reset, `data/clusters/default/logs/seon.log:30`
recorded signature
`c839475f6a436b9209438f24b5f424441269a3eb4479cc3cdbdcf35c51c78f06`
from `seon.turn:3168`. A read-only MCP JVM probe against root
`/Users/sean/src/seon`, cluster `default`, reproduced the refusal on empty
and prose-only input. The complete bounded follow-up envelope returned one
`ret`, 30 ms, cluster alive, `windowed? false`:

```clojure
(mapv
 (fn [text]
   (try
     (let [v (seon.cluster.reply/sources text 'user 1000)]
       {:text text :map? (map? v)
        :sources (when (vector? v) (mapv :seon.cluster.eval/source v))})
     (catch Throwable t
       (let [v (get-in (ex-data t)
                       [:seon.error/data :seon.error/diagnostic-offending])]
         {:text text :returned-map? (map? v) :keys (vec (keys v))
          :missing-base (filterv #(not (contains? v %))
                                [:seon.error/at :seon.error/layer
                                 :seon.error/operation])}))))
 ["" "Only prose here." "#foo/bar [1 2]" "[1 2]"])
```

The first three returned `:returned-map? true` and all three
`:missing-base` keys. Their actual refused values were maps containing the
reply marker, legacy kind, message and data. The last input returned
`{:map? false :sources ["[1 2]"]}`. Thus the vector-member wording describes
an unsuccessful union arm's explanation, not the parser's actual refusal
shape. The root defect is the reply adapter's incomplete error construction.
The underlying `seon.sci.reader/error-value` already constructs the base
observation; its declared `source-bound` and `refused-token` members also
replace the retired kind checks still used by this adapter.

The prepared repair uses the existing diagnostic constructor, retains authored
text and declares the three precise reply errors with their base schema.
Oversize and refused-tag classification consumes the reader's declared members;
`#=` is accepted as a refused-token string, never executed. The regression
checks empty/prose, source bounds, unknown tags, read-eval tags and valid vector
source under the locally armed public contract, including all arities. Existing
reader-error assertions use the current `unreadable-member` observation.

Status remains open pending the root's gate and adoption proof. No test JVM,
operator action, adoption or runtime code mutation ran in this repair lane.
Required namespaces: `seon.cluster.reply-test`, `seon.sci.reader-test`, and
`seon.ai-stream-fold-test` (the original empty-stop regression). Root should
rerun the same bounded form after adoption and observe ordinary turn settlement.


RESET NEEDED for the reply marker representation: `:seon.cluster.reply/refused-tag`
widens from the reader's symbol tag schema to its symbol/string refused-token
schema so `#=` remains the exact observed string. Its persisted bridge shape may
change; the root batches this with the other authorized reset changes. An
alternative retaining the symbol-only attribute would need a distinct string
error alternative using `:seon.sci.reader/refused-token`, plus corresponding
producer/output/render consumer changes; do not reuse the symbol-only reply
error while omitting its required marker. The prepared slice chooses the coherent
reset rather than expanding that compatibility boundary.


## Schema admission correction — 2026-09-21

Reset after `846bfd0f4` refused `:seon.cluster.reply/no-forms-error` because
its only non-base required member was boolean. Evidence:
`data/operator/operations/jvm-c73f5e94-e96f-444e-b359-c92b01237187.log`;
`seon.schema.internal/assert-error-declaration!` requires actual domain evidence.
The helper now carries the exact authored `:seon.cluster.reply/text` at the
error boundary, and no-forms requires it (including the legitimate empty string).
The armed regression checks those exact bytes and verifies that removing them
invalidates the no-forms error even when its boolean marker remains. The complete
projection build exercises structural error declaration admission before boot;
namespace loading or Malli value validation alone is insufficient. No validation
or boot was run by this bounded repair; root owns the pending proof.
