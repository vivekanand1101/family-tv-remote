import type { PluginListenerHandle } from '@capacitor/core';
export interface TvDevice { name?: string; host?: string; port?: number; serviceName?: string; [key: string]: unknown; }
export interface PairResult { paired?: boolean; [key: string]: unknown; }
export interface NetworkState { wifiConnected: boolean; cellularConnected: boolean; internetAvailable: boolean; }
export interface VolumeState { available: boolean; ip?: string; level?: number; max?: number; muted?: boolean; }
export interface RemoteState {
  connected: boolean;
  ip: string;
  manufacturer: string;
  model: string;
  serviceVersion: string;
  power: boolean | null;
  currentApp: string;
  keyboardEnabled: boolean;
  keyboardActive: boolean;
  capabilities: { keys: boolean; volume: boolean; power: boolean; appLinks: boolean; keyboard: boolean; voice: boolean; };
  volume: VolumeState;
}
export interface RedmiRemotePlugin {
  getNetworkState(): Promise<NetworkState>;
  openWifiSettings(): Promise<{ opened: boolean }>;
  discover(): Promise<{ devices: TvDevice[] }>;
  pair(options: { ip: string; port?: number }): Promise<PairResult>;
  finishPairing(options: { pin: string }): Promise<PairResult>;
  cancelPairing(): Promise<void>;
  send(options: { command: string; value?: number }): Promise<void>;
  getState(): Promise<RemoteState>;
  setKeyboardEnabled(options: { enabled: boolean }): Promise<RemoteState>;
  sendText(options: { text: string }): Promise<void>;
  /** Requires a TV-reported field length; otherwise rejects TEXT_FIELD_UNKNOWN. Empty text clears it. */
  replaceText(options: { text: string }): Promise<void>;
  addListener(eventName: 'stateChanged', listenerFunc: (state: RemoteState) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'volumeChanged', listenerFunc: (volume: VolumeState) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'networkChanged', listenerFunc: (network: NetworkState) => void): Promise<PluginListenerHandle>;
  addListener(eventName: string, listenerFunc: (...args: any[]) => void): Promise<PluginListenerHandle>;
}
export const RedmiRemote: RedmiRemotePlugin;
