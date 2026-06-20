import { Linking, PermissionsAndroid, Platform } from 'react-native';
import * as IntentLauncher from 'expo-intent-launcher';
import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * Centralized Android runtime-permission handling.
 *
 * Why this exists: previously the camera permission (via expo-camera) and the
 * phone/call-log permissions (via the call bridge) were requested from two
 * different components at the same time. Android only shows one permission
 * dialog at a time, so the second request was silently dropped — which is why
 * "only the camera was ever asked". Requesting them all from one place, in one
 * sequence, fixes that.
 *
 * Call-log access is mandatory on Android 9+ to read the caller number; without
 * it the number arrives empty.
 */

const BATTERY_PROMPTED_KEY = 'alphapos.operatorlink.batteryPrompted';
const ANDROID_PACKAGE = 'com.alphapos.operatorlink';

export interface PermissionState {
  camera: boolean;
  phone: boolean; // READ_PHONE_STATE
  callLog: boolean; // READ_CALL_LOG
  notifications: boolean;
}

/** The permissions the app cannot function without. */
export function hasRequiredPermissions(state: PermissionState): boolean {
  return state.camera && state.phone && state.callLog;
}

const GRANTED = PermissionsAndroid.RESULTS.GRANTED;

// The RN typings mark these optional; on Android they are always defined.
const CAMERA = PermissionsAndroid.PERMISSIONS.CAMERA!;
const READ_PHONE_STATE = PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE!;
const READ_CALL_LOG = PermissionsAndroid.PERMISSIONS.READ_CALL_LOG!;
const POST_NOTIFICATIONS = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS!;

const ALL_GRANTED: PermissionState = {
  camera: true,
  phone: true,
  callLog: true,
  notifications: true,
};

/** Reads current permission grants without showing any dialog. */
export async function checkPermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') return ALL_GRANTED;
  const notificationsApplicable = Number(Platform.Version) >= 33;
  const [camera, phone, callLog, notifications] = await Promise.all([
    PermissionsAndroid.check(CAMERA),
    PermissionsAndroid.check(READ_PHONE_STATE),
    PermissionsAndroid.check(READ_CALL_LOG),
    notificationsApplicable
      ? PermissionsAndroid.check(POST_NOTIFICATIONS)
      : Promise.resolve(true),
  ]);
  return { camera, phone, callLog, notifications };
}

/**
 * Requests every runtime permission in a single sequential flow. Android shows
 * the dialogs one after another, so none get dropped. Returns the resulting
 * grant state.
 */
export async function requestAllPermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') return ALL_GRANTED;

  const toRequest = [CAMERA, READ_PHONE_STATE, READ_CALL_LOG];
  if (Number(Platform.Version) >= 33 && POST_NOTIFICATIONS) {
    toRequest.push(POST_NOTIFICATIONS);
  }

  let result: Record<string, string> = {};
  try {
    result = await PermissionsAndroid.requestMultiple(toRequest);
  } catch {
    // Fall back to whatever check() reports.
    return checkPermissions();
  }

  const notificationsApplicable = Number(Platform.Version) >= 33;
  return {
    camera: result[CAMERA] === GRANTED,
    phone: result[READ_PHONE_STATE] === GRANTED,
    callLog: result[READ_CALL_LOG] === GRANTED,
    notifications: notificationsApplicable
      ? result[POST_NOTIFICATIONS] === GRANTED
      : true,
  };
}

/** Opens this app's system settings page (for permissions denied "forever"). */
export async function openAppSettings(): Promise<void> {
  try {
    await Linking.openSettings();
  } catch {
    // Ignore — nothing else we can do from JS.
  }
}

/** True once the user has been shown the battery-optimization request before. */
export async function wasBatteryPrompted(): Promise<boolean> {
  try {
    return (await AsyncStorage.getItem(BATTERY_PROMPTED_KEY)) === '1';
  } catch {
    return false;
  }
}

/**
 * Asks the OS to exempt the app from battery optimization, so the foreground
 * service (and therefore call detection) is not killed in the background.
 *
 * Android has no JS API to read the current exemption state, so we just fire
 * the request and remember that we asked.
 */
export async function requestBatteryExemption(): Promise<void> {
  if (Platform.OS !== 'android') return;
  try {
    await IntentLauncher.startActivityAsync(
      'android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
      { data: `package:${ANDROID_PACKAGE}` }
    );
  } catch {
    // Some OEMs block this action; fall back to the general battery settings.
    try {
      await IntentLauncher.startActivityAsync(
        'android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS'
      );
    } catch {
      // Give up silently.
    }
  } finally {
    try {
      await AsyncStorage.setItem(BATTERY_PROMPTED_KEY, '1');
    } catch {
      // Non-fatal.
    }
  }
}
