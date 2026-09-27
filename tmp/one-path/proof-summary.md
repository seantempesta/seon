| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 224 | {:taken [], :contract-failures [], :contracts 99, :declared 59, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 571 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 291 | {:facts-errors []} |
| `time-h36` | timing | PASS | 6896 | {:ms 6626, :timings {:read 2.03121, :evaluate 217.609871, :derive 81.16453999999999, :apply 4788.957416, :settle 1534.872709}} |
| `b7-lazy-definition` | effects | PASS | 522 | {:shown ["(42)"], :diagnostics []} |
| `corpus-join` | observer | PASS | 774 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 834 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 340 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.313374, :evaluate 27.441583, :derive 9.234083, :apply 130.878332, :settl... |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 4059 | {:ruling-b-ms {:before 33, :after 28}, :head-path-ms {:before 5, :after 5}} |
| `oq1-storage-failure` | owner | PASS | 428 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 997 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 194 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 56 | {:shown ["#object[one-path-proof.host/eval5011535/reify--5011536]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :di... |
| `time-one-defn` | timing | PASS | 3601 | {:ms [1030 368 275 285 271], :timings {:read 0.110001, :evaluate 2.439708, :derive 9.830833, :apply 114.350167, :settle 139.305667}, :batch-retained-mb 49.57} |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 1114 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `supply-from-the-world` | supply | PASS | 336 | {:lacking "Cannot call user/target: :seon.db/connection is unavailable. This evaluation's world holds no :seon.db/connection; pass it explicitly.", :walk (:s... |
| `agent-starts-in-another-namespace` | effects | PASS | 907 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 188 | {:count 52, :designed [6], :ms 19, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 1977 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3 user/nc1]} |
| `b5-contracts-gate` | landing | PASS | 943 | {:ms 330, :findings 0, :each []} |
| `r8-render-read-only` | entrant | PASS | 270 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 7099 | {:ms [344 245 271], :timings {:read 0.093957, :evaluate 1.783375, :derive 8.633125, :apply 124.688125, :settle 132.384667}, :cause "seon.db/nearest-base: the... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 341 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 1632 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 98 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `gate-runs-tests-in-their-namespace` | gate | PASS | 1640 | {:gate #:seon.gate{:sym gjf.core/f, :blocks? false, :tests 1}, :refusal {:seon.gate/sym gjf.core/f, :seon.gate/blocks? true, :seon.gate/tests 1, :seon.gate/n... |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes were not maps and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 474 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `gate-judges-the-change` | gate | PASS | 1592 | {:still-red-gate #:seon.gate{:sym user/gj, :blocks? false, :tests 2, :still-red [#:seon.test{:pass-count 0, :fail-count 1, :error-count 0, :sym user/gj-red}]... |
| `parity-faults-in-both-replies` | parity | PASS | 1220 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-27T23:47:09.403Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 964 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 476 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | PASS | 9692 | {:kept 24, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 5 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 0 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 611 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 415 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 249 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 66197 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["The install gate refused user/h32-f: its own contract check failed"]]], :entities 86, :differ []... |
| `oq1-bad-form-logged` | owner | PASS | 596 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 250 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1608 | {:errors ["Program retraction refused."], :feedback #:seon.feedback{:changed [user/review-new], :missing-contracts [user/review-new]}} |
| `v2-7-no-reverse-requires` | static | PASS | 1512 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 651 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 568 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 7859, :old-gen-after-gc-mb 2727, :processors 18}, :after {:heap-used-mb 8013, :old-gen-after-gc-mb 2150, :processors 18}} |
| `time-acquisition` | timing | PASS | 10774 | {:entities 100, :ms 8, :us-per-entity 80} |
| `blob-get-without-connection` | supply | PASS | 347 | {:values ["the whole value"], :digest "c1a9c9c4cd5488af28d93e7d185c77f4177c84955ba89bc0cb60613f266d0373"} |
| `s3-schema-rehearsal` | landing | PASS | 7890 | {:errors [":seon.batch/sink references predicate seon.batch/sink?, but its existing program-graph call edges do not yet prove a pure, capability-free transit... |
| `q5-def-edges` | owner | PASS | 587 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 105 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-27T23:47:23.743Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 436 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 464 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 0 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `s3-population-admits` | landing | PASS | 1090 | {:head {:refused "Schema population refused :seon.cache/cache (dishonest-generator)."}, :with-batch {:refused "Schema population refused :seon.cache/cache (d... |
| `mcp-equals-agent-turn` | entrant | PASS | 10714 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 282 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 613 | {:facts-errors ["The install gate refused user/gt1: its own contract check failed"]} |
| `v2-5-remove-ns` | effects | PASS | 590 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 703 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 65 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1214 | {} |
| `sci-suite` | sci-fork | PASS | 0 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 572 | {:values ["42" "#'user/ns-name" "43"]} |
| `branch-entrance` | parity | PASS | 285 | {:created {:seon.store/branch :op-branch-3e42f4, :seon.cluster/created? true, :seon.db/basis-t 536885226}, :refusals ["The branch already exists; retire it o... |
| `supply-reads-the-working-value` | supply | PASS | 516 | {:seen [[true false] [true true]]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 66 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 763 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `b4-writer-refusal-reply` | totality | PASS | 405 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 97 | {:diagnostics ["observe: stop failed"]} |
| `feedback-hub-fresh` | timing | PASS | 2827 | {:bounded-text {:fresh-ms 40, :tests 10, :counts {:never-run 10}, :armed ["79 ms in seon.db/with-declarations x339" "32 ms in seon.db/pull x260" "32 ms in se... |
| `v2-8-nested-render-one-tx` | entrant | PASS | 257 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 283 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 585 | {:branches [":agent-0dfaf57a2f83" ":agent-b6436faa2e0a"]} |
| `supply-per-world` | supply | PASS | 594 | {} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1105 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `gate-callee-contract-is-feedback` | gate | PASS | 1305 | {:findings [#:seon.gate{:contract user/cal2, :arguments [-1]}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1553 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 876 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 1348 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 286 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1286 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :tests #:user{fb-t :never-run}, :counts {:never-run 1}}, :contract... |
| `q3-threaded-facts` | owner | PASS | 290 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 6 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 11131 | {:timings {:read 7.363539000000002, :evaluate 245.16333400000005, :derive 941.6417099999999, :apply 4838.304496000001, :settle 653.068916}, :retained-forms 7... |

99/99 proofs pass
