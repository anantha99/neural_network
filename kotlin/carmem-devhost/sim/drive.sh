#!/usr/bin/env bash
# Simulate a weekly school run on the AAOS emulator and check that carmem learns it.
#
# Needs: the emulator running with carmem.devhost installed as a priv-app (see ../README.md),
# adb on PATH or ADB set. Takes ~20 minutes: location fixes are rate-limited to one per 30 s.
#
#   ./drive.sh            three Mondays of home -> school -> home, then the fourth Monday's check
#   ./drive.sh check      only the fourth-Monday check
set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB=${ADB:-adb}
FIX_GAP=32 # LocationFeed asks for at most one fix per 30 s
HOME_LAT=12.9716; HOME_LON=77.5946
SCHOOL_LAT=12.9850; SCHOOL_LON=77.5946
STEPS=5 # ~300 m per fix, above the segmenter's 300 m minimum trip

sh() { "$ADB" shell "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

clock() { # clock 2026-11-02 07:30
    local d=$1 t=$2
    sh "date ${d:5:2}${d:8:2}${t:0:2}${t:3:2}${d:0:4}.00" >/dev/null
    log "device clock $(sh date)"
}
ignition() { sh dumpsys car_service inject-vhal-event 0x11400409 0 "$1" >/dev/null; } # 4 = ON, 2 = OFF
cabin_temp() { sh dumpsys car_service inject-vhal-event 0x15600503 1 "$1" >/dev/null; } # area 1 = ROW_1_LEFT
fix() { "$ADB" emu geo fix "$2" "$1" 900 8 "${3:-20}" >/dev/null; } # lat lon [knots]
probe() { sh am broadcast --user current -a carmem.debug.PROBE -n carmem.devhost/.ProbeReceiver --es op "$1" ${2:+--es arg "$2"} >/dev/null; }

drive() { # drive from_lat from_lon to_lat to_lon
    local i lat lon
    fix "$1" "$2" 0; sleep "$FIX_GAP"
    ignition 4; sleep 2
    cabin_temp 21.5
    for ((i = 1; i <= STEPS; i++)); do
        lat=$(awk -v a="$1" -v b="$3" -v i="$i" -v n="$STEPS" 'BEGIN { printf "%.6f", a + (b - a) * i / n }')
        lon=$(awk -v a="$2" -v b="$4" -v i="$i" -v n="$STEPS" 'BEGIN { printf "%.6f", a + (b - a) * i / n }')
        fix "$lat" "$lon"; sleep "$FIX_GAP"
    done
    ignition 2; sleep 3
}

check() {
    clock 2026-11-23 07:25
    fix "$HOME_LAT" "$HOME_LON" 0; sleep "$FIX_GAP"
    sh logcat -c
    ignition 4; sleep 5
    probe recall "school"; probe snapshot; sleep 3
    log "fourth Monday, ignition on at home:"
    sh logcat -d -s carmem-probe:I carmem:D
    ignition 2
}

setup() {
    "$ADB" root >/dev/null; "$ADB" wait-for-device
    sh settings put global auto_time 0
    probe snapshot; sleep 15 # starts carmem.devhost in the driver's user, which starts the service
    ignition 2; sleep 2
}

reset() { # fresh database, and the driver's AAOS user enrolled so trips are not guest trips
    local user
    user=$(sh am get-current-user | tr -d '\r')
    sh am force-stop --user "$user" carmem.devhost
    sh rm -rf "/data/user/$user/carmem.devhost/no_backup/*"
    probe snapshot; sleep 15
    sh "am broadcast --user current -a carmem.debug.PROBE -n carmem.devhost/.ProbeReceiver --es op enroll --es arg alex --es name Alex --es bindings '[{\"kind\":\"seat_profile\",\"value\":\"user:$user\"}]'" >/dev/null
    sleep 2
}

setup
if [[ ${1:-} != check ]]; then
    reset
    for monday in 2026-11-02 2026-11-09 2026-11-16; do
        clock "$monday" 07:30; log "school run $monday"
        drive "$HOME_LAT" "$HOME_LON" "$SCHOOL_LAT" "$SCHOOL_LON"
        clock "$monday" 15:30; log "home $monday"
        drive "$SCHOOL_LAT" "$SCHOOL_LON" "$HOME_LAT" "$HOME_LON"
        sh logcat -d -s carmem:D | grep -c "IgnitionChanged" | xargs -I{} echo "  ignition events so far: {}"
    done
fi
check
