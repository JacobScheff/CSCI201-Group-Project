/** Stands in for the browser WebSocket so tests can play the server's role. */
export class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }

  /** Messages the app sent, parsed from JSON. */
  send(data) { this.sent.push(JSON.parse(data)); }

  close() { this.readyState = FakeWebSocket.CLOSED; }

  // Test controls: act as the server.
  open() { this.readyState = FakeWebSocket.OPEN; this.onopen?.({}); }
  receive(message) { this.onmessage?.({ data: JSON.stringify(message) }); }
  drop() { this.readyState = FakeWebSocket.CLOSED; this.onclose?.({}); }

  static latest() { return FakeWebSocket.instances.at(-1); }
}
