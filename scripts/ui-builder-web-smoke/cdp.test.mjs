import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { headlessShellPath, PLAYWRIGHT_SWITCHES } from './cdp.mjs';

test('finds the headless shell installed beside Playwright Chromium', () => {
  const root = mkdtempSync(join(tmpdir(), 'cdp-test-'));
  const chrome = join(root, 'chromium-1194/chrome-linux/chrome');
  const shell = join(root, 'chromium_headless_shell-1194/chrome-linux/headless_shell');
  mkdirSync(join(root, 'chromium_headless_shell-1194/chrome-linux'), { recursive: true });
  writeFileSync(shell, '');
  assert.equal(headlessShellPath(chrome), shell);
});

test('keeps the full browser when no headless shell is installed', () => {
  const chrome = join(mkdtempSync(join(tmpdir(), 'cdp-test-')), 'chromium-1194/chrome-linux/chrome');
  assert.equal(headlessShellPath(chrome), chrome);
  assert.equal(headlessShellPath('/usr/bin/chromium'), '/usr/bin/chromium');
});

test('launches headless without a startup page or a debugging pipe', () => {
  assert.ok(PLAYWRIGHT_SWITCHES.includes('--headless'));
  assert.ok(!PLAYWRIGHT_SWITCHES.some((s) => s.startsWith('--remote-debugging') || s === '--no-startup-window'));
});
