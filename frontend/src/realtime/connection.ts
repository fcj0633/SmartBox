import { decode, encode } from '../api/json';
export type ConnectionState = 'connecting' | 'connected' | 'disconnected';
export class Realtime {
  private socket?: WebSocket;
  private retry?: ReturnType<typeof setTimeout>;
  private stopped = false;
  private attempt = 0;
  constructor(private ticket: () => Promise<string>, private boxes: string[], private state: (s: ConnectionState) => void, private changed: (message: { type: string; box_id?: string; data?: { operation_id?: string } }) => void, private resync: () => Promise<boolean>) {}
  async connect() {
    if (this.stopped) return;
    this.state('connecting');
    try {
      const ticket = await this.ticket(); if (this.stopped) return;
      const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws?ticket=${encodeURIComponent(ticket)}`);
      this.socket = socket;
      socket.onopen = () => {
        if (this.stopped) { socket.close(); return; }
        socket.send(encode({ v: 1, type: 'subscribe', box_ids: this.boxes }));
        this.attempt = 0; this.state('connected'); this.changed({ type: 'resync' });
      };
      socket.onmessage = (event) => {
        if (this.stopped) return;
        try { const message = decode<{ type: string; box_id?: string; data?: { operation_id?: string } }>(event.data); if (message.type === 'error') socket.close(); else this.changed(message); }
        catch { socket.close(); }
      };
      socket.onerror = () => socket.close();
      socket.onclose = () => { if (!this.stopped) void this.disconnected(); };
    } catch { if (!this.stopped) void this.disconnected(); }
  }
  private async disconnected() {
    this.state('disconnected');
    const allowed = await this.resync();
    if (this.stopped || !allowed) return;
    const wait = Math.min(30000, 1000 * 2 ** Math.min(this.attempt++, 5)) + Math.random() * 250;
    this.retry = setTimeout(() => void this.connect(), wait);
  }
  stop() {
    this.stopped = true; clearTimeout(this.retry);
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(encode({ v: 1, type: 'unsubscribe', box_ids: this.boxes }));
    this.socket?.close();
  }
}
