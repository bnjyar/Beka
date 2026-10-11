# Installing Beka

*[Bahasa Indonesia](PASANG.md)*

This guide is for you if you are installing Beka for the first time. You need an Android 11 or newer phone
and about 10 minutes.

Beka is in English, or in Indonesian when your phone is set to Indonesian (on Android 13+ you can change it in
Settings → Appearance → Language). The menu names below are the English ones.

## 1. Download and install
1. Open the [Releases](https://github.com/bnjyar/Beka/releases/latest) page on your phone and tap `beka.apk`.
2. If the browser asks, allow downloading the file.
3. Open the downloaded file. Android asks to allow "Install unknown apps" for your browser or file manager:
   turn it on, go back and tap **Install**.
4. If Play Protect warns about an unknown app, choose **More details → Install anyway**. Beka is not on the
   Play Store, so this warning is expected.

## 2. First setup
Open Beka. The setup walks you through each step:
1. **Display over other apps**: so Beka's bubble can float above other apps.
2. **Accessibility**: so Beka can read the screen and tap buttons when you ask it to.
   If the switch is greyed out or you see "Restricted setting", open **App info → ⋮ (top right)
   → Allow restricted settings**, then try again. This happens to every app installed outside the Play Store.
3. **Notifications**: you can skip this, but without it Beka cannot tell you about proactive rules.
   "Notification access" can also hit "Restricted setting"; the fix is the same as above.
4. **Keep running in the background**: let Beka run without battery restrictions, so the phone does not stop
   the bubble and rules. Xiaomi, Oppo, Vivo and Realme phones get an **Open phone settings** button for extra
   permissions (see section 4).
5. **Microphone**: if you want to talk to Beka. You can skip this.

## 3. Choose an AI model
Beka needs an AI model to answer. Beka has no server of its own; you use your own account.
- **Easiest: log in to OpenRouter.** Tap **Log in to OpenRouter**, sign in or sign up, then allow access. A new
  account needs credit first: top up at [openrouter.ai/settings/credits](https://openrouter.ai/settings/credits).
  Without credit Beka says "Your provider balance is used up".
- **Your own API key** (OpenAI, Anthropic or another provider): Settings → AI model → **Add provider**,
  paste the key, then **Save**.

Keys are stored encrypted on the phone and are only sent to the provider you chose.

## 4. Xiaomi, Oppo, Vivo, Realme and Samsung phones
These brands tend to stop apps in the background. The "Keep running in the background" setup step (also in
Settings → Permissions) takes care of the battery. If Beka's bubble still disappears often, or Beka cannot open apps:
- **Battery**: Beka's App info → Battery → choose **Unrestricted** (or "No restrictions").
- **Autostart** (Xiaomi, Oppo, Vivo, Realme): Beka's App info → turn on **Autostart / Auto launch**.
- **Xiaomi**: Beka's App info → Other permissions → turn on **Display pop-up windows while running in the
  background**. Without it Beka cannot open other apps.
- **Samsung**: Settings → Device care → Battery → Background usage limits → make sure Beka is not under
  "Sleeping apps".

Menu names can differ a little between phone versions.

## 5. Start using it
Tap **Start Beka**, then tap the bubble to open the panel. Try asking "what is on this screen?" in any app.
Beka asks first before anything risky (sending, paying, deleting, calling), and never touches banking,
wallet or password manager apps.

## Updates
Beka checks for new versions by itself. Install them from **Settings → Updates**. Your data stays.

## Backup
Before you change phones or reinstall, open **Settings → Data → Export**. Keep the file, then use **Import** on
the new phone. Memories, rules, app permissions and settings come along; API keys do not, so log in or paste
them again. Uninstalling Beka deletes all of its data.

## Something wrong?
In Beka's panel: **⋮ → Report**. Describe what happened; Beka saves a report and opens a GitHub page to send
it. Or tell the person who gave you Beka.
