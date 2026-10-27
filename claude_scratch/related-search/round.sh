#!/bin/zsh
# round.sh OUT [--set ...]: one measured round (step 6's set6.sh): a fresh store of this worktree,
# the five tasks at cap 30 on deepseek (thinking off), waiting on the runs' end, run-stats to OUT.
W=/Users/sean/src/seon-next/.worktrees/a-text-written-on-a-branch-is-embedded-there; P=8260
S=/private/tmp/claude-501/-Users-sean-src-seon-next/89e4bd39-d696-48b6-9c09-34666b1c7927/scratchpad/related-ab
OUT=$1; shift
cd $W || exit 1
bin/root stop >/dev/null 2>&1; rm -rf $W/runs/store $W/runs/store-agents
SEON_PROFILE=deepseek bin/root start --set ':seon.config.agent/max-turns=30' "$@" >/dev/null 2>&1
ev() { code=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'); printf '{:op :eval :agent "root" :code "%s"}\n' "$code" | nc -w ${2:-60} 127.0.0.1 $P; }
ev "(root/load-tasks! sys) nil" 300 >/dev/null
until ev "(count (seon.vector/due @(:conn sys) (seon.vector/model-id (seon.config/value :seon.config.vector/model))))" | grep -qE ':value 0[,} ]|:printed "0"'; do sleep 10; done
ev "(root/run! sys (clojure.edn/read-string (slurp \"$S/tasks.edn\")))" >/dev/null
sleep 60
ev "(load-file \"$S/stats.clj\") nil" >/dev/null
until ev "(count (filter #{:running} (map :status (run-stats sys))))" | grep -qE ':value 0[,} ]|:printed "0"'; do sleep 20; done
ev "(spit \"$OUT\" (pr-str (run-stats sys))) nil" 120 >/dev/null
bin/root stop >/dev/null 2>&1
echo "round done $OUT $(date +%T)"
