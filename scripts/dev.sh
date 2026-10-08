#!/usr/bin/env bash
# Dev loop helpers. Runs inside proot on the phone; device access goes through Shizuku's rish.
set -euo pipefail

REPO=bnjyar/Beka
PKG=dev.beka
RISH_DIR=/data/data/com.termux/files/home
WORK=${TMPDIR:-/tmp}/beka-dev
mkdir -p "$WORK"

rish() {
  RISH_APPLICATION_ID=com.termux sh "$RISH_DIR/rish" -c "$*"
}

case "${1:-help}" in
  wait)    # wait for the latest CI run on main to finish
    gh run watch -R "$REPO" "$(gh run list -R "$REPO" -b main -L1 --json databaseId -q '.[0].databaseId')" --exit-status
    ;;
  install) # download the nightly APK and install it over the current one
    rm -f "$WORK/beka.apk"
    gh release download nightly -R "$REPO" -p beka.apk -D "$WORK"
    size=$(stat -c %s "$WORK/beka.apk")
    cat "$WORK/beka.apk" | rish "pm install -r -S $size"
    rish "dumpsys package $PKG | grep versionName"
    ;;
  a11y)    # re-enable the accessibility service (updates can switch it off)
    svc="$PKG/$PKG.accessibility.BekaAccessibilityService"
    cur=$(rish "settings get secure enabled_accessibility_services")
    case "$cur" in *"$svc"*) echo "already enabled" ;; null|"") rish "settings put secure enabled_accessibility_services $svc" ;; *) rish "settings put secure enabled_accessibility_services $cur:$svc" ;; esac
    rish "settings put secure accessibility_enabled 1"
    ;;
  log)     # recent app logs
    rish "logcat -d -t 500 --pid=\$(pidof $PKG)"
    ;;
  crash)   # last crash buffer
    rish "logcat -d -b crash -t 200"
    ;;
  shot)    # screenshot to $WORK/screen.png
    rish "screencap -p" > "$WORK/screen.png" && echo "$WORK/screen.png"
    ;;
  ui)      # dump the current UI hierarchy
    rish "uiautomator dump /data/local/tmp/ui.xml >/dev/null && cat /data/local/tmp/ui.xml"
    ;;
  report)  # newest debug bundle from Download/beka-debug
    dir=$(rish "ls -1d /sdcard/Download/beka-debug/* | tail -1")
    for f in note.txt steps.jsonl screen.txt; do echo "=== $f"; rish "cat $dir/$f" || true; done
    ;;
  *)
    echo "usage: $0 {wait|install|a11y|log|crash|shot|ui|report}"
    ;;
esac
