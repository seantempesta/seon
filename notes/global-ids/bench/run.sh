#!/bin/zsh
ROOT=/Users/sean/src/seon-jolt/next-global-ids
. $ROOT/bin/env
cd $ROOT
HOME=$ROOT/target/home JOLT_PWD=$ROOT nice -n 15 $ROOT/target/jolt/jolt -A:test run "$@"
