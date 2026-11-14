#!/bin/bash
# Flames and Steel (Mac): double-click to start. Runs run-flames-and-steel.sh from the same folder.
cd "$(dirname "$0")" || exit 1
exec /bin/bash "./run-flames-and-steel.sh"
