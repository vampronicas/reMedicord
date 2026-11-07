# Installing reMedicord (sideload)

reMedicord isn't on Google Play. You install the APK file yourself. Requires **Android 12 or newer**.

## 1. Copy the APK to your phone
Use a USB cable, or any way you trust, to copy `reMedicord-<version>.apk` to the phone's **Downloads** folder.

## 2. Install
1. Open the **Files** app on the phone → Downloads → tap the APK.
2. Android may say your file manager isn't allowed to install apps. Tap **Settings** → turn on **Allow from this source** → go back.
3. Tap **Install**. Google Play Protect may warn that the app is unknown, because it isn't from the Play Store. Choose **Install anyway** only if you built or received the APK from a source you trust.
4. Optional: turn **Allow from this source** off again afterwards.

## 3. First launch
1. **Create a 4-digit PIN.** There is **no way to recover a forgotten PIN.** The only way back in is *Settings → Apps → reMedicord → Storage → Clear storage*, which erases all data, and then restoring a backup.
2. **Reminder setup.** Allow each item. The screen explains why each one is needed:
   - Notifications
   - Exact alarms
   - Full-screen alarm
   - Battery: Unrestricted (stops the phone from shutting down reminders to save power)
3. Add your first medicine with **Medicines → + Add medicine**.

Check **Settings → Reminder health check** any time. Every item should show ✅.

## Updating
Install the newer APK over the old one. Your data is kept **only if both APKs are signed with the same key**. If Android refuses the update ("package conflicts"), **don't uninstall**, because uninstalling deletes all data. First export a backup (Settings → Backup & restore), then reinstall and restore.

## Backups
**Settings → Backup & restore → Export backup** saves a `.zip` to a folder you choose. The file is **not encrypted**, so keep it somewhere safe. Restoring replaces all current data. A snapshot of the current data is saved first, so a restore can be undone.
