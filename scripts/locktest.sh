#!/system/bin/sh
# Fase-8 spike: waits for the keyguard, then probes virtual display 11 while the phone is locked.
# Each round: A = as the phone left it (asleep), B = after a WAKEUP key sent to display 11 only.
OUT=/data/local/tmp/locktest.txt
VD=11
: > $OUT
while ! dumpsys window policy | grep -q 'mIsShowing=true'; do sleep 1; done
echo "LOCKED at $(date +%T)" >> $OUT

state() {
  dumpsys power | grep -A1 'Power Group powerGroupId' | grep -oE 'powerGroupId: [0-9]+|wakefulness: [0-9]+' | paste -sd' ' >> $OUT
  dumpsys window policy | grep -oE 'mIsShowing=[a-z]+' | head -1 >> $OUT
  echo "vd $(dumpsys display | grep -oE '"beka-vd".*' | grep -oE ' state [A-Z_]+' | head -1)" >> $OUT
  dumpsys activity activities | grep -A6 "Display #$VD" | grep -oE 'isSleeping=[a-z]+|visible=[a-z]+' | paste -sd' ' >> $OUT
}

probe() { # $1 = tag, $2 = text to click
  log -t BekaSpike "MARK $1"
  am broadcast -a dev.beka.SPIKE_DISPLAY --ei display $VD --es op dump >/dev/null
  sleep 1
  am broadcast -a dev.beka.SPIKE_DISPLAY --ei display $VD --es op click --es text "$2" >/dev/null
  sleep 3
  am broadcast -a dev.beka.SPIKE_DISPLAY --ei display $VD --es op dump >/dev/null
  am broadcast -a dev.beka.SPIKE_DISPLAY --ei display $VD --es op shot >/dev/null
  touch /data/local/tmp/vd.req
  sleep 2
  cp /sdcard/Android/data/dev.beka/files/vd-a11y-$VD.png /data/local/tmp/lock-a11y-$1.png
  cp /data/local/tmp/vd.png /data/local/tmp/lock-host-$1.png
  logcat -d -s BekaSpike | sed -n "/MARK $1/,\$p" | grep -E 'window type|action on|shot|tree|displays' | cut -c1-200 >> $OUT
}

round() { # $1 = round number
  am start --display $VD -a android.intent.action.VIEW -d https://example.com -p com.android.chrome >/dev/null 2>&1
  sleep 2
  echo "=== round $1 A (as is) at $(date +%T)" >> $OUT
  state
  probe "$1A" "Learn more"
  echo "=== round $1 B (WAKEUP to display $VD) at $(date +%T)" >> $OUT
  input -d $VD keyevent KEYCODE_WAKEUP
  sleep 2
  state
  am start --display $VD -a android.intent.action.VIEW -d https://example.com -p com.android.chrome >/dev/null 2>&1
  sleep 2
  probe "$1B" "Learn more"
  state
}

sleep 20
round 1
sleep 45
round 2
echo "DONE $(date +%T)" >> $OUT
