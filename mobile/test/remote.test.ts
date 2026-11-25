import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChannelShortcut } from '../src/types';

const mocks = vi.hoisted(() => ({
  send: vi.fn(),
  getState: vi.fn(),
  setKeyboardEnabled: vi.fn(),
  replaceText: vi.fn(),
  sendText: vi.fn(),
}));

vi.mock('@capacitor/core', () => ({
  Capacitor: { isNativePlatform: () => true },
}));

vi.mock('capacitor-codeplay-wifi-tv-remote', () => ({
  RedmiRemote: mocks,
}));

import { enforceTvMute, openTvApp, playJioTvChannel, remoteTargetForCurrentApp, selectHdmi1, sendJioCommand, sleepTv, toggleTvMute, wakeTv } from '../src/remote';

const channel: ChannelShortcut = {
  id: 'aaj-tak',
  name: 'Aaj Tak',
  shortName: 'AAJ',
  contentId: '301036',
  channelNumber: 509,
  provider: 'JioCinema',
  vendor: 'TV Today',
  category: 'Hindi News',
  color: '#d9232e',
};

describe('remote target routing', () => {
  it('routes Sony apps to the TV and HDMI playback to the Jio box', () => {
    expect(remoteTargetForCurrentApp('com.google.android.youtube.tv')).toBe('tv');
    expect(remoteTargetForCurrentApp('in.startv.hotstar')).toBe('tv');
    expect(remoteTargetForCurrentApp('com.mediatek.tv.oneworld.tvcenter')).toBe('jio');
    expect(remoteTargetForCurrentApp(null)).toBeNull();
  });
});

describe('JioTV+ channel workflow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    mocks.send.mockResolvedValue(undefined);
    mocks.getState
      .mockResolvedValueOnce({ power: false })
      .mockResolvedValue({ power: true });
    mocks.setKeyboardEnabled.mockResolvedValue({});
    mocks.replaceText.mockResolvedValue(undefined);
    mocks.sendText.mockResolvedValue(undefined);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ launched: true, ready: true }),
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('wakes the TV, selects HDMI 1, then asks the companion to tune the JioSTB', async () => {
    const workflow = playJioTvChannel('192.0.2.1', channel);
    await vi.runAllTimersAsync();
    await workflow;

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/power/wake',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(mocks.send).toHaveBeenCalledWith({ command: 'hdmi1' });
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/audio/mute'))).toBe(false);
    expect(fetch).not.toHaveBeenCalledWith(
      'http://192.0.2.1:6470/input/hdmi1',
      expect.anything(),
    );
    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/jio/channel',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ channelNumber: 509 }),
      }),
    );

    const wakeCall = vi.mocked(fetch).mock.calls.findIndex(([url]) => String(url).endsWith('/power/wake'));
    const wakeOrder = vi.mocked(fetch).mock.invocationCallOrder[wakeCall];
    const hdmiOrder = mocks.send.mock.invocationCallOrder[0];
    const channelCall = vi.mocked(fetch).mock.calls.findIndex(([url]) => String(url).endsWith('/jio/channel'));
    expect(hdmiOrder).toBeGreaterThan(wakeOrder);
    expect(channelCall).toBeGreaterThanOrEqual(0);
    expect(vi.mocked(fetch).mock.invocationCallOrder[channelCall]).toBeGreaterThan(hdmiOrder);
  });

  it('waits for the TV companion during a cold boot before tuning', async () => {
    let healthAttempts = 0;
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (input: string | URL | Request) => ({
      ok: true,
      json: async () => {
        if (String(input).endsWith('/health')) {
          healthAttempts += 1;
          return { ready: healthAttempts >= 3 };
        }
        return { launched: true };
      },
    })));

    const workflow = playJioTvChannel('192.0.2.1', channel);
    await vi.runAllTimersAsync();
    await workflow;

    expect(healthAttempts).toBe(3);
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/jio/channel'))).toBe(true);
  });

  it('captures audio state before powering the TV off', async () => {
    await sleepTv('192.0.2.1');

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/power/prepare-sleep',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(mocks.send).toHaveBeenCalledWith({ command: 'power' });
  });

  it('uses the TV companion for wake without changing the audio state', async () => {
    const workflow = wakeTv('192.0.2.1');
    await vi.runAllTimersAsync();
    await workflow;

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/power/wake',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(mocks.send).not.toHaveBeenCalled();
  });

  it('falls back to Remote v2 when the TV companion cannot wake the TV', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    const workflow = wakeTv('192.0.2.1');
    await vi.runAllTimersAsync();
    await workflow;

    expect(mocks.send).toHaveBeenCalledWith({ command: 'wake' });
  });

  it('rejects wake when the TV never reports an awake state', async () => {
    mocks.getState.mockReset().mockResolvedValue({ power: null });
    const workflow = wakeTv('192.0.2.1');
    const assertion = expect(workflow).rejects.toThrow('The TV did not turn on');
    await vi.runAllTimersAsync();
    await assertion;
  });

  it('sends the HDMI app-link command without changing the audio state', async () => {
    await selectHdmi1();

    expect(mocks.send).toHaveBeenCalledWith({ command: 'hdmi1' });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('opens Hotstar through Remote v2 without changing the audio state', async () => {
    const workflow = openTvApp('192.0.2.1', 'hotstar');
    await vi.runAllTimersAsync();
    await workflow;

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/power/wake',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(mocks.send).toHaveBeenCalledTimes(1);
    expect(mocks.send).toHaveBeenCalledWith({ command: 'hotstar' });
  });

  it('mutes only when the explicit mute action is requested', async () => {
    await enforceTvMute('192.0.2.1');

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/audio/mute',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Family-Remote-Token': expect.any(String) }),
      }),
    );
  });

  it('toggles mute through the TV companion', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ muted: false, volume: 10 }),
    }));

    await expect(toggleTvMute('192.0.2.1')).resolves.toEqual({ muted: false, volume: 10 });
    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/audio/toggle-mute',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('maps Jio remote buttons to authenticated companion key requests', async () => {
    await sendJioCommand('192.0.2.1', 'playPause');

    expect(fetch).toHaveBeenCalledWith(
      'http://192.0.2.1:6470/jio/key',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ keyCode: 85 }) }),
    );
  });
});
