import { useCallback, useEffect, useState } from 'react';
import { Platform } from 'react-native';
import * as Updates from 'expo-updates';
import * as Application from 'expo-application';
import * as FileSystem from 'expo-file-system';
import * as IntentLauncher from 'expo-intent-launcher';

import { ApkManifest, UPDATE_MANIFEST_URL } from '../config';

/**
 * Two update channels:
 *  - OTA (EAS Update): JS/asset fixes downloaded silently on launch; we surface
 *    a banner + button to apply (reload). No reinstall, no warning.
 *  - APK (self-hosted): for native changes. Checks a version manifest; if a
 *    newer build exists, downloads the APK and launches the system installer
 *    (which shows the normal sideload install screen).
 */
export interface AppUpdates {
  otaPending: boolean;
  otaChecking: boolean;
  checkOta: () => Promise<'pending' | 'none' | 'error'>;
  applyOta: () => Promise<void>;

  apk: ApkManifest | null;
  apkChecking: boolean;
  apkDownloading: boolean;
  apkProgress: number;
  checkApk: () => Promise<void>;
  installApk: () => Promise<void>;

  updateAvailable: boolean;
  currentVersionName: string;
  currentVersionCode: number;
}

export function useAppUpdates(): AppUpdates {
  const { isUpdatePending } = Updates.useUpdates();
  const [otaChecking, setOtaChecking] = useState(false);

  const [apk, setApk] = useState<ApkManifest | null>(null);
  const [apkChecking, setApkChecking] = useState(false);
  const [apkDownloading, setApkDownloading] = useState(false);
  const [apkProgress, setApkProgress] = useState(0);

  const currentVersionName = Application.nativeApplicationVersion ?? '1.0.0';
  const currentVersionCode = Number(Application.nativeBuildVersion ?? '0') || 0;

  const otaPending = Updates.isEnabled && isUpdatePending;

  const checkOta = useCallback(async (): Promise<'pending' | 'none' | 'error'> => {
    if (!Updates.isEnabled) return 'none';
    setOtaChecking(true);
    try {
      const res = await Updates.checkForUpdateAsync();
      if (res.isAvailable) {
        await Updates.fetchUpdateAsync(); // makes isUpdatePending true
        return 'pending';
      }
      return 'none';
    } catch {
      return 'error';
    } finally {
      setOtaChecking(false);
    }
  }, []);

  const applyOta = useCallback(async () => {
    if (!Updates.isEnabled) return;
    try {
      await Updates.reloadAsync();
    } catch {
      // Ignore — reload only fails in dev / if no update is pending.
    }
  }, []);

  const checkApk = useCallback(async () => {
    if (!UPDATE_MANIFEST_URL) return;
    setApkChecking(true);
    try {
      // Cache-bust so we always see the latest manifest.
      const sep = UPDATE_MANIFEST_URL.includes('?') ? '&' : '?';
      const res = await fetch(`${UPDATE_MANIFEST_URL}${sep}t=${Date.now()}`);
      const data = (await res.json()) as ApkManifest;
      if (data && typeof data.versionCode === 'number' && data.versionCode > currentVersionCode) {
        setApk(data);
      } else {
        setApk(null);
      }
    } catch {
      // Network/parse failure — leave apk as-is.
    } finally {
      setApkChecking(false);
    }
  }, [currentVersionCode]);

  const installApk = useCallback(async () => {
    if (!apk?.apkUrl || Platform.OS !== 'android') return;
    setApkDownloading(true);
    setApkProgress(0);
    try {
      const target = `${FileSystem.documentDirectory}operator-update.apk`;
      // Remove any stale download first.
      try {
        await FileSystem.deleteAsync(target, { idempotent: true });
      } catch {
        /* ignore */
      }
      const dl = FileSystem.createDownloadResumable(apk.apkUrl, target, {}, (p) => {
        if (p.totalBytesExpectedToWrite > 0) {
          setApkProgress(p.totalBytesWritten / p.totalBytesExpectedToWrite);
        }
      });
      const result = await dl.downloadAsync();
      if (!result?.uri) return;

      // Hand the file to the system package installer via a content:// URI.
      const contentUri = await FileSystem.getContentUriAsync(result.uri);
      await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
        data: contentUri,
        flags: 1, // FLAG_GRANT_READ_URI_PERMISSION
        type: 'application/vnd.android.package-archive',
      });
    } catch {
      // Download/install failed — keep the banner so the user can retry.
    } finally {
      setApkDownloading(false);
    }
  }, [apk]);

  // Check the APK manifest once on mount.
  useEffect(() => {
    checkApk();
  }, [checkApk]);

  return {
    otaPending,
    otaChecking,
    checkOta,
    applyOta,
    apk,
    apkChecking,
    apkDownloading,
    apkProgress,
    checkApk,
    installApk,
    updateAvailable: !!apk || otaPending,
    currentVersionName,
    currentVersionCode,
  };
}
