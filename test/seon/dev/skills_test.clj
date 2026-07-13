(ns seon.dev.skills-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.skills :as skills]))

(deftest sync-generates-runtime-and-development-adapters
  (let [root (fs/create-temp-dir {:prefix "seon-skills-test-"})
        canonical (fs/path root "seon-skills/datahike")
        agents (fs/path root ".agents/skills/datahike")
        claude (fs/path root ".claude/skills/datahike")
        codex-only (fs/path root ".agents/skills/browser-automation/SKILL.md")
        claude-adapter (fs/path root ".claude/skills/browser-automation/SKILL.md")
        claude-only (fs/path root ".claude/skills/claude-only/SKILL.md")]
    (try
      (doseq [path [canonical agents claude (fs/parent codex-only)
                    (fs/parent claude-adapter) (fs/parent claude-only)]]
        (fs/create-dirs path))
      (spit (str (fs/path canonical "SKILL.md")) "canonical\n")
      (fs/create-dirs (fs/path canonical "references"))
      (spit (str (fs/path canonical "references/querying.md")) "query\n")
      (spit (str (fs/path agents "SKILL.md")) "stale\n")
      (spit (str (fs/path claude "SKILL.md")) "different\n")
      (spit (str codex-only) "operator-current\n")
      (spit (str claude-adapter) "operator-stale\n")
      (spit (str claude-only) "claude-only\n")

      (is (= 3 (count (skills/adapter-drift root))))
      (skills/sync! root)
      (is (= [] (skills/adapter-drift root)))
      (is (= "canonical\n" (slurp (str (fs/path agents "SKILL.md")))))
      (is (= "query\n" (slurp (str (fs/path claude "references/querying.md")))))
      (is (= "operator-current\n" (slurp (str claude-adapter)))
          "Codex's operator-only skill generates the Claude adapter")
      (is (= "claude-only\n" (slurp (str claude-only)))
          "generation does not delete Claude-only skills")
      (finally (fs/delete-tree root {:force true})))))

(deftest checked-in-adapters-match-the-canonical-corpus
  (let [root (str (fs/normalize (fs/absolutize ".")))]
    (testing "every shipped runtime skill has exact Codex and Claude adapters"
      (is (= {:seon.dev.skills/clean? true} (skills/check! root))))))
