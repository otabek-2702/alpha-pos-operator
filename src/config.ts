/**
 * App-level configuration you can edit without touching feature code.
 *
 * Replace the SUPPORT values with your real contacts, and set
 * UPDATE_MANIFEST_URL once you host the version file for full-APK (sideload)
 * updates. Leave UPDATE_MANIFEST_URL empty to disable the APK-update check
 * (OTA updates via EAS Update still work).
 */

export const SUPPORT = {
  // Shown on the Support screen. Tapping opens the dialer / Telegram / mail.
  phone: '+998 90 000 00 00',
  telegram: 'alphapos_support', // without the @
  email: 'support@alphapos.uz',
};

/**
 * URL of a JSON manifest describing the latest sideload APK. Example contents:
 *
 *   {
 *     "versionCode": 2,
 *     "versionName": "1.0.1",
 *     "apkUrl": "https://your-host/operator/operator-1.0.1.apk",
 *     "notes": "Call-waiting fix, new icon",
 *     "mandatory": false
 *   }
 *
 * The app compares `versionCode` to the installed build and offers the update.
 * Host it anywhere static (your server, GitHub Releases, object storage).
 */
export const UPDATE_MANIFEST_URL: string = '';

/** Shape of the remote APK manifest. */
export interface ApkManifest {
  versionCode: number;
  versionName: string;
  apkUrl: string;
  notes?: string;
  mandatory?: boolean;
}
