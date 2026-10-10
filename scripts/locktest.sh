#!/system/bin/sh
# Fase-8 spike: waits for the keyguard, then operates WhatsApp on the virtual display while the phone is locked.
# Each round: A = as the phone left it (asleep), B = after waking only the virtual display's power group
# (IPowerManager.wakeUpWithDisplayId through DisplayHost), then a test message to the user's own chat.
# Only matching log lines are kept, so chat contents stay out of the output.
OUT=/data/local/tmp/locktest.txt
VD=$(grep -oE 'DISPLAY_ID=[0-9]+' /data/local/tmp/vd.log | tail -1 | cut -d= -f2)
B="am broadcast -a dev.beka.SPIKE_DISPLAY --ei display $VD"
: > $OUT
while ! dumpsys window policy | grep -q 'mIsShowing=true'; do sleep 1; done
echo "LOCKED at $(date +%T), virtual display $VD" >> $OUT

state() {
  dumpsys power | grep -A1 'Power Group powerGroupId' | grep -oE 'powerGroupId: [0-9]+|wakefulness: [0-9]+' | paste -sd' ' >> $OUT
  dumpsys window policy | grep -oE 'mIsShowing=[a-z]+' | head -1 >> $OUT
  echo "vd $(dumpsys display | grep -oE '"beka-vd".*' | grep -oE ' state [A-Z_]+' | head -1)" >> $OUT
  dumpsys activity activities | grep -A6 "Display #$VD" | grep -oE 'isSleeping=[a-z]+|visible=[a-z]+' | paste -sd' ' >> $OUT
}

round() { # $1 = round number
  echo "=== round $1 A (as is) at $(date +%T)" >> $OUT
  state
  echo "=== round $1 B (wakeUpWithDisplayId $VD) at $(date +%T)" >> $OUT
  echo "ipm wakeUpWithDisplayId now 0 beka com.android.shell vd" > /data/local/tmp/vd.cmd
  sleep 2
  state
  $B --es op type --es text "Tes Beka fase 8: dikirim saat HP terkunci, ronde $1 $(date +%T)" >/dev/null
  sleep 1
  $B --es op click --es text "Send" >/dev/null
  sleep 2
  $B --es op dump >/dev/null
  $B --es op shot >/dev/null
  sleep 1
  logcat -d -s BekaSpike:I | grep -E "action on|window type|shot display|Tes Beka" | tail -8 | cut -c33-220 >> $OUT
  cp /sdcard/Android/data/dev.beka/files/vd-a11y-$VD.png /data/local/tmp/lock-wa-$1.png
  state
}

sleep 20
round 1
sleep 60
round 2
echo "DONE $(date +%T)" >> $OUT
