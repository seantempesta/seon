#!/bin/zsh
# run-mode.sh CLUSTER — the 9-drive matrix for one mode, strictly serial.
set -e
cd /Users/sean/src/seon
CL=$1
T=evals/runs/2026-07-10-minimal-buildup/tools/min-drive.sh
for n in 1 2 3; do zsh $T $CL poker poker $n; done
for n in 1 2 3; do zsh $T $CL two-bucket two-bucket $n; done
for n in 1 2 3; do zsh $T $CL - db-memory $n; done
echo "MODE $CL MATRIX COMPLETE"
