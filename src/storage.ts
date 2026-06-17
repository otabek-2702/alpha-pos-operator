import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Persistence for the paired desktop POS WebSocket URL.
 *
 * Only one desktop is paired at a time. The stored value is the full URL
 * encoded in the pairing QR, e.g. `ws://192.168.1.42:8765?token=abc123`.
 * It is stored verbatim and reused on launch so the app auto-reconnects
 * without re-scanning.
 */

const DESKTOP_URL_KEY = 'alphapos.operatorlink.desktopUrl';

/** Returns the saved desktop URL, or `null` if the device has never paired. */
export async function loadDesktopUrl(): Promise<string | null> {
  try {
    return await AsyncStorage.getItem(DESKTOP_URL_KEY);
  } catch {
    // A storage read failure should not crash startup — treat as "not paired".
    return null;
  }
}

/** Persists the desktop URL exactly as scanned. */
export async function saveDesktopUrl(url: string): Promise<void> {
  try {
    await AsyncStorage.setItem(DESKTOP_URL_KEY, url);
  } catch {
    // Non-fatal: the in-memory URL still drives this session.
  }
}

/** Clears the saved pairing so the app returns to the QR scanner. */
export async function clearDesktopUrl(): Promise<void> {
  try {
    await AsyncStorage.removeItem(DESKTOP_URL_KEY);
  } catch {
    // Non-fatal.
  }
}
