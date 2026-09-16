import { useCallback, useState } from 'react';
import * as Application from 'expo-application';

import { checkForUpdate } from '../operator';
import type { UpdateStatus } from '../operator';

/**
 * Self-updates run natively: the background service checks the public GitHub
 * releases every 30 minutes, downloads and verifies a newer APK, and installs
 * it between calls. The UI only shows that state and can ask for a check now.
 */
export interface AppUpdates {
  status: UpdateStatus | null;
  checking: boolean;
  checkNow: () => Promise<void>;
  /** A newer version is known and not installed yet. */
  updateAvailable: boolean;
  currentVersionName: string;
  currentVersionCode: number;
}

export function useAppUpdates(status: UpdateStatus | null): AppUpdates {
  const [requested, setRequested] = useState(false);

  const checkNow = useCallback(async () => {
    setRequested(true);
    try {
      await checkForUpdate();
      // The check runs on the service thread; the 2-second status poll picks up its progress.
      await new Promise((resolve) => setTimeout(resolve, 2500));
    } catch {
      // The native status carries the error message.
    } finally {
      setRequested(false);
    }
  }, []);

  return {
    status,
    checking: requested || status?.state === 'checking',
    checkNow,
    updateAvailable: !!status && status.latestCode > status.installedCode,
    currentVersionName: status?.installedName || Application.nativeApplicationVersion || '',
    currentVersionCode: status?.installedCode || Number(Application.nativeBuildVersion ?? '0') || 0,
  };
}

export function describeUpdate(status: UpdateStatus): string {
  switch (status.state) {
    case 'checking': return 'Yangilanish tekshirilmoqda…';
    case 'downloading': return `Yuklab olinmoqda… ${status.size > 0 ? Math.round(Math.min(1, status.downloaded / status.size) * 100) : 0}%`;
    case 'ready': return 'Qo‘ng‘iroq bo‘lmagan paytda avtomatik o‘rnatiladi';
    case 'installing': return 'O‘rnatilmoqda… ilova bir necha soniyaga qayta ishga tushadi';
    case 'waiting_user': return status.error || 'O‘rnatishni tasdiqlang';
    case 'error': return status.error || 'Yangilanish o‘rnatilmadi';
    default: return 'Yangi versiya topildi';
  }
}
