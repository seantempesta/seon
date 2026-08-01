#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
frozen_tree="$repo_root/tmp/jvm-tuning/frozen-tree"

if [[ -e "$frozen_tree" ]]; then
  echo "refusing to replace existing frozen tree: $frozen_tree" >&2
  exit 2
fi

mkdir -p "$frozen_tree"
git -C "$repo_root" archive HEAD | tar -x -C "$frozen_tree"
git -C "$repo_root" rev-parse HEAD > "$frozen_tree/.jvm-tuning-head"
printf '%s\n' "$frozen_tree"
