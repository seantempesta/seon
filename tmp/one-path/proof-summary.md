| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 233 | {:taken [], :contract-failures [], :contracts 113, :declared 59, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 585 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 287 | {:facts-errors []} |
| `time-h36` | timing | PASS | 6362 | {:ms 6103, :timings {:read 2.248624, :evaluate 204.43566699999997, :derive 78.58212499999999, :apply 4069.2349169999998, :settle 1747.907958}} |
| `b7-lazy-definition` | effects | PASS | 469 | {:shown ["(42)"], :diagnostics []} |
| `corpus-join` | observer | PASS | 600 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `world-members-agree` | world | PASS | 883 | {:worlds {:agent-turn {:db-is-connections true, :ctx-custody-is-connection true, :writer-takes-connection true, :landed true, :own-branch? true, :elsewhere f... |
| `v2-4-schema-reflush` | schema | PASS | 309 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.296124, :evaluate 21.836083000000002, :derive 7.3395, :apply 112.9487080... |
| `speculative-batch-keeps-committed-projection` | schema | PASS | 3756 | {:ruling-b-ms {:before 32, :after 28}, :head-path-ms {:before 4, :after 4}} |
| `oq1-storage-failure` | owner | PASS | 316 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `world-survives-compute-hop` | world | PASS | 860 | {:here #:seon.agent{:id "root"}, :bare {:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema... |
| `v2-6-tag-once` | input | PASS | 187 | {:reader-calls 1} |
| `b6-jvm-throwing-print-and-error` | entrant | PASS | 53 | {:shown ["#object[one-path-proof.host/eval4702632/reify--4702633]" "#:seon.error{:exception-class clojure.lang.ExceptionInfo,\n :message \"jvm boom\"}"], :di... |
| `time-one-defn` | timing | PASS | 2214 | {:ms [244 221 214 248 219], :timings {:read 0.11924900000000001, :evaluate 2.384083, :derive 7.439792, :apply 79.74475, :settle 125.547667}, :batch-retained-... |
| `r6-e1-opening-reads-the-inbox` | parity | PASS | 1025 | {:inbox-reads ["(my.message/inbox {:seon.agent/id \"root\"})"], :walk-duplicates 1, :opening-forms 10} |
| `agent-starts-in-another-namespace` | effects | PASS | 670 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 188 | {:count 52, :designed [6], :ms 22, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 1422 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3 user/nc1]} |
| `b5-contracts-gate` | landing | PASS | 1026 | {:ms 338, :findings 0, :each []} |
| `r8-render-read-only` | entrant | PASS | 242 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 6314 | {:ms [279 218 230], :timings {:read 0.11787500000000001, :evaluate 2.067667, :derive 8.075208, :apply 88.272583, :settle 126.943875}, :cause "seon.db/nearest... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 241 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 919 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `b3-malformed-tag-read-once` | reader | PASS | 0 | {:reader-calls 1, :reason "bad tag"} |
| `v2-6-repair-before-require` | input | PASS | 83 | {:values [nil 3 :clojure.string/k]} |
| `b1-value-equal-to-sentinel` | totality | PASS | 0 | {:diagnostics [["observe: review injected failure"] ["observe: review injected failure"] ["observe: review injected failure"]]} |
| `gate-runs-tests-in-their-namespace` | gate | PASS | 1328 | {:gate #:seon.gate{:sym gjf.core/f, :blocks? false, :tests 1}, :refusal {:seon.gate/sym gjf.core/f, :seon.gate/blocks? true, :seon.gate/tests 1, :seon.gate/n... |
| `b1-malformed-observation` | totality | PASS | 0 | {:diagnostics ["observe: 2 observed writes were not maps and were dropped"]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 438 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `gate-judges-the-change` | gate | PASS | 1391 | {:still-red-gate #:seon.gate{:sym user/gj, :blocks? false, :tests 2, :still-red [#:seon.test{:pass-count 0, :fail-count 1, :error-count 0, :sym user/gj-red}]... |
| `parity-faults-in-both-replies` | parity | PASS | 1023 | {:mcp-faults 1, :agent-faults 1, :diagnostics ["show: [{:seon.error/at #inst \"2026-09-27T23:14:17.459Z\",\n  :seon.error/data\n    #:seon.render.unknown{:ca... |
| `test-agent` | entrant | PASS | 1136 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 479 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `turn-overhead` | timing | PASS | 8826 | {:kept 22, :kept-plan-chars [4536371 15640 1533 991 652 618 608 608 608 608 608 278], :remaining {:largest-plan "seon.error/for-agent's read stores one whole... |
| `b2-generated-definition-hook` | join | PASS | 5 | {:made [{:name made, :row 2, :col 1, :end-row 2, :end-col 9}]} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `b3-repair-reads-tag-once` | reader | PASS | 0 | {:reader-calls 1, :result {:forms ["[#review/tag 1]"], :text "[#review/tag 1]", :repairs [#:seon.batch{:repair-kind :delimiters, :at 0}]}} |
| `v2-4-register-then-use` | schema | PASS | 584 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 431 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 229 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 50363 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["The install gate refused user/h32-f: its own contract check failed"]]], :entities 73, :differ []... |
| `oq1-bad-form-logged` | owner | PASS | 463 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 221 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `b4-moved-head-feedback` | effects | PASS | 1538 | {:errors ["Program retraction refused."], :feedback #:seon.feedback{:changed [user/review-new], :missing-contracts [user/review-new]}} |
| `v2-7-no-reverse-requires` | static | PASS | 1541 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 618 | {:read-only [false false false true]} |
| `sink-per-world` | world | PASS | 600 | {:a #:user{counted 1000}, :b #:user{counted 1500}, :jvm-wide 2500} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 4454, :old-gen-after-gc-mb 2095, :processors 18}, :after {:heap-used-mb 5107, :old-gen-after-gc-mb 2405, :processors 18}} |
| `time-acquisition` | timing | PASS | 23495 | {:entities 100, :ms 11, :us-per-entity 110} |
| `q5-def-edges` | owner | PASS | 529 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 108 | {:shown ["()" "42"], :diagnostics ["show: [#:seon.error{:at #inst \"2026-09-27T23:14:20.937Z\",\n  :layer :seon.render/invocation,\n  :operation seon.sci.eva... |
| `v2-5-remove-rebind` | effects | PASS | 389 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 403 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `b1-nil-print-failure` | totality | PASS | 0 | {:entries [#:seon.batch{:ordinal 0, :ns-before user, :value nil, :source "nil", :line 1, :shown "#<unprintable nil>", :column 1, :ns-after user}], :diagnosti... |
| `mcp-equals-agent-turn` | entrant | PASS | 9479 | {:behaviors 9, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 230 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 533 | {:facts-errors ["The install gate refused user/gt1: its own contract check failed"]} |
| `v2-5-remove-ns` | effects | PASS | 613 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `b6-jvm-floor` | entrant | PASS | 551 | {:jvm-shown-chars 3192, :sci-shown-chars 3192, :jvm-tail "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... |
| `v2-8-jvm-recorded` | entrant | PASS | 65 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1179 | {} |
| `sci-suite` | sci-fork | PASS | 0 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 575 | {:values ["42" "#'user/ns-name" "43"]} |
| `r6-shown-by-the-one-print-owner` | print | PASS | 65 | {:shown ["{:seon.error/exception-class clojure.lang.ExceptionInfo,\n :seon.sci.eval/stacktrace [[user 1 1]],\n :seon.error/message \"owner\"}" "(+ 1 2)" "{:a... |
| `q4-lone-key` | owner | PASS | 497 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `b4-writer-refusal-reply` | totality | PASS | 476 | {:reply #:seon.batch{:commit #:seon.batch{:writer-refusal #:seon.error{:message "review writer refusal"}, :first-refusal "review writer refusal"}, :diagnosti... |
| `v2-3-observer-fails` | totality | PASS | 99 | {:diagnostics ["observe: stop failed"]} |
| `feedback-hub-fresh` | timing | PASS | 1282 | {:bounded-text {:fresh-ms 19, :tests 10, :counts {:never-run 10}}, :shown-value {:fresh-ms 13, :tests 22, :counts {:never-run 22}}, :config-effective {:fresh... |
| `v2-8-nested-render-one-tx` | entrant | PASS | 258 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 244 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 665 | {:branches [":agent-20a76ae2ed8e" ":agent-fdff5fe99fed"]} |
| `b2-exact-span-no-fallback` | join | PASS | 0 | {:join #:seon.batch{:join :unanalyzed, :reason :no-definition-at-origin}} |
| `parity-render-refusal-never-parks` | parity | PASS | 1061 | {:mcp {:applied? true, :stored? true, :values 2, :shown "{:seon.render/ai no.such/renderer, :x 1}"}, :agent {:applied? true, :stored? true, :values 2, :shown... |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `gate-callee-contract-is-feedback` | gate | PASS | 1207 | {:findings [#:seon.gate{:contract user/cal2, :arguments [-1]}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1515 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `r6-unresolved-names-kept-as-values` | effects | PASS | 814 | {:calls #{clojure.core/defn clojure.set/union}, :requires #{seon.bootstrap clojure.test clojure.set seon.db my.turn my.message}} |
| `worlds-never-cross` | world | PASS | 1193 | {:sources ["(def op3-cross :a)" "(def op3-cross :b)"]} |
| `v2-3-span-invalid` | totality | PASS | 439 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1174 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :tests #:user{fb-t :never-run}, :counts {:never-run 1}}, :contract... |
| `q3-threaded-facts` | owner | PASS | 254 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `b7-concurrent-observation` | effects | PASS | 2 | {:observed 8000, :late "The evaluation that owned this observation has completed."} |
| `time-chained-40` | timing | PASS | 19244 | {:timings {:read 7.983127999999998, :evaluate 212.51099700000006, :derive 836.7979510000001, :apply 14110.272003, :settle 448.270875}, :retained-forms 79, :b... |

92/92 proofs pass
