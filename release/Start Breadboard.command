#!/bin/sh
# macOS: double-click to start Breadboard. The console opens in your browser once it is up; press
# Ctrl+C in this window, or close it, to stop Breadboard. If it stops by itself (it ran out of
# memory, say), it is started again after a few seconds.
#
# macOS doesn't open this file from a download the first time: it can't check who made it. On macOS
# 15 and later, Control-click → Open no longer helps; System Settings → Privacy & Security → Open
# Anyway does. Or, always: open Terminal, type sh and a space, drag breadboard.sh from this folder
# into the window, and press Return.
cd "$(dirname "$0")" || exit 1
BREADBOARD_KEEP_RUNNING="${BREADBOARD_KEEP_RUNNING:-1}"
export BREADBOARD_KEEP_RUNNING
# (through sh, as in Terminal: it doesn't need breadboard.sh to be marked executable)
exec sh ./breadboard.sh "$@"
