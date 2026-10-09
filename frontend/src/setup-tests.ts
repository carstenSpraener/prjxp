/** Mock localStorage for Vitest (runs in Node.js, not a browser). */
const storage: Record<string, string> = {};

Object.defineProperty(globalThis, 'localStorage', {
  value: {
    getItem(key: string): string | null {
      return key in storage ? storage[key] : null;
    },
    setItem(key: string, value: string): void {
      storage[key] = String(value);
    },
    removeItem(key: string): void {
      delete storage[key];
    },
    get length(): number {
      return Object.keys(storage).length;
    },
    key(index: number): string | null {
      const keys = Object.keys(storage);
      return index < keys.length ? keys[index] : null;
    },
    clear(): void {
      for (const key of Object.keys(storage)) {
        delete storage[key];
      }
    },
  },
  writable: true,
});
