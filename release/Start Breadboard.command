#!/bin/sh
# macOS: double-click to start Breadboard. The console opens in your browser once it is up; press
# Ctrl+C in this window, or close it, to stop Breadboard. If it stops by itself (it ran out of
# memory, say), it is started again after a few seconds. (The first time, macOS may say it can't
# check the developer: then right-click this file and choose Open, or allow it in System Settings →
# Privacy & Security.)
cd "$(dirname "$0")" || exit 1
BREADBOARD_KEEP_RUNNING="${BREADBOARD_KEEP_RUNNING:-1}"
export BREADBOARD_KEEP_RUNNING
exec ./breadboard.sh "$@"
