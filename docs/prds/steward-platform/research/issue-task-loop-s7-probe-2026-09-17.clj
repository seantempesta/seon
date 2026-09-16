; Evaluate these forms through MCP JVM mode on default. No test JVM.
; The test namespace is reloaded through the runner's loader, never its fixture.
(require 'seon.test 'seon.operator 'seon.db 'seon.test.runner 'seon.turn)

(def s7-recorded-tests
  (future
    (#'seon.test/with-test-loader
     (fn []
       (require 'seon.issue-test :reload)
       (require 'seon.issue-settlement-test :reload)
       (require 'seon.turn-test :reload)
       (require 'seon.turn-loop-test :reload)))
    (mapv
     (fn [test-symbol]
       (let [connection (seon.operator/connection "default")
             database (seon.db/db connection)]
         (seon.test/run
          (#'seon.test/resolve-test test-symbol)
          connection
          {:seon.db/db database
           :seon.test.run/provenance (seon.test.runner/provenance database)
           :seon.test/remaining-ms 240000})))
     '[seon.issue-test/unchanged-issue-adoption-writes-no-issue-datoms
       seon.issue-test/issue-worker-creation-is-atomic
       seon.issue-test/detector-only-issue-starts-and-settles-from-its-subject
       seon.issue-settlement-test/started-issue-tests-retain-historical-authority
       seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion
       seon.issue-test/issue-worker-opening-links-its-issue
       seon.turn-test/issue-budget-counts-a-call-that-closes-before-the-provider
       seon.turn-test/generated-read-evidence-rejects-turn-activity
       seon.turn-loop-test/one-wake-cannot-open-a-second-turn-after-the-first-closes])))

(if (realized? s7-recorded-tests)
  (mapv #(select-keys % [:seon.test/pass-count :seon.test/fail-count
                        :seon.test/error-count :seon.test/failure-message])
        @s7-recorded-tests)
  :running)

; Read evidence from the existing budget owner, then the existing admission.
(let [database (seon.db/db (seon.operator/connection "default"))
      sink (atom [])
      remaining (binding [seon.db/*read-evidence-sink* sink]
                  (seon.turn/turns-left database "juniper"))
      evidence (seon.db/read-evidence @sink)]
  {:remaining remaining
   :captured (count @sink)
   :refusal
   (#'seon.turn/generated-read-fault
    database
    {:seon.cluster.eval/source
     "(seon.turn/turns-left (seon.db/db) \"juniper\")"}
    {:seon.cluster.eval/read-evidence evidence})})

; Actual live generation request. Choose an open returned issue before start!.
(seon.issue/generate!
 {:seon.db/connection (seon.operator/connection "default")
  :seon.issue/detector "seon.issue.detect/public-without-doc"
  :seon.fn.file/relative-root "src" :seon.issue/severity :cleanup})

; Dated proof identities; do not re-run this start after exhaustion without
; intentionally supplying a larger total budget. The same function resumes.
(seon.issue/start!
 {:seon.db/connection (seon.operator/connection "default")
  :seon.issue/id "65f46c0efa7f" :seon.issue/budget 2})

(mapv #(select-keys % [:seon.cluster.eval/source :seon.eval/shown])
      (filter :seon.eval/origin
              (seon.eval/of-agent
               (seon.db/db (seon.operator/connection "default")) "2393cac275ae")))

; Review revision: origin is a declared ref, never classification of source text.
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:origin-attribute (seon.db/pull database [:db/ident] [:db/ident :seon.eval/origin])
   :stored-remaining (seon.db/pull database [:db/ident] [:db/ident :seon.issue/turns-remaining])
   :origin-recognized (#'seon.turn/issue-origin-read?
                       database {:seon.eval/origin [:seon.issue/id "65f46c0efa7f"]})
   :spelling-alone (#'seon.turn/issue-origin-read?
                    database {:seon.cluster.eval/source "(my.issue/status {:seon.issue/id \"65f46c0efa7f\"})"})
   :spent (seon.turn/episode-runs database "2393cac275ae")
   :remaining (seon.turn/turns-left database "2393cac275ae")
   :messages (seon.db/q
              '[:find [?content ...] :in $ ?id
                :where [?issue :seon.issue/id ?id]
                [?message :seon.message/about ?issue]
                [?message :seon.message/content ?content]] database "65f46c0efa7f")})

; The review probe resumed this SAME worker with total budget 5. It retained
; pending turn 7d739af1dc0f, then completed three provider turns. Do not repeat.
(comment
  (seon.issue/start! {:seon.db/connection (seon.operator/connection "default")
                     :seon.issue/id "65f46c0efa7f" :seon.issue/budget 5}))

; A manual system-turn probe carries the live context's projection, like its proc.
(let [handle (:seon.turn.loop/cluster (#'seon.cluster/mcp-instance "default"))]
  (seon.schema/call-with-projection
   (seon.sci.kernel/context-projection (:seon.sci.eval/ctx handle))
   #(seon.turn/system-turn {:seon.turn.loop/cluster handle
                           :seon.agent/id "2393cac275ae" :seon.turn/write? false})))


; Adoption review: use one immutable database and identity pull pattern.
; Before the fix on PID 53320: 1723 issues, 5946 forms, 3017 retractions.
; Candidate adopt-tx on the same unchanged population: 1723 issues, 0 forms.
(def s7-adoption-delta
  (future
    (let [database (seon.db/db (seon.operator/connection "default"))
          pattern (#'seon.issue/citation-pattern (#'seon.issue/citation-attributes database))
          issues (seon.db/q '[:find [?e ...] :where [?e :seon.issue/path]] database)
          rows (mapv #(seon.db/pull database pattern %) issues)
          tx (seon.issue/adopt-tx database rows)]
      {:seon.issue/count (count issues)
       :seon.issue/forms (count tx)})))

(if (realized? s7-adoption-delta) @s7-adoption-delta :running)

; Run this additional regression via the same seon.test/run form above:
; seon.issue-test/unchanged-issue-adoption-writes-no-issue-datoms
