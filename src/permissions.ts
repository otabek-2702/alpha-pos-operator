import { Linking, PermissionsAndroid, Platform } from 'react-native';

/** Runtime grants are checked from Android, never inferred from a previous prompt. */
export interface PermissionState {
  camera: boolean;
  phone: boolean;
  callLog: boolean;
  notifications: boolean;
}

/** Camera and notifications are requested too, but do not block call delivery. */
export function hasRequiredPermissions(state: PermissionState): boolean {
  return state.phone && state.callLog;
}

const CAMERA = PermissionsAndroid.PERMISSIONS.CAMERA!;
const READ_PHONE_STATE = PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE!;
const READ_CALL_LOG = PermissionsAndroid.PERMISSIONS.READ_CALL_LOG!;
const POST_NOTIFICATIONS = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS!;
const ALL_GRANTED: PermissionState = { camera: true, phone: true, callLog: true, notifications: true };

export async function checkPermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') return ALL_GRANTED;
  const [camera, phone, callLog, notifications] = await Promise.all([
    PermissionsAndroid.check(CAMERA),
    PermissionsAndroid.check(READ_PHONE_STATE),
    PermissionsAndroid.check(READ_CALL_LOG),
    Number(Platform.Version) >= 33 ? PermissionsAndroid.check(POST_NOTIFICATIONS) : Promise.resolve(true),
  ]);
  return { camera, phone, callLog, notifications };
}

/** Use a single Android request to avoid competing permission dialogs. */
export async function requestAllPermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') return ALL_GRANTED;
  const permissions = [CAMERA, READ_PHONE_STATE, READ_CALL_LOG];
  if (Number(Platform.Version) <= 29) permissions.push(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE!);
  if (Number(Platform.Version) >= 33 && POST_NOTIFICATIONS) permissions.push(POST_NOTIFICATIONS);
  try { await PermissionsAndroid.requestMultiple(permissions); }
  catch { /* Always return the real grants, including after a cancelled dialog. */ }
  return checkPermissions();
}

export async function openAppSettings(): Promise<void> {
  await Linking.openSettings();
}
