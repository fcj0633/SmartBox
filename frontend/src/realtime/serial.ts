// A resource has one reader. Notifications during an in-flight read coalesce into one follow-up.
export class SerialRead {
  private running = false;
  private dirty = false;
  private stopped = false;
  private timer?: ReturnType<typeof setTimeout>;
  private controller?: AbortController;
  private last = 0;
  constructor(private read: (signal: AbortSignal) => Promise<void>, private interval = 1000) {}
  trigger() {
    if (this.stopped) return;
    this.dirty = true;
    if (this.running || this.timer) return;
    const delay = Math.max(0, this.interval - (Date.now() - this.last));
    this.timer = setTimeout(() => { this.timer = undefined; void this.run(); }, delay);
  }
  private async run() {
    if (this.stopped) return;
    this.running = true; this.dirty = false; this.last = Date.now();
    this.controller = new AbortController();
    try { await this.read(this.controller.signal); } catch { /* The reader owns presentation of errors. */ }
    finally { this.running = false; if (this.dirty && !this.stopped) this.trigger(); }
  }
  stop() { this.stopped = true; this.dirty = false; clearTimeout(this.timer); this.controller?.abort(); }
}
