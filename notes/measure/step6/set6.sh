#!/bin/zsh
# set6.sh WORKTREE PORT OUT TASKS-EDN [--set ...]: one measured round: a fresh store of this
# worktree, the tasks at cap 30 (thinking off), waiting on the runs' end, then run-stats (and on
# the new side prose-stats and similar-stats) written to OUT (.prose, .similar beside it). The
# root is left running for the debug pages when KEEP=1.
W=$1; P=$2; OUT=$3; TASKS=$4; shift 4
S=/Users/sean/src/seon-next/.worktrees/agent-conversation/notes/step0
cd $W || exit 1
bin/root stop >/dev/null 2>&1; rm -rf $W/runs/store $W/runs/store-agents
SEON_PROFILE=deepseek bin/root start --set ':seon.config.agent/max-turns=30' "$@" >/dev/null 2>&1
ev() { code=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'); printf '{:op :eval :agent "root" :code "%s"}\n' "$code" | nc -w ${2:-60} 127.0.0.1 $P; }
ev "(root/load-tasks! sys) nil" 300 >/dev/null
# main's vectors first: a run's branch forks main, and :similar reads its vectors there
until ev "(count (seon.vector/due @(:conn sys) (seon.vector/model-id (seon.config/value :seon.config.vector/model))))" | grep -qE ':value 0[,} ]|:printed "0"'; do sleep 10; done
ev "(root/run! sys (clojure.edn/read-string (slurp \"$TASKS\")))" >/dev/null
sleep 60
ev "(load-file \"$S/stats.clj\") nil" >/dev/null
until ev "(count (filter #{:running} (map :status (run-stats sys))))" | grep -qE ':value 0[,} ]|:printed "0"'; do sleep 20; done
ev "(spit \"$OUT\" (pr-str (run-stats sys))) nil" 120 >/dev/null
if [[ $W == */agent-conversation ]]; then
  ev "(load-file \"$S/prose.clj\") (load-file \"$S/similar.clj\") (spit \"$OUT.prose\" (pr-str (prose-stats sys))) (spit \"$OUT.similar\" (pr-str (similar-stats sys))) nil" 300 >/dev/null
fi
[[ -z $KEEP ]] && bin/root stop >/dev/null 2>&1
echo "round done $OUT"
