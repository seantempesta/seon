#!/bin/zsh
# notes/ctx-lab/run.sh AGENT FILE...: load each lab file into AGENT of the ctx-lab root (port 8230), one eval each
D=${0:A:h}; A=$1; shift
for f in "$@"; do printf '{:op :eval :agent "%s" :code "(load-file \\"%s/%s\\")"}\n' "$A" "$D" "$f" | "$D/../../bin/ask" 8230; done
