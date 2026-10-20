;; Writes static.md from functions.edn, tests.edn, namespaces.edn. Run after analyze.clj.
(require '[clojure.edn :as edn] '[clojure.string :as str] 'clojure.set)
(def dir "docs/research/agent-platform/2026-10-20/census/")
(def fs (edn/read-string (slurp (str dir "functions.edn"))))
(def ts (edn/read-string (slurp (str dir "tests.edn"))))
(def nss (edn/read-string (slurp (str dir "namespaces.edn"))))
(def rr (edn/read-string (slurp (str dir "rr-crosscheck.edn"))))
(def sb (StringBuilder.))
(defn w [& xs] (.append sb (apply str xs)) (.append sb "\n"))
(defn row [& cells] (w "| " (str/join " | " cells) " |"))
(defn tbl [hdr rows] (apply row hdr) (apply row (map (constantly "---") hdr)) (doseq [r rows] (apply row r)) (w))
(defn lines [xs] (reduce + (keep :census/lines xs)))
(defn fr [k xs] (into (sorted-map) (frequencies (map k xs))))
(def contractable (filter #(#{:fn :macro} (:census/kind %)) fs))
(def verdicts [:port :replaced :unclear])
(def port-fs (filter #(= :port (:census/verdict %)) fs))
(def ns-ver (into {} (map (juxt :census/ns :census/verdict)) nss))

(w "---\ntitle: \"Static census of src and script (2026-10-20)\"\ntype: research\n---\n")
(w "# Static census of src and script (2026-10-20)\n")
(w "Lane census-static. Evidence only; nothing in `src`, `script` or `test` changed. Data: `functions.edn` (one map per function), `tests.edn` (one per test), `namespaces.edn` (one per namespace with its verdict), `verdicts.edn` (the hand-written verdict inputs), `ideas.md` (the headline: ideas worth carrying over).\n")
(w "Reproduce: dump forms in `dump-forms.clj` (JVM-mode reads on default), then `analyze.clj`, `report.clj`, from the checkout root with only Clojure on the classpath.\n")

(w "## Data I could not get, exactly\n")
(w "- **Stored reaching-test facts do not exist on default.** `:seon.test/reach`, `:seon.test/reach-unknown` and `:seon.test/run` have zero datoms. The reach columns are my own static closure over `:seon.fn/calls` (every call edge the analysis stored, src, script and test helpers), run from each of the " (count ts) " stored test entities. It undercounts tests that reach a function by `requiring-resolve`, `resolve` or a quoted name (266 test sites, per `requiring-resolve-inventory.md`): for example `my.test` shows 0 reaching tests. It overcounts only through hubs: a function almost every test touches (for example `seon.error.problem`) reads 2,500+, so read the reach counts of hub functions as everything.")
(w "- **Stored test results are almost nonexistent.** Default holds 3 `:seon.test.run/id` entities (all from 2026-10-19 08:46, history has the same 3): 2 tests with fail counts, 1 green. The other stored evidence is the red list of one partial-history operator run, `data/clusters/default/logs/operator-check-20261019T230731.687.edn` (candidate `d7e547e25`, 746 executed, 41 red): it records reds only, never a pass. So a test is `:red` if it is in either source, `:green` only if its stored member run passed, else `:never-run`. Test results kept on the 62 other branches of default's store were not read (a per-branch history walk is not a small request).")
(w "- **Per-function \"last result\"** is derived from those results through my static reach, and means `reached-by-red` (at least one stored-red test reaches it), `reached-by-green-only`, `never-run` (reached, but no reaching test has any stored result) or `no-tests` (no static reach). Because the reds sit on hubs, `reached-by-red` is a very coarse signal; the red/green counts are in the rows (`:census/red-tests`, `:census/green-tests`).")
(w "- **Assertion counts** are a textual count of `(is`/`(are`/`(thrown?` forms in the stored test source, not a stored fact (none is stored). `:census/asserts` 0 means the test body has none of those forms (it may assert through a helper).")
(w "- **Timing bound** is `:seon.test/long-ms`, else `:seon.test/check-time-limit-ms`, else the 5000 ms default; none of the measured run times except the 3 stored runs exist.")
(w "- **Lines** are the stored `:seon.fn/source` line count of each definition (sum " (lines fs) " against 107,546 file lines in `src`). **Entrants** are not a stored fact: I derived them (below).")
(w "- **Run-time lookup cross-check** against `docs/research/agent-platform/2026-10-19/requiring-resolve-inventory.md`: that document lists categories and ten cycles, not every site. I parsed the per-site scratch JSON it names (`tmp/rr-inventory/final.json`, gitignored, written 2026-10-19 20:10): " (:inventory-enclosing-fns rr) " non-prose enclosing functions, of which " (:inventory-fns-found-in-census rr) " are src/script functions still present on default; my call-edge detection finds " (:both rr) " of those " (:inventory-fns-found-in-census rr) ", misses " (count (:inventory-only rr)) " (the lookup is inside a nested `delay`/`fn` that the analysis attributes to another Var, or the function changed since) and adds " (count (:census-only rr)) " the 10-19 scan did not list (post-scan changes, `ns-resolve`/`var-get` forms). Lists in `rr-crosscheck.edn`.\n")

(w "## Totals\n")
(tbl ["Measure" "Value"]
     [["Functions (every `def` form in `src` + `script`)" (str (count fs) " (src " (count (filter #(str/starts-with? (:census/file %) "src/") fs)) ", script " (count (filter #(str/starts-with? (:census/file %) "script/") fs)) ")")]
      ["Lines in those definitions" (lines fs)]
      ["Kinds" (pr-str (fr :census/kind fs))]
      ["Private / public" (str (count (filter :census/private? fs)) " / " (count (remove :census/private? fs)))]
      ["Host-bound" (count (filter :census/host-bound? fs))]
      ["Namespaces" (count nss)]
      ["Tests (stored deftests; in `test/`)" (count ts)]
      ["Test namespaces" (count (distinct (map :census/ns ts)))]
      ["Run-time lookup functions (src/script)" (count (filter :census/lookups fs))]
      ["Functions whose body has a `try` (catch/finally)" (count (filter :census/try? fs))]
      ["Functions with a docstring" (count (filter :census/doc? fs))]])

(w "## Contract status\n")
(w "Applies to `:fn` and `:macro` kinds (" (count contractable) " of " (count fs) "; constants, plain defs and types are `:not-applicable`). `complete` = a stored `:seon.fn/spec` with no `[:fn`, no `:any`, no `:some` and fewer than two bare `:map`; `weak` = a spec with at least one of those; `none` = no stored spec; `malformed` = `:seon.fn/malformed-spec` (preserved as data, not instrumented).\n")
(tbl ["Status" "All functions" "Private" "Public" "Lines"]
     (for [k [:complete :weak :none :malformed]]
       (let [c (filter #(= k (:census/contract %)) contractable)]
         [(name k) (count c) (count (filter :census/private? c)) (count (remove :census/private? c)) (lines c)])))
(tbl ["Verdict" "complete" "weak" "none" "malformed"]
     (for [v verdicts :let [c (filter #(= v (:census/verdict %)) contractable) m (fr :census/contract c)]]
       [(name v) (get m :complete 0) (get m :weak 0) (get m :none 0) (get m :malformed 0)]))

(w "## Replacement verdicts (against BAGGAGE.md and NEEDS.md)\n")
(w "`port` means the **concept** is needed by the Jolt version; it does not mean the code is carried over. A `port` namespace names what inside it the Jolt architecture drops (`replaced-parts` in `namespaces.edn`). `replaced` means the Jolt architecture removes the need. `unclear` has a stated reason (an owner decision or no row). Citation per namespace is in `namespaces.edn` (`:census/rows`), written as `NEEDS#n` (capability table row n), `NEEDS-OD n` (owner-decides n) or `BAGGAGE:<section>`.\n")
(tbl ["Verdict" "Namespaces" "Functions" "Lines" "Share of lines" "Run-time lookup fns" "try fns" "Uncontracted (none)"]
     (for [v verdicts :let [f (filter #(= v (:census/verdict %)) fs) c (filter #(#{:fn :macro} (:census/kind %)) f)]]
       [(name v) (count (filter #(= v (:census/verdict %)) nss)) (count f) (lines f) (format "%.0f%%" (* 100.0 (/ (lines f) (lines fs)))) (count (filter :census/lookups f)) (count (filter :census/try? f)) (count (filter #(= :none (:census/contract %)) c))]))
(w "`port` namespaces by what the Jolt tree already has (NEEDS status column): lines in `done` namespaces " (lines (filter #(= :done (:census/jolt-status (first (filter (fn [n] (= (:census/ns n) (:census/ns %))) nss)))) port-fs)) ", `partial` " (lines (filter #(= :partial (:census/jolt-status (first (filter (fn [n] (= (:census/ns n) (:census/ns %))) nss)))) port-fs)) ", `missing` " (lines (filter #(= :missing (:census/jolt-status (first (filter (fn [n] (= (:census/ns n) (:census/ns %))) nss)))) port-fs)) ".\n")
(w "NEEDS.md puts the Jolt need at about 11 percent of Seon's lines (~11,400 of ~100,000 including what is still to build). The `port` namespaces here hold " (format "%.0f%%" (* 100.0 (/ (lines port-fs) (lines fs)))) " of the lines, so the port is overwhelmingly of ideas and tests, not of code: see `ideas.md` for what each idea needs in its simplest form.\n")
(w "### Port namespaces, largest first\n")
(tbl ["Namespace" "Lines" "Fns" "Jolt status" "Rows" "What the Jolt architecture drops from it"]
     (for [n (take 30 (sort-by (comp - :census/lines) (filter #(= :port (:census/verdict %)) nss)))]
       [(str "`" (:census/ns n) "`") (:census/lines n) (:census/functions n) (name (:census/jolt-status n)) (str/join ", " (:census/rows n)) (or (:census/replaced-parts n) "")]))
(w "### Replaced namespaces, largest first (top 25)\n")
(tbl ["Namespace" "Lines" "Fns" "Rows" "Why"]
     (for [n (take 25 (sort-by (comp - :census/lines) (filter #(= :replaced (:census/verdict %)) nss)))]
       [(str "`" (:census/ns n) "`") (:census/lines n) (:census/functions n) (str/join ", " (:census/rows n)) (:census/reason n)]))
(w "### Unclear namespaces\n")
(tbl ["Namespace" "Lines" "Fns" "Reason"]
     (for [n (sort-by (comp - :census/lines) (filter #(= :unclear (:census/verdict %)) nss))]
       [(str "`" (:census/ns n) "`") (:census/lines n) (:census/functions n) (:census/reason n)]))

(w "## Uncontracted functions in `port` namespaces\n")
(def port-nss (set (map :census/ns (filter #(= :port (:census/verdict %)) nss))))
(let [none (filter #(and (= :none (:census/contract %)) (port-nss (:census/ns %))) contractable)
      weak (filter #(and (= :weak (:census/contract %)) (port-nss (:census/ns %))) contractable)]
  (w (count none) " functions have no contract and " (count weak) " have a weak one, in `port` namespaces. Private: " (count (filter :census/private? none)) " / " (count none) " uncontracted. Top 30 uncontracted by callers (src/script callers; then lines):\n")
  (tbl ["Function" "Callers" "Test callers" "Lines" "Private" "Reaching tests"]
       (for [f (take 30 (sort-by (juxt (comp - :census/callers) (comp - :census/lines)) none))]
         [(str "`" (:census/sym f) "`") (:census/callers f) (:census/test-callers f) (:census/lines f) (:census/private? f) (:census/reaching-tests f)]))
  (w "Port namespaces by uncontracted count:\n")
  (tbl ["Namespace" "None" "Weak" "Complete"]
       (for [n (take 15 (sort-by #(- (get-in % [:census/contract-counts :none] 0)) (filter #(port-nss (:census/ns %)) nss)))]
         [(str "`" (:census/ns n) "`") (get-in n [:census/contract-counts :none] 0) (get-in n [:census/contract-counts :weak] 0) (get-in n [:census/contract-counts :complete] 0)])))

(w "## Run-time lookups in `port` namespaces\n")
(let [lk (filter #(and (:census/lookups %) (port-nss (:census/ns %))) fs)]
  (w (count lk) " functions in `port` namespaces call a run-time name lookup (`requiring-resolve`, `resolve`, `ns-resolve`, `find-var`, `find-ns`, `intern`, `var-get`, plus the enumerators `the-ns`, `ns-publics`, `ns-interns`, `ns-map`). Per the 2026-10-19 inventory, 63 of the 178 production sites are load-cycle workarounds and 61 more are lazy by choice, so most of these are not needed by an idea; each is a place the idea is more complicated than it has to be.\n")
  (tbl ["Namespace" "Functions with a lookup" "Lookup forms"]
       (for [[ns xs] (sort-by (comp - count val) (group-by :census/ns lk))]
         [(str "`" ns "`") (str/join ", " (map #(str "`" (name (:census/sym %)) "`") (take 8 xs)) ) (frequencies (mapcat :census/lookups xs))])))
(w "Full list with symbol: filter `functions.edn` on `:census/lookups`.\n")

(w "## Tests\n")
(tbl ["State" "Tests"]
     (for [[k v] (fr :census/last-result ts)] [(name k) v]))
(w "Result sources: " (pr-str (fr :census/result-source ts)) ".\n")
(tbl ["Measure" "Value"]
     [["Declared long (`:seon.test/long`)" (count (filter :census/long? ts))]
      ["Platform (`:seon.test/platform`, a throwaway JVM)" (count (filter :census/platform? ts))]
      ["Time bound source" (pr-str (fr :census/time-bound-source ts))]
      ["Tests with zero textual assertions" (count (filter #(zero? (:census/asserts %)) ts))]
      ["Assertions (textual count)" (reduce + (keep :census/asserts ts))]
      ["Median / max reach (src/script fns, static)" (str (nth (sort (map :census/reach-src ts)) (quot (count ts) 2)) " / " (apply max (map :census/reach-src ts)))]
      ["Tests reaching 0 src/script functions statically" (count (filter #(zero? (:census/reach-src %)) ts))]])
(w "### Red in stored evidence (" (count (filter #(= :red (:census/last-result %)) ts)) ")\n")
(doseq [t (filter #(= :red (:census/last-result %)) ts)] (w "- `" (:census/sym t) "` (" (name (:census/result-source t)) ")"))
(w)
(w "### Never run\n")
(w (count (filter #(= :never-run (:census/last-result %)) ts)) " of " (count ts) " tests have no stored result on default. That is not a pass; it is the absence of evidence, and it is the dominant finding of the test census: the stored program has 3,471 tests and 3 stored runs. The " (count (filter #(= :red (:census/last-result %)) ts)) " reds above (41 from the 10-19 run's red list, plus one that is red only in default's own stored run) are the only red evidence, so they are a lower bound. Per-namespace counts of never-run tests are derivable from `tests.edn`.\n")
(w "Longest static reach (top 10 tests, src/script functions):\n")
(tbl ["Test" "Reach" "Asserts" "Long"] (for [t (take 10 (sort-by (comp - :census/reach-src) ts))] [(str "`" (:census/sym t) "`") (:census/reach-src t) (:census/asserts t) (:census/long? t)]))

(w "## Dead code\n")
(w "Dead means: no src/script caller, no test caller, no reaching test, and not an entrant (`:census/dead :direct`); or not reachable by static call edges from any entrant or test (`:transitive`). Entrants (" (count (filter :census/entrant fs)) "): `-main`, named in a resource/`bin`/`deps.edn` file (`referenced-by-resource`, " (count (filter #(= :referenced-by-resource (:census/entrant %)) fs)) ", generous: a symbol named in a schema or a render attachment counts), public `my.*` agent verbs, public script functions, macros. Reachability by a name resolved at run time is invisible to call edges, so a function reached only by `requiring-resolve` appears `:direct` here only if it has no static caller anywhere; check before deleting.\n")
(tbl ["Function" "Kind" "Lines" "Private" "Verdict" "Class"]
     (for [f (filter :census/dead fs)] [(str "`" (:census/sym f) "`") (name (:census/kind f)) (:census/lines f) (:census/private? f) (name (:census/verdict f)) (name (:census/dead f))]))
(w "Counts by verdict: " (pr-str (fr :census/verdict (filter :census/dead fs))) ". Rows of kind `type` are the generated `->Rec`/`map->Rec` constructors of a record: they are dead only if the record is never constructed, which this call-edge view cannot tell from a constructor used through interop.\n")
(w "Functions with no static test reach (any caller): " (count (filter #(= :no-tests (:census/last-result %)) fs)) "; of those with no src/script caller either and not entrants: " (count (filter #(and (= :no-tests (:census/last-result %)) (zero? (:census/callers %)) (not (:census/entrant %))) fs)) ".\n")
(spit (str dir "static.md") (str sb))
(println "static.md" (count (str sb)) "chars")
