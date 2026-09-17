/** Two independent local POS peers for isolated Android emulator smoke tests. */
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { WebSocketServer } = require('ws');
const expected = new Map([[8765, 'operator-emulator-test-one'], [8767, 'operator-emulator-test-two']]);
for (const [port, token] of expected) {
  const server = new WebSocketServer({ port, host: '0.0.0.0', verifyClient: ({ req }) => new URL(req.url, 'http://local').searchParams.get('token') === token });
  server.on('connection', socket => {
    console.log(JSON.stringify({ port, event: 'connected' }));
    socket.on('message', raw => {
      const event = JSON.parse(raw.toString());
      console.log(JSON.stringify({ port, type: event.type, test: event.test ?? false, recordId: event.record?.id,
        ...(event.type === 'operator_hello' ? { role: event.role, protocol: event.protocol } : {}),
        ...(event.type === 'call_state' ? { states: (event.calls ?? []).map((call) => call.state) } : {}),
        ...(event.record ? { outcome: event.record.outcome, ringSeconds: event.record.ringSeconds,
          talkSeconds: event.record.talkSeconds, named: event.record.customerName === 'Sinov mijoz', revision: event.record.revision } : {}),
      }));
      if (event.type === 'call_start') socket.send(JSON.stringify({ type: 'customer_name', phone: event.phone, name: 'Sinov mijoz' }));
      if (event.type === 'call_record') socket.send(JSON.stringify({ type: 'call_record_ack', id: event.record.id, revision: event.record.revision }));
    });
    socket.on('close', () => console.log(JSON.stringify({ port, event: 'disconnected' })));
  });
  console.log(`Emulator POS ready on TCP ${port}`);
}
