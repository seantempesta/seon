(ns seon.agent.ctx.testrun
  "The `:test-failures` context section — the agent's CURRENT failing tests.

   The reactive-context pattern (docs/seon/concepts/reactive-context.md): a
   pure fn of the db at render time. It reads the LATEST persisted pytest
   run for the agent (`seon.agent.testrun/record!` projects each run) and
   renders its failing set as a comment block. When the newest run is GREEN
   — or there is no run — it renders NOTHING: fixing the tests and re-running
   supersedes the failures with a green run, the query returns clean, and the
   section VANISHES (self-healing, no stored 'seen' flag).

   Agent-scoped: it shows the running agent's own latest run. Symbol-wired
   into the composer (config manifest) as
   `'seon.agent.ctx.testrun/testrun-block`; required into the boot build
   (`seon.client`) so the symbol resolves."
  (:require
    [clojure.string :as str]
    [seon.agent.testrun :as testrun]
    [seon.db :as db]))

(def ^:private message-cap
  "Per-failure cap on the rendered message — a long assertion repr is
   clipped with an inline `…`; the agent re-runs pytest for the full trace."
  160)

(def ^:private max-failures
  "How many failures to list inline. A huge failing set is capped with a
   loud footer; re-run pytest to see them all."
  20)

(defn- clip
  "Collapse whitespace and cap `s` to [[message-cap]] with an inline `…`."
  [s]
  (let [s (-> (str s) str/trim (str/replace #"\s+" " "))]
    (if (> (count s) message-cap)
      (str (subs s 0 message-cap) "…")
      s)))

(defn- failure-line
  "One `; tests/test_x.py::test_foo — message` row for a pulled failure."
  [{:seon.agent.testrun/keys [test-name path message]}]
  (str "; " path "::" test-name
       (when (seq message) (str " — " (clip message)))))

(defn testrun-block
  "DEPRECATED — reference for the `warnings` milestone; see context-rebuild.

   The agent's latest pytest failures as a block, or empty when green.

   Derived from the newest persisted testrun scoped to this agent. Renders a
   `TEST FAILURES` comment block (counts + one line per failing test) when
   the latest run had failures or errors; renders \"\" when it was green or
   absent (self-healing — see docs/seon/concepts/reactive-context)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (if (or (nil? db) (nil? id))
    ""
    (if-let [{:seon.agent.testrun/keys [eid passed failed errors]}
             (testrun/latest-run db id)]
      (if (and (zero? failed) (zero? errors))
        ""
        (let [failures (:seon.agent.testrun/failures
                        (db/pull {:seon.db/db db :seon.db/ref eid
                                  :seon.db/pull-pattern
                                  '[{:seon.agent.testrun/failures
                                     [:seon.agent.testrun/test-name
                                      :seon.agent.testrun/path
                                      :seon.agent.testrun/message]}]}))]
          (let [shown  (take max-failures failures)
                hidden (- (count failures) (count shown))]
            (str ";;; TEST FAILURES — latest pytest run: "
                 failed " failed, " errors " error" (when (not= errors 1) "s")
                 ", " (or passed 0) " passed\n"
                 (str/join "\n" (map failure-line shown))
                 (when (pos? hidden)
                   (str "\n; … +" hidden " more — re-run pytest to see all"))))))
      "")))
