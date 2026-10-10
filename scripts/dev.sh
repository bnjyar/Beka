#!/usr/bin/env bash
# Dev loop helpers. Runs inside proot on the phone; device access goes through Shizuku's rish.
set -euo pipefail

REPO=bnjyar/Beka
PKG=dev.beka
RISH_DIR=/data/data/com.termux/files/home
WORK=${TMPDIR:-/tmp}/beka-dev
mkdir -p "$WORK"

# Calls Shizuku's shell loader directly (the rish wrapper script needs Android's getprop in PATH).
# Without a tty, rish spreads stdout over both fds, so they are merged here.
rish() {
  RISH_APPLICATION_ID=com.termux /system/bin/app_process \
    -Djava.class.path="$RISH_DIR/rish_shizuku.dex" /system/bin --nice-name=rish \
    rikka.shizuku.shell.ShizukuShellLoader -c "$*" 2>&1
}

# rish drops data on large outputs, so binary files are pulled in small base64 chunks,
# each verified by length and retried, then the whole file is checked against md5.
pull() {
  local src=$1 dst=$2 chunk=262144
  local size; size=$(rish "stat -c %s '$src'" | tr -d '\r\n ')
  : > "$dst"
  local i=0
  while (( i * chunk < size )); do
    local want=$(( size - i * chunk < chunk ? size - i * chunk : chunk ))
    local tries=0 part=""
    while :; do
      part=$(rish "dd if='$src' bs=$chunk skip=$i count=1 2>/dev/null | base64 -w0")
      if [[ $(printf %s "$part" | base64 -d 2>/dev/null | wc -c) -eq $want ]]; then break; fi
      (( ++tries < 5 )) || { echo "pull failed at chunk $i" >&2; return 1; }
    done
    printf %s "$part" | base64 -d >> "$dst"
    i=$(( i + 1 ))
  done
  local remote local_sum
  remote=$(rish "md5sum '$src'" | cut -d' ' -f1)
  local_sum=$(md5sum "$dst" | cut -d' ' -f1)
  [[ "$remote" == "$local_sum" ]] || { echo "md5 mismatch for $src" >&2; return 1; }
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
  log)     # recent app logs (pulled as a file; large rish output gets truncated)
    rish "logcat -d -t 1000 --pid=\$(pidof $PKG) > /data/local/tmp/beka-log.txt"
    pull /data/local/tmp/beka-log.txt "$WORK/log.txt" && cat "$WORK/log.txt"
    ;;
  crash)   # last crash buffer
    rish "logcat -d -b crash -t 200"
    ;;
  shot)    # screenshot to $WORK/screen.png
    rish "screencap -p /data/local/tmp/beka-shot.png"
    pull /data/local/tmp/beka-shot.png "$WORK/screen.png" && echo "$WORK/screen.png"
    ;;
  ui)      # dump the current UI hierarchy
    rish "uiautomator dump /data/local/tmp/ui.xml >/dev/null && cat /data/local/tmp/ui.xml"
    ;;
  applog)  # Beka's own recorded log (files/logs, survives logcat rotation); applog [lines] [steps]
    n=${2:-400}
    f=app-log; [[ "${3:-}" == steps ]] && f=steps
    rish "run-as $PKG sh -c 'cat files/logs/$f.2.txt files/logs/$f.1.txt files/logs/$f.txt 2>/dev/null | tail -n $n' > /data/local/tmp/beka-applog.txt"
    pull /data/local/tmp/beka-applog.txt "$WORK/applog.txt" && cat "$WORK/applog.txt"
    ;;
  report)  # newest debug bundle from Download/beka-debug
    dir=$(rish "ls -1d /sdcard/Download/beka-debug/* | tail -1" | tr -d '\r')
    out="$WORK/report/$(basename "$dir")"
    mkdir -p "$out"
    for f in $(rish "ls '$dir'" | tr -d '\r'); do
      pull "$dir/$f" "$out/$f" || pull "$dir/$f" "$out/$f" || echo "skipped $f" >&2
    done
    echo "$out"
    ;;
  locktest) # fase-8 spike: wait for the screen lock (pattern), then probe the virtual display (scripts/locktest.sh)
    base64 -w0 "$(dirname "$0")/locktest.sh" | rish "base64 -d > /data/local/tmp/locktest.sh"
    rish "sh /data/local/tmp/locktest.sh; cat /data/local/tmp/locktest.txt"
    ;;
  vd)      # fase 9: drive the virtual display (connect|open <pkg>|key <code>|main [pkg]|close|state|frame|locktest)
    shift
    rish "logcat -c; am broadcast -a dev.beka.SPIKE_DISPLAY --es op vd --es text '$*' >/dev/null; sleep ${VD_WAIT:-2}; logcat -d -s BekaDisplay:I BekaSpike:I | cut -c19-"
    if [[ "${1:-}" == frame ]]; then pull /sdcard/Android/data/$PKG/files/vd-frame.png "$WORK/vd-frame.png" && echo "$WORK/vd-frame.png"; fi
    ;;
  *)
    echo "usage: $0 {wait|install|a11y|log|applog|crash|shot|ui|report|locktest|vd}"
    ;;
esac
