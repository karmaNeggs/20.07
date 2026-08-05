#!/bin/bash
# Captures every log tag this app writes to, from one connected phone, into one timestamped file.
# Usage:
#   ./capture_debug_log.sh              # first/only connected device
#   ./capture_debug_log.sh <serial>      # a specific device, if more than one is connected
#   ./capture_debug_log.sh <serial> sender   # optional label, appended to the filename
#
# Run this, then use the app for the test scenario, then Ctrl+C to stop. The output file will be
# sitting in this same directory when you're done — send that file back.

set -euo pipefail

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
if [ ! -x "$ADB" ]; then
  ADB="adb" # fall back to PATH if the SDK isn't at the default location
fi

SERIAL="${1:-}"
LABEL="${2:-}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTFILE="debug_log_${TIMESTAMP}${LABEL:+_$LABEL}.txt"

TAGS="BeaconRadio MeshGattClient MeshGattServer RelayResponder RelayEngine WifiDirectAccelerator"

echo "Writing to: $OUTFILE"
echo "Capturing tags: $TAGS"
echo "Reproduce the test scenario now. Press Ctrl+C to stop."
echo

if [ -n "$SERIAL" ]; then
  "$ADB" -s "$SERIAL" logcat -v threadtime -s $TAGS > "$OUTFILE"
else
  "$ADB" logcat -v threadtime -s $TAGS > "$OUTFILE"
fi
