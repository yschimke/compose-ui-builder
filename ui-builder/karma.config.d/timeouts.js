// Headless Chrome on a busy CI runner can go quiet for longer than Karma's 30s default while it
// loads and instantiates the Wasm test bundle; Karma then drops it ("Disconnected (0 times),
// because no message in 30000 ms") before a single test runs. Give it room to boot instead.
config.set({
  browserNoActivityTimeout: 120000,
  captureTimeout: 120000,
  browserDisconnectTimeout: 30000,
  browserDisconnectTolerance: 1,
});
