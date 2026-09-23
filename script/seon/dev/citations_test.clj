(ns seon.dev.citations-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.citations :as citations]))

(defn- git!
  "Run git in `root`; a nonzero exit fails the fixture with its output."
  [root & argv]
  (let [{:keys [exit out err]}
        (process/sh (into ["git" "-c" "user.email=citations@test" "-c" "user.name=citations"
                           "-c" "core.hooksPath=/dev/null"]
                          argv)
                    {:dir root})]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:argv argv :exit exit :out out :err err})))
    out))

(defn- write! [root path text]
  (let [f (fs/path root path)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- commit! [root message & paths]
  (apply git! root "add" "--" paths)
  (git! root "commit" "-q" "-m" message "--" ))

(defn- run-check [root & documents]
  (citations/check {::citations/root root ::citations/documents (vec documents)}))

(defn- statuses [report]
  (mapv (juxt ::citations/text ::citations/status) (::citations/results report)))

(def ^:private source
  (str "(ns demo.core)\n"
       "\n"
       "(defn helper\n"
       "  [x]\n"
       "  (inc x))\n"
       "\n"
       "(defn target\n"
       "  [x]\n"
       "  (let [y (helper x)]\n"
       "    (* 2 y)))\n"))

(def ^:private skill
  (str "# Demo\n"
       "\n"
       "`demo.core/target` (`src/demo/core.clj:7`) doubles its helper,\n"
       "and `helper` is defined first (`:3`). The doubling itself is\n"
       "`src/demo/core.clj:10`.\n"
       "\n"
       "A missing file (`src/demo/gone.clj:4`) is stale.\n"))

(defn- with-repository [f]
  (let [root (str (fs/canonicalize (fs/create-temp-dir {:prefix "citations-test"})))]
    (try
      (git! root "init" "-q")
      (write! root "src/demo/core.clj" source)
      (write! root "SKILL.md" skill)
      (commit! root "start" "src/demo/core.clj" "SKILL.md")
      (f root)
      (finally (fs/delete-tree root)))))

(deftest parsing-names-each-citation-and-its-symbol
  (let [cites (citations/citations "SKILL.md" skill)]
    (is (= [["src/demo/core.clj:7" "src/demo/core.clj" 7 7 ["demo.core/target"]]
            [":3" "src/demo/core.clj" 3 3 ["helper"]]
            ["src/demo/core.clj:10" "src/demo/core.clj" 10 10 []]
            ["src/demo/gone.clj:4" "src/demo/gone.clj" 4 4 []]]
           (mapv (juxt ::citations/text ::citations/cited-path ::citations/start
                       ::citations/end ::citations/candidates)
                 cites))))
  (testing "a (`sym`) right after a citation names it; a `sym`, `p:N` list pairs in order"
    (let [text (str "| `x.clj:9` (`beta`), `:12` (`gamma`) |\n"
                    "\n"
                    "`one`, `two` and `three` (`y.clj:1`, `:2`, `:3`).\n")]
      (is (= [["x.clj:9" ["beta"]]
              [":12" ["gamma" "beta"]]
              ["y.clj:1" ["one" "three" "two"]]
              [":2" ["two"]]
              [":3" ["three"]]]
             (mapv (juxt ::citations/text ::citations/candidates)
                   (citations/citations "t.md" text)))))))

(deftest a-shifted-anchor-fails-by-name-and-fix-repairs-it
  (with-repository
    (fn [root]
      (testing "at the committed state every symbol-bearing citation is current"
        (let [report (run-check root "SKILL.md")]
          (is (= [["src/demo/core.clj:7" ::citations/current]
                  [":3" ::citations/current]
                  ["src/demo/core.clj:10" ::citations/current]
                  ["src/demo/gone.clj:4" ::citations/missing-file]]
                 (statuses report)))))
      ;; another writer inserts three lines above both definitions and commits
      (write! root "src/demo/core.clj"
              (str/replace-first source "\n\n(defn helper" "\n\n(def a 1)\n(def b 2)\n\n(defn helper"))
      (commit! root "shift" "src/demo/core.clj")
      (let [report (run-check root "SKILL.md")
            by-text (into {} (map (juxt ::citations/text identity)) (::citations/results report))]
        (testing "the checker names the citation, its old line and its current line"
          (is (= ::citations/drifted (::citations/status (by-text "src/demo/core.clj:7"))))
          (is (= 10 (::citations/current-start (by-text "src/demo/core.clj:7"))))
          (is (= 6 (::citations/current-start (by-text ":3"))))
          (is (str/includes? (citations/format-result (by-text "src/demo/core.clj:7"))
                             "SKILL.md:3 `src/demo/core.clj:7` (demo.core/target) drifted: now at 10")))
        (testing "a symbol-less citation is relocated through its committed lines"
          (is (= ::citations/drifted (::citations/status (by-text "src/demo/core.clj:10"))))
          (is (= 13 (::citations/current-start (by-text "src/demo/core.clj:10"))))))
      (citations/fix! {::citations/root root ::citations/documents ["SKILL.md"]})
      (let [text (slurp (str (fs/path root "SKILL.md")))]
        (is (str/includes? text "`demo.core/target` (`src/demo/core.clj:10`)"))
        (is (str/includes? text "(`:6`)"))
        (is (str/includes? text "`src/demo/core.clj:13`."))
        (is (str/includes? text "(`src/demo/gone.clj:4`)")
            "a missing file is reported, never rewritten"))
      (is (= [::citations/current ::citations/current ::citations/unverifiable
              ::citations/missing-file]
             (mapv second (statuses (run-check root "SKILL.md"))))
          "after the fix the symbol citations verify; the rewritten symbol-less
           one has no baseline until it is committed, and says so"))))

(deftest another-writers-uncommitted-lines-are-read-at-head
  (with-repository
    (fn [root]
      (write! root "src/demo/core.clj" (str ";; uncommitted\n;; lines\n" source))
      (let [report (run-check root "SKILL.md")]
        (is (= ::citations/current
               (::citations/status (first (::citations/results report)))))
        (is (= "HEAD" (::citations/read-at (first (::citations/results report))))))
      (testing "a commit that carries the target reads it as it stands"
        (let [report (citations/check {::citations/root root
                                       ::citations/documents ["SKILL.md"]
                                       ::citations/worktree-paths ["src/demo/core.clj"]})]
          (is (= ::citations/drifted
                 (::citations/status (first (::citations/results report))))))))))

(deftest a-commit-carrying-a-skill-file-is-checked-and-refused-on-drift
  (with-repository
    (fn [root]
      (write! root ".agents/skills/demo/SKILL.md"
              "`demo.core/target` (`src/demo/core.clj:7`) doubles.\n")
      (let [request (fn [command]
                      (citations/commit-request {::citations/root root ::citations/cwd root
                                                 ::citations/command command}))]
        (testing "a commit naming no skill file needs no check"
          (is (nil? (request "git commit --only -m 'touches src/demo/core.clj' -- src/demo/core.clj")))
          (is (nil? (request (str "git commit -F - -- src/demo/core.clj <<'EOF'\n"
                                  "a message naming .agents/skills/demo/SKILL.md\nEOF")))
              "a heredoc message is no pathspec")
          (is (nil? (request "git status"))))
        (testing "the commit's own skill files, named or staged, are the documents"
          (is (= [".agents/skills/demo/SKILL.md"]
                 (::citations/documents
                  (request "cd x && git commit --only -m \"a b\" -- .agents/skills/demo/SKILL.md"))))
          (git! root "add" ".agents/skills/demo/SKILL.md")
          (is (= [".agents/skills/demo/SKILL.md"]
                 (::citations/documents (request "git commit --only -m m -- $paths")))
              "a pathspec only the shell expands checks every changed skill file")
          (is (= [".agents/skills/demo/SKILL.md"]
                 (::citations/documents (request "git commit -q -m staged")))))
        (let [commit "git commit --only -m m -- .agents/skills/demo/SKILL.md"]
          (is (nil? (citations/commit-refusal (request commit))))
          (write! root ".agents/skills/demo/SKILL.md"
                  "`demo.core/target` (`src/demo/core.clj:5`) doubles.\n")
          (is (str/includes? (citations/commit-refusal (request commit))
                             ".agents/skills/demo/SKILL.md:1 `src/demo/core.clj:5` (demo.core/target) drifted: now at 7")))))))
