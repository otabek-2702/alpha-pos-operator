import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * WebSocket client for streaming call events to the desktop POS.
 *
 * - The desktop is the SERVER; this app is the CLIENT.
 * - Connects to the paired URL verbatim (token query string included).
 * - Auto-reconnects with capped backoff (1s, 2s, 5s) while a URL is set.
 * - Buffers outbound events in memory while disconnected (max 20) and flushes
 *   on reconnect so a call during a brief network drop is not lost.
 */

export type WsStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

/** The only message shapes this app ever sends. The desktop normalizes phones. */
export type OutboundMessage =
  | { type: 'call_start'; phone: string; direction: 'in' | 'out' }
  | { type: 'call_end'; phone: string };

/** Backoff schedule in ms; the last value is reused once exhausted. */
const BACKOFF_MS = [1000, 2000, 5000];

/** Max number of events buffered while disconnected. Oldest is dropped first. */
const MAX_QUEUE = 20;

export interface UseWebSocketResult {
  status: WsStatus;
  /**
   * Sends a message if connected, otherwise buffers it (when a URL is paired).
   * Returns true if the message went out on the wire immediately.
   */
  send: (message: OutboundMessage) => boolean;
}

export function useWebSocket(url: string | null): UseWebSocketResult {
  const [status, setStatus] = useState<WsStatus>('disconnected');

  // Live socket and the pending-send buffer. Refs survive reconnects and
  // re-renders so a queued event outlives the socket that failed to send it.
  const wsRef = useRef<WebSocket | null>(null);
  const queueRef = useRef<OutboundMessage[]>([]);

  // Keep the latest URL accessible inside the stable `send` callback.
  const urlRef = useRef<string | null>(url);
  urlRef.current = url;

  const send = useCallback((message: OutboundMessage): boolean => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(message));
        return true;
      } catch {
        // Fall through to buffering.
      }
    }
    // Only buffer while we have somewhere to eventually send it.
    if (urlRef.current) {
      const queue = queueRef.current;
      queue.push(message);
      if (queue.length > MAX_QUEUE) queue.shift();
    }
    return false;
  }, []);

  useEffect(() => {
    if (!url) {
      setStatus('disconnected');
      return;
    }

    let attempt = 0;
    let cancelled = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let socket: WebSocket | null = null;

    const flushQueue = () => {
      const ws = wsRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) return;
      const queue = queueRef.current;
      while (queue.length > 0) {
        const message = queue[0]!;
        try {
          ws.send(JSON.stringify(message));
          queue.shift();
        } catch {
          // Stop on the first failure; remaining items stay buffered in order.
          break;
        }
      }
    };

    const scheduleReconnect = () => {
      if (cancelled || !urlRef.current) {
        setStatus('disconnected');
        return;
      }
      setStatus('reconnecting');
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]!;
      attempt += 1;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(open, delay);
    };

    function open() {
      if (cancelled) return;
      setStatus(attempt === 0 ? 'connecting' : 'reconnecting');

      try {
        socket = new WebSocket(url as string);
      } catch {
        scheduleReconnect();
        return;
      }
      wsRef.current = socket;

      socket.onopen = () => {
        if (cancelled) return;
        attempt = 0;
        setStatus('connected');
        flushQueue();
      };

      // Desktop is send-only from our perspective; ignore any inbound data.
      socket.onmessage = () => {};

      // `onerror` is always followed by `onclose`, where reconnect is handled.
      socket.onerror = () => {};

      socket.onclose = () => {
        wsRef.current = null;
        if (cancelled) return;
        scheduleReconnect();
      };
    }

    open();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      const ws = wsRef.current;
      wsRef.current = null;
      if (ws) {
        // Detach handlers so the teardown close does not trigger a reconnect.
        ws.onopen = null;
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
        try {
          ws.close();
        } catch {
          // Ignore close errors during teardown.
        }
      }
      setStatus('disconnected');
    };
  }, [url]);

  return { status, send };
}
