import { Capacitor } from '@capacitor/core';
import { RedmiRemote, type NetworkState, type RemoteState, type TvDevice } from 'capacitor-codeplay-wifi-tv-remote';
import type { JioChannelTarget, RemoteCommand, RemoteTarget, TvApplication } from './types';

export type { NetworkState } from 'capacitor-codeplay-wifi-tv-remote';

export const isNative = Capacitor.isNativePlatform();
export const canOpenWifiSettings = (Capacitor as { getPlatform?: () => string }).getPlatform?.() === 'android';
const COMPANION_PORT = 6470;
const COMPANION_TOKEN = import.meta.env.VITE_FAMILY_REMOTE_TOKEN?.trim();
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function companionToken(): string {
  if (!COMPANION_TOKEN) {
    throw new Error('Set VITE_FAMILY_REMOTE_TOKEN before using Family TV Companion');
  }
  return COMPANION_TOKEN;
}

const jioKeyCodes: Partial<Record<RemoteCommand, number>> = {
  up: 19,
  down: 20,
  left: 21,
  right: 22,
  ok: 23,
  home: 3,
  back: 4,
  menu: 82,
  search: 84,
  playPause: 85,
  channelUp: 166,
  channelDown: 167,
  enter: 66,
  delete: 67,
  keyboardSearch: 66,
};

async function companionRequest(
  ip: string,
  path: string,
  options?: RequestInit,
  timeoutMs = 3000,
): Promise<Record<string, unknown>> {
  const controller = new AbortController();
  const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`http://${ip}:${COMPANION_PORT}${path}`, {
      ...options,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        'X-Family-Remote-Token': companionToken(),
        ...options?.headers,
      },
    });
    const result = await response.json() as Record<string, unknown>;
    if (!response.ok) throw new Error(String(result.message || 'TV companion request failed'));
    return result;
  } finally {
    globalThis.clearTimeout(timeout);
  }
}

export function remoteTargetForCurrentApp(currentApp: string | null | undefined): RemoteTarget | null {
  if (!currentApp) return null;
  return currentApp.includes('com.mediatek.tv.oneworld.tvcenter') ? 'jio' : 'tv';
}

export async function getLocalNetworkState(): Promise<NetworkState> {
  if (!isNative) return { wifiConnected: true, cellularConnected: false, internetAvailable: true };
  return RedmiRemote.getNetworkState();
}

export async function openWifiSettings(): Promise<boolean> {
  if (!isNative) return false;
  return (await RedmiRemote.openWifiSettings()).opened;
}

export async function subscribeLocalNetworkState(listener: (network: NetworkState) => void): Promise<() => void> {
  if (!isNative) return () => undefined;
  const handle = await RedmiRemote.addListener('networkChanged', listener);
  return () => { void handle.remove(); };
}

export async function discoverTvs(): Promise<TvDevice[]> {
  if (!isNative) return [{ name: 'Living room TV (preview)', ip: '192.168.1.100' }];
  return (await RedmiRemote.discover()).devices;
}

export async function pairTv(ip: string): Promise<{ connected: boolean; pinRequired: boolean }> {
  if (!isNative) return { connected: true, pinRequired: false };
  const result = await RedmiRemote.pair({ ip }) as Record<string, unknown>;
  return { connected: result.connected === true, pinRequired: result.pinRequired === true };
}

export async function finishPairing(pin: string): Promise<void> {
  if (!isNative) return;
  await RedmiRemote.finishPairing({ pin });
}

export async function getTvState(): Promise<RemoteState | null> {
  if (!isNative) return null;
  return RedmiRemote.getState();
}

export async function subscribeTvState(listener: (state: RemoteState) => void): Promise<() => void> {
  if (!isNative) return () => undefined;
  const handle = await RedmiRemote.addListener('stateChanged', listener);
  return () => { void handle.remove(); };
}

export async function sendCommand(command: RemoteCommand): Promise<void> {
  if (!isNative) {
    console.info('[TV preview]', command);
    return;
  }
  await RedmiRemote.send({ command });
}

export async function sendText(text: string): Promise<void> {
  if (!isNative) {
    console.info('[TV preview] text', text);
    return;
  }
  await RedmiRemote.setKeyboardEnabled({ enabled: true });
  await RedmiRemote.sendText({ text });
}

export async function checkTvCompanion(ip: string): Promise<boolean> {
  if (!isNative) return true;
  try {
    const result = await companionRequest(ip, '/health', undefined, 1500);
    return result.ready === true;
  } catch {
    return false;
  }
}

export async function enforceTvMute(ip: string): Promise<void> {
  await companionRequest(ip, '/audio/mute', { method: 'POST', body: '{}' }, 2500);
}

export async function toggleTvMute(ip: string): Promise<{ muted: boolean; volume: number }> {
  const result = await companionRequest(ip, '/audio/toggle-mute', { method: 'POST', body: '{}' }, 2500);
  return {
    muted: result.muted === true,
    volume: Number(result.volume ?? 0),
  };
}

export async function sleepTv(ip: string): Promise<void> {
  try {
    await companionRequest(ip, '/power/prepare-sleep', { method: 'POST', body: '{}' }, 2500);
  } catch {
    // Power-off remains available if the companion is temporarily unavailable.
  }
  await sendCommand('power');
}

export async function selectHdmi1(): Promise<void> {
  await sendCommand('hdmi1');
}

export async function openTvApp(ip: string, command: 'youtube' | 'hotstar'): Promise<void> {
  await wakeTv(ip);
  await sendCommand(command);
}

export async function listTvApplications(ip: string): Promise<TvApplication[]> {
  if (!isNative) return [
    { id: 'com.google.android.youtube.tv', name: 'YouTube' },
    { id: 'in.startv.hotstar', name: 'JioHotstar' },
    { id: 'com.netflix.ninja', name: 'Netflix' },
  ];
  const result = await companionRequest(ip, '/tv/apps', undefined, 5000);
  if (!Array.isArray(result.apps)) return [];
  return result.apps.flatMap((app) => {
    if (!app || typeof app !== 'object') return [];
    const record = app as Record<string, unknown>;
    const id = typeof record.id === 'string' ? record.id : '';
    const name = typeof record.name === 'string' ? record.name : '';
    const icon = typeof record.icon === 'string' ? record.icon : undefined;
    return id && name ? [{ id, name, icon }] : [];
  });
}

export async function openInstalledTvApplication(ip: string, app: TvApplication): Promise<void> {
  if (!isNative) {
    console.info('[TV preview] open installed app', app);
    return;
  }
  await wakeTv(ip);
  await companionRequest(ip, '/tv/apps/open', {
    method: 'POST',
    body: JSON.stringify({ id: app.id }),
  }, 5000);
}

export async function wakeTv(
  ip: string,
  onProgress?: (message: string) => void,
): Promise<RemoteState | null> {
  if (!isNative) return null;
  onProgress?.('Turning TV on…');
  try {
    await companionRequest(ip, '/power/wake', { method: 'POST', body: '{}' }, 5000);
  } catch {
    await RedmiRemote.send({ command: 'wake' });
  }

  let state = await RedmiRemote.getState();
  for (let attempt = 0; attempt < 16 && state.power !== true; attempt += 1) {
    await sleep(500);
    state = await RedmiRemote.getState();
  }
  if (state.power !== true) {
    throw new Error('The TV did not turn on. Press Power once more.');
  }
  return state;
}

export async function sendJioCommand(ip: string, command: RemoteCommand): Promise<void> {
  if (!isNative) {
    console.info('[JioSTB preview]', command);
    return;
  }
  const keyCode = jioKeyCodes[command];
  if (keyCode === undefined) throw new Error(`${command} is not available for the Jio box`);
  await companionRequest(ip, '/jio/key', {
    method: 'POST',
    body: JSON.stringify({ keyCode }),
  });
}

export async function sendJioText(ip: string, text: string): Promise<void> {
  if (!isNative) {
    console.info('[JioSTB preview] text', text);
    return;
  }
  await companionRequest(ip, '/jio/text', {
    method: 'POST',
    body: JSON.stringify({ text }),
  }, 5000);
}

async function waitForTvCompanion(ip: string, timeoutMs = 60_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await checkTvCompanion(ip)) return;
    await sleep(2000);
  }
  throw new Error('Family TV Companion did not start after the TV booted');
}

export async function playJioTvChannel(
  ip: string,
  channel: JioChannelTarget,
  onProgress?: (message: string) => void,
): Promise<void> {
  if (!isNative) {
    console.info('[TV preview] JioTV+', channel);
    return;
  }

  await wakeTv(ip, onProgress);
  onProgress?.('Selecting HDMI 1…');
  await selectHdmi1();
  await sleep(1500);

  onProgress?.('Starting JioTV+ controls…');
  await waitForTvCompanion(ip);
  onProgress?.(`Opening ${channel.name}…`);
  await companionRequest(ip, '/jio/channel', {
    method: 'POST',
    body: JSON.stringify({ channelNumber: channel.channelNumber }),
  }, 45000);
}
