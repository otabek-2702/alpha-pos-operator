import { useEffect, useRef, useState } from 'react';
import { Platform } from 'react-native';
import CallDetectorManager, { type CallEvent } from 'react-native-call-detection';

import type { OutboundMessage } from './useWebSocket';

/**
 * Bridges native phone-call events to the wire protocol.
 *
 * Android reports states without an explicit direction, so direction is
 * inferred by tracking the event sequence:
 *
 *   - "Incoming" (with number)   -> ring; emit call_start direction "in".
 *   - "Offhook"  (with number)   -> if preceded by "Incoming" it is that call
 *                                   being answered (emit nothing); otherwise it
 *                                   is an OUTGOING call -> emit call_start "out".
 *   - "Disconnected" / "Missed"  -> emit call_end and reset tracking flags.
 *
 * `readPhoneNumberAndroid` is enabled so the OS supplies the number. On
 * Android 9+ this requires BOTH READ_PHONE_STATE and READ_CALL_LOG — without
 * READ_CALL_LOG the number arrives empty.
 */

export interface CallLogEntry {
  id: string;
  at: number;
  type: OutboundMessage['type'];
  phone: string;
  direction?: 'in' | 'out';
}

/** Keep only the most recent N events for the live status log. */
const MAX_LOG = 10;

/** A call currently in progress, used to drive the live "active call" banner. */
export interface ActiveCall {
  phone: string;
  direction: 'in' | 'out';
  startedAt: number;
}

export interface UseCallBridgeResult {
  /** Last ~10 events emitted to the desktop, newest first. */
  log: CallLogEntry[];
  /** The call currently in progress, or null. */
  activeCall: ActiveCall | null;
}

/**
 * @param send     outbound websocket sender
 * @param enabled  true once the required call permissions are granted. The
 *                 native detector is only created while this is true; it is
 *                 torn down and recreated if the value changes. Permissions are
 *                 requested centrally (see PermissionsScreen / permissions.ts),
 *                 not here, so the dialogs no longer collide with the camera one.
 */
export function useCallBridge(
  send: (message: OutboundMessage) => boolean,
  enabled: boolean
): UseCallBridgeResult {
  const [log, setLog] = useState<CallLogEntry[]>([]);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);

  // Always call through the latest `send` even though the detector is created
  // once. (`send` from useWebSocket changes identity when the URL changes.)
  const sendRef = useRef(send);
  sendRef.current = send;

  // Sequence-tracking state for direction inference. Refs, not state, because
  // the native callback must read/write them synchronously without re-renders.
  const incomingInProgress = useRef(false);
  const outgoingInProgress = useRef(false);
  const activePhone = useRef('');
  const logSeq = useRef(0);

  useEffect(() => {
    // Call detection is Android-only and only runs once permissions are granted.
    if (Platform.OS !== 'android' || !enabled) {
      return;
    }

    let detector: CallDetectorManager | null = null;

    const pushLog = (entry: CallLogEntry) =>
      setLog((prev) => [entry, ...prev].slice(0, MAX_LOG));

    const emit = (message: OutboundMessage) => {
      sendRef.current(message);
      pushLog({
        id: `${Date.now()}-${logSeq.current++}`,
        at: Date.now(),
        type: message.type,
        phone: message.phone,
        direction: message.type === 'call_start' ? message.direction : undefined,
      });
    };

    const handleCall = (event: CallEvent, rawNumber?: string | null) => {
      const phone = (rawNumber ?? '').toString().trim();

      switch (event) {
        case 'Incoming': {
          // Incoming ring. The number is required to be useful to the POS.
          if (!phone) break;
          incomingInProgress.current = true;
          outgoingInProgress.current = false;
          activePhone.current = phone;
          emit({ type: 'call_start', phone, direction: 'in' });
          setActiveCall({ phone, direction: 'in', startedAt: Date.now() });
          break;
        }

        case 'Offhook': {
          if (incomingInProgress.current) {
            // The earlier incoming call was answered — already announced.
            if (phone) activePhone.current = phone;
          } else {
            // No preceding ring => the operator placed an outgoing call.
            outgoingInProgress.current = true;
            const outPhone = phone || activePhone.current;
            activePhone.current = outPhone;
            emit({ type: 'call_start', phone: outPhone, direction: 'out' });
            setActiveCall({ phone: outPhone, direction: 'out', startedAt: Date.now() });
          }
          break;
        }

        case 'Disconnected':
        case 'Missed': {
          // End the call only if one was actually being tracked.
          if (
            incomingInProgress.current ||
            outgoingInProgress.current ||
            activePhone.current
          ) {
            emit({ type: 'call_end', phone: phone || activePhone.current });
          }
          incomingInProgress.current = false;
          outgoingInProgress.current = false;
          activePhone.current = '';
          setActiveCall(null);
          break;
        }

        // 'Connected' / 'Dialing' are iOS-only and never reach Android.
        default:
          break;
      }
    };

    detector = new CallDetectorManager(
      handleCall,
      true, // readPhoneNumberAndroid — supply the number on Android
      () => {
        // Native-side permission denial (should not happen once gated).
      },
      {
        title: 'Phone access required',
        message:
          'AlphaPOS Operator Link needs phone state and call-log access to ' +
          'read the caller number and send it to the POS desktop.',
      }
    );

    return () => {
      if (detector) {
        try {
          detector.dispose();
        } catch {
          // Ignore disposal errors during teardown.
        }
        detector = null;
      }
      setActiveCall(null);
    };
  }, [enabled]);

  return { log, activeCall };
}
