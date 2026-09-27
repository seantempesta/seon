| proof | class | pass | ms | evidence |
|---|---|---|---|---|
| `v2-7-schema-names` | static | PASS | 17 | {:taken [], :contract-failures [], :contracts 89, :declared 53, :form-failures []} |
| `entrant-merge-write-back` | entrant | PASS | 542 | {:reindexed [true true]} |
| `r5-namespace-facts` | effects | PASS | 436 | {:facts-errors []} |
| `time-h36` | timing | PASS | 5521 | {:ms 5516, :timings {:read 3.074334, :evaluate 299.271625, :show 78.12721000000002, :derive 192.60295800000003, :apply 4207.3992880000005, :settle 733.319583}} |
| `corpus-join` | observer | PASS | 583 | {:writes 102, :by-join {:matched 95, :member 5, :unanalyzed 2}, :agent-macro [["h40-macro-two-defns" user/h40-x] ["h40-macro-two-defns" user/h40-y]], :bad []} |
| `v2-4-schema-reflush` | schema | PASS | 962 | {:values [":review.v2/n" ":int" ":review.v2/n" "[:int {:min 3}]"], :timings {:read 0.545417, :evaluate 28.008834, :show 11.978917, :derive 20.353876000000003... |
| `oq1-storage-failure` | owner | PASS | 96 | {:commit #:seon.batch{:storage-error "storage unavailable"}} |
| `v2-6-tag-once` | input | PASS | 4 | {:reader-calls 1} |
| `time-one-defn` | timing | PASS | 2064 | {:ms [1128 237 235 232 226], :timings {:read 0.10245800000000001, :evaluate 1.303, :show 1.360417, :derive 7.974792, :apply 101.935375, :settle 96.116958}} |
| `agent-starts-in-another-namespace` | effects | PASS | 3259 | {:values ["#'op3.elsewhere/there" ":here"]} |
| `q2-recorded-replies` | owner | PASS | 30 | {:count 52, :designed [6], :ms 25, :diffs []} |
| `expansion-observer` | sci-fork | PASS | 0 | {} |
| `contract-warning` | owner | PASS | 467 | {:note "user/nc1 has no :malli/schema; specify it before proposing a merge.", :warning [user/nc2 user/nc3]} |
| `r8-render-read-only` | entrant | PASS | 27 | {:values ["#'user/leaked" "{:seon.schema/expected-value :seon.db/connection, :seon.error/operation seon.db/missing-connection-error, :seon.schema/refused-val... |
| `write-after-schema-batch` | timing | PASS | 5561 | {:ms [731 428 434], :timings {:read 0.094874, :evaluate 1.493041, :show 2.618042, :derive 14.653666, :apply 203.148791, :settle 174.177125}, :cause "seon.db/... |
| `extensions-marked` | sci-fork | PASS | 0 | {:protocol-writes [:root :root :extend :extend]} |
| `v2-3-later-stages-fail` | totality | PASS | 119 | {:diagnostics ["show: stage failed" "show: stage failed" "conclude: stage failed" "settle: stage failed" "fault: stage failed" "fault: stage failed" "fault: ... |
| `attribute-change-equivalence` | schema | PASS | 872 | {:results [true true true]} |
| `v2-1-nested-callback` | sci-fork | PASS | 0 | {:events ["outer"]} |
| `future-conveys` | sci-fork | PASS | 0 | {:events [user/fut]} |
| `v2-6-repair-before-require` | input | PASS | 52 | {:values [nil 3 :clojure.string/k]} |
| `q1-single-def-kondo` | sci-fork | PASS | 0 | {:made {:name made, :row 2, :col 1, :defined-by clojure.core/def}} |
| `r2-rebound-intern` | observer | PASS | 365 | {:values ["#'user/review-rebound" "#'user/review-rebound" "[:error \"user/review-rebound refused argument 0: expected an integer, got a string. Fix: Supply a... |
| `test-agent` | entrant | PASS | 1422 | {:values ["#'user/op3-add" "true" "false"], :recorded 3} |
| `entrant-mcp-sci-recorded` | entrant | PASS | 298 | {:evaluations 2, :commit #:seon.batch{:applied true, :tx-count 2}} |
| `v2-1-inherited` | sci-fork | PASS | 0 | {:value 12, :events [{:same-var? true, :observed 2}]} |
| `v2-4-register-then-use` | schema | PASS | 560 | {:values [":op3.h16/n" "#'user/h16f" "4" "[:error \"user/h16f refused argument 0: expected :op3.h16/n (an integer at least 0), got an integer -1. Fix: Supply... |
| `captured-locals` | observer | PASS | 326 | {:private #:user{capture-f :captures-locals}} |
| `jvm-gets-feedback` | entrant | PASS | 21 | {:feedback #:seon.feedback{:changed []}} |
| `corpus` | corpus | PASS | 26530 | {:same 84, :designed 10, :facts-errors [["h32-multi-arity" ["Function install refused for user/h32-f: its gate failed. Fix the contract or the function and r... |
| `oq1-bad-form-logged` | owner | PASS | 880 | {:facts-errors ["Schema reference :no.such/key is not declared in the candidate projection; it is named by user/oq1bad. Declare :no.such/key in the same chan... |
| `extension-replay` | decision | PASS | 297 | {:decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"} |
| `v2-7-no-reverse-requires` | static | PASS | 1611 | {:cycles []} |
| `v2-5-read-only-evidence` | effects | PASS | 676 | {:read-only [false false false true]} |
| `jvm` | context | PASS | 0 | {:before {:heap-used-mb 4065, :old-gen-after-gc-mb 4053, :processors 18}, :after {:heap-used-mb 7844, :old-gen-after-gc-mb 4942, :processors 18}} |
| `time-acquisition` | timing | PASS | 15938 | {:entities 100, :ms 15, :us-per-entity 150} |
| `q5-def-edges` | owner | PASS | 1278 | {:calls [[clojure.core/def] [clojure.core/def user/base-v] [clojure.core/defonce clojure.core/inc user/base-v]], :acquired [[42 {:k 41}]]} |
| `v2-3-show-fails` | totality | PASS | 47 | {:shown ["()" "42"], :diagnostics []} |
| `v2-5-remove-rebind` | effects | PASS | 519 | {:source "(def rr-v2 2)"} |
| `record-acquires-once` | observer | PASS | 333 | {:sources ("(defrecord OpR [a])"), :acquired [7]} |
| `mcp-equals-agent-turn` | entrant | PASS | 3904 | {:behaviors 8, :differ []} |
| `ns-and-defn-in-one-form` | effects | PASS | 1996 | {:facts-errors []} |
| `entrant-gate` | entrant | PASS | 592 | {:facts-errors ["Function install refused for user/gt1: its gate failed. Fix the contract or the function and re-evaluate the defn."]} |
| `v2-5-remove-ns` | effects | PASS | 1213 | {:namespace-facts nil, :commit #:seon.batch{:applied true, :tx-count 1}} |
| `v2-8-jvm-recorded` | entrant | PASS | 34 | {:values ["" "A" "[:error \"Divide by zero\"]" "3"], :commit #:seon.batch{:applied true, :tx-count 1}} |
| `r4-new-then-removed` | effects | PASS | 1142 | {} |
| `sci-suite` | sci-fork | PASS | 1 | {:patched {:tests 397, :assertions 1517, :fail-count 5, :errors 0}, :base {:tests 393, :assertions 1485, :fail-count 5, :errors 0}, :failures ["FAIL in (arit... |
| `v2-3-ns-name-shadow` | totality | PASS | 284 | {:values ["42" "#'user/ns-name" "43"]} |
| `q4-lone-key` | owner | PASS | 1092 | {:attribute #:db{:valueType :db.type/long, :cardinality :db.cardinality/one}, :notes [{:seon.schema/key :one.path.q4/f, :seon.schema/installed? false, :seon.... |
| `r1-every-write` | sci-fork | PASS | 0 | {:retained [{:root 2, :origin {:line 1, :column 1, :end-line 1, :end-column 10}} {:root 3, :origin nil} {:root 4, :origin nil}]} |
| `v2-3-observer-fails` | totality | PASS | 33 | {:diagnostics ["observe: stop failed"]} |
| `v2-8-nested-render-one-tx` | entrant | PASS | 256 | {:commits 1, :values ["#'user/nr" "[\"3\"]"]} |
| `q2-live-reply` | owner | PASS | 572 | {:repairs [:prose :prompt-marker :delimiters :prose], :text "; Let me require it first.\n(require '[clojure.string :as str])\n::str/k\n(+ 1 2)\n(defn bad [x]... |
| `test-agents-concurrent` | entrant | PASS | 659 | {:branches [":agent-b9393a02b3b2" ":agent-670cce12031d"]} |
| `v2-2-generated-in-let` | sci-fork | PASS | 0 | {:origins [{:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair} {:line 3, :column 3, :end-line 3, :end-column 9, :expanded-by pair}]} |
| `v2-2-single-def-macro` | sci-fork | PASS | 0 | {:origin {:line 2, :column 1, :end-line 2, :end-column 9, :expanded-by single}} |
| `oq1-head-moved` | owner | PASS | 1433 | {:facts-errors ["Program retraction refused."], :first-refusal "Program retraction refused."} |
| `v2-1-throwing-watch` | sci-fork | PASS | 0 | {:answer "watch failed", :events [{:op :bind, :root 2}]} |
| `v2-3-span-invalid` | totality | PASS | 34 | {:diagnostics ["read: the read stage answered span [0, 999) outside its text of 2 characters"]} |
| `feedback-from-facts` | owner | PASS | 1782 | {:feedback #:seon.feedback{:changed [user/fb-f user/fb-g], :missing-contracts [user/fb-f], :reaching-tests #:user{fb-f [{:seon.test/sym user/fb-t, :seon.feed... |
| `q3-threaded-facts` | owner | PASS | 230 | {:values ["#'user/q3f" "First doc." "#'user/q3f" "Second doc."]} |
| `two-threads-one-context` | sci-fork | PASS | 0 | {:counts [50 50]} |
| `time-chained-40` | timing | PASS | 7680 | {:ms 7655, :forms 79, :timings {:read 6.653331000000002, :evaluate 146.090003, :show 135.055578, :derive 949.467046, :apply 4935.947501000001, :settle 293.28... |

63/63 proofs pass
