import { useEffect, useMemo, useRef, useState } from 'react';
import './App.css';
import { searchJioChannels, searchTvApplications } from './catalogueSearch';
import { channels } from './channels';
import { connectionIssueFor, wifiRequiredIssue, type ConnectionIssue } from './connectionIssues';
import { jioChannels } from './jioChannels.generated';
import { startPressRepeat } from './pressRepeat';
import {
  canOpenWifiSettings,
  checkTvCompanion,
  discoverTvs,
  finishPairing,
  getLocalNetworkState,
  getTvState,
  isNative,
  listTvApplications,
  openInstalledTvApplication,
  openTvApp,
  openWifiSettings,
  pairTv,
  playJioTvChannel,
  remoteTargetForCurrentApp,
  selectHdmi1,
  sendCommand,
  sleepTv,
  sendJioCommand,
  sendJioText,
  sendText,
  subscribeLocalNetworkState,
  subscribeTvState,
  toggleTvMute,
  wakeTv,
  type NetworkState,
} from './remote';
import type { ChannelCategory, JioChannelTarget, RemoteCommand, RemoteTarget, TvApplication } from './types';

const TV_IP_KEY = 'family-tv-remote.tv-ip';
const TV_NAME_KEY = 'family-tv-remote.tv-name';
const TV_CACHE_KEY = 'family-tv-remote.discovered-tvs.v1';
const TV_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const categories: ChannelCategory[] = ['Sports', 'Hindi News', 'Entertainment'];
const CATALOGUE_PAGE_SIZE = 40;

interface DiscoveredTv {
  name: string;
  ip: string;
}

function loadCachedTvs(): DiscoveredTv[] {
  try {
    const cached = JSON.parse(localStorage.getItem(TV_CACHE_KEY) || 'null') as {
      savedAt: number;
      devices: DiscoveredTv[];
    } | null;
    if (!cached || Date.now() - cached.savedAt > TV_CACHE_TTL_MS) {
      localStorage.removeItem(TV_CACHE_KEY);
      return [];
    }
    return cached.devices;
  } catch {
    localStorage.removeItem(TV_CACHE_KEY);
    return [];
  }
}

function cacheTvs(devices: DiscoveredTv[]) {
  localStorage.setItem(TV_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), devices }));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
}

function ConnectionIssueCard({
  issue,
  onRetry,
  onOpenWifi,
}: {
  issue: ConnectionIssue;
  onRetry: () => Promise<void>;
  onOpenWifi: () => Promise<void>;
}) {
  return (
    <section className="connection-issue" role="alert">
      <strong>{issue.title}</strong>
      <p>{issue.message}</p>
      <div>
        {issue.kind === 'wifi-required' && canOpenWifiSettings && (
          <button className="primary" onClick={() => void onOpenWifi()}>Open Wi-Fi</button>
        )}
        <button className="secondary" onClick={() => void onRetry()}>Retry</button>
      </div>
      {issue.kind === 'wifi-required' && !canOpenWifiSettings && (
        <small>Open iPhone Settings → Wi-Fi, choose your home network, then return here.</small>
      )}
    </section>
  );
}

function App() {
  const [connected, setConnected] = useState(!isNative);
  const [tvIp, setTvIp] = useState(localStorage.getItem(TV_IP_KEY) || '');
  const [tvName, setTvName] = useState(localStorage.getItem(TV_NAME_KEY) || 'Living room TV');
  const [pinRequired, setPinRequired] = useState(false);
  const [pin, setPin] = useState('');
  const [devices, setDevices] = useState<DiscoveredTv[]>(loadCachedTvs);
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState(isNative ? 'Find or select your TV' : 'Browser preview mode');
  const [text, setText] = useState('');
  const [companionConnected, setCompanionConnected] = useState(!isNative);
  const [tvAwake, setTvAwake] = useState<boolean | null>(isNative ? null : true);
  const [tvMuted, setTvMuted] = useState<boolean | null>(isNative ? null : false);
  const [remoteTarget, setRemoteTarget] = useState<RemoteTarget>('tv');
  const [networkState, setNetworkState] = useState<NetworkState | null>(null);
  const [connectionIssue, setConnectionIssue] = useState<ConnectionIssue | null>(null);
  const [catalogueTab, setCatalogueTab] = useState<'channels' | 'apps'>('channels');
  const [catalogueQuery, setCatalogueQuery] = useState('');
  const [catalogueLimit, setCatalogueLimit] = useState(CATALOGUE_PAGE_SIZE);
  const [tvApplications, setTvApplications] = useState<TvApplication[] | null>(null);
  const [tvApplicationsLoading, setTvApplicationsLoading] = useState(false);
  const [tvApplicationsError, setTvApplicationsError] = useState('');
  const connectionIssueRef = useRef<ConnectionIssue | null>(null);
  const stopVolumeRepeatRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (!connected) return;
    let unsubscribe: () => void = () => {};
    void subscribeTvState((state) => {
      setTvAwake(state.power);
      if (state.volume?.available && typeof state.volume.muted === 'boolean') {
        setTvMuted(state.volume.muted);
      }
      const target = remoteTargetForCurrentApp(state.currentApp);
      if (target) setRemoteTarget(target);
    }).then((remove) => { unsubscribe = remove; });
    return () => unsubscribe();
  }, [connected]);

  useEffect(() => () => stopVolumeRepeatRef.current?.(), []);
  useEffect(() => { connectionIssueRef.current = connectionIssue; }, [connectionIssue]);

  useEffect(() => {
    if (!connected || companionConnected || tvAwake === false || !tvIp) return;
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    const probe = async () => {
      const ready = await checkTvCompanion(tvIp);
      if (cancelled) return;
      if (ready) {
        setCompanionConnected(true);
        if (connectionIssueRef.current?.kind === 'companion-unreachable') setConnectionIssue(null);
        setMessage('JioTV+ shortcuts are ready');
        return;
      }
      attempts += 1;
      if (attempts < 45) retryTimer = setTimeout(() => void probe(), 2000);
    };
    retryTimer = setTimeout(() => void probe(), 1000);
    return () => {
      cancelled = true;
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [companionConnected, connected, tvAwake, tvIp]);

  useEffect(() => {
    void refreshNetworkState().then((network) => {
      const issue = wifiRequiredIssue(network);
      if (issue) setConnectionIssue(issue);
    });
  }, []);

  useEffect(() => {
    let unsubscribe: (() => void) | undefined;
    void subscribeLocalNetworkState((network) => {
      setNetworkState(network);
      const issue = wifiRequiredIssue(network);
      if (issue) {
        stopVolumeRepeatRef.current?.();
        setBusy('');
        setConnectionIssue(issue);
        setMessage(issue.title);
      } else if (connectionIssueRef.current?.kind === 'wifi-required') {
        setConnectionIssue(null);
        setMessage('Home Wi-Fi connected · Try again');
      }
    }).then((remove) => { unsubscribe = remove; });
    return () => unsubscribe?.();
  }, []);

  const groupedChannels = useMemo(
    () => Object.fromEntries(categories.map((category) => [
      category,
      channels.filter((channel) => channel.category === category),
    ])) as Record<ChannelCategory, typeof channels>,
    [],
  );
  const matchedCatalogueChannels = useMemo(
    () => searchJioChannels(jioChannels, catalogueQuery),
    [catalogueQuery],
  );
  const matchedTvApplications = useMemo(
    () => searchTvApplications(tvApplications ?? [], catalogueQuery),
    [catalogueQuery, tvApplications],
  );

  async function refreshNetworkState(): Promise<NetworkState> {
    try {
      const network = await getLocalNetworkState();
      setNetworkState(network);
      return network;
    } catch {
      // Network inspection is advisory. If it fails, still attempt local control.
      const fallback = { wifiConnected: true, cellularConnected: false, internetAvailable: false };
      setNetworkState(fallback);
      return fallback;
    }
  }

  async function requireHomeWifi(): Promise<boolean> {
    const network = await refreshNetworkState();
    const issue = wifiRequiredIssue(network);
    if (!issue) {
      setConnectionIssue(null);
      return true;
    }
    setConnectionIssue(issue);
    setMessage(issue.title);
    return false;
  }

  async function showConnectionFailure(
    area: 'discovery' | 'tv' | 'companion' | 'jio',
  ) {
    const network = await refreshNetworkState();
    const issue = connectionIssueFor(area, network, tvName);
    setConnectionIssue(issue);
    setMessage(issue.title);
  }

  async function showWifiSettings() {
    if (canOpenWifiSettings && await openWifiSettings()) {
      setMessage('Select your home Wi-Fi, then return and tap Retry');
      return;
    }
    setMessage('Open Settings → Wi-Fi and select the same home network as the TV');
  }

  async function retryConnection() {
    setConnectionIssue(null);
    if (!connected) {
      await scan();
    } else if (connectionIssue?.kind === 'companion-unreachable' || connectionIssue?.kind === 'jio-unreachable') {
      await checkCompanion();
    } else {
      await connect(tvIp, tvName);
    }
  }

  async function scan() {
    setBusy('Searching for TVs…');
    try {
      if (!await requireHomeWifi()) return;
      const found = await discoverTvs();
      const discovered = found.map((device) => ({
        name: String(device.name || 'Android TV'),
        ip: String(device.ip || device.host || ''),
      })).filter((device) => device.ip);
      setDevices(discovered);
      if (discovered.length) {
        cacheTvs(discovered);
        setConnectionIssue(null);
        setMessage('Select your TV');
      } else {
        localStorage.removeItem(TV_CACHE_KEY);
        await showConnectionFailure('discovery');
      }
    } catch {
      await showConnectionFailure('discovery');
    } finally {
      setBusy('');
    }
  }

  async function connect(ip: string, name: string) {
    setTvIp(ip);
    setTvName(name);
    setBusy(`Connecting to ${name}…`);
    try {
      if (!await requireHomeWifi()) return;
      const result = await pairTv(ip);
      setPinRequired(result.pinRequired);
      if (result.connected) {
        setConnected(true);
        localStorage.setItem(TV_IP_KEY, ip);
        localStorage.setItem(TV_NAME_KEY, name);
        const [state, companionReady] = await Promise.all([getTvState(), checkTvCompanion(ip)]);
        setCompanionConnected(companionReady);
        setTvAwake(state?.power ?? null);
        setTvMuted(state?.volume?.available && typeof state.volume.muted === 'boolean' ? state.volume.muted : null);
        const target = remoteTargetForCurrentApp(state?.currentApp);
        if (target) setRemoteTarget(target);
        setConnectionIssue(null);
        setMessage(state ? `${state.manufacturer} ${state.model}`.trim() || `${name} connected` : `${name} connected`);
      } else if (result.pinRequired) {
        setMessage('Enter the code shown on the TV');
      }
    } catch {
      setConnected(false);
      await showConnectionFailure('tv');
    } finally {
      setBusy('');
    }
  }

  async function submitPin() {
    setBusy('Pairing…');
    try {
      await finishPairing(pin.trim());
      setPinRequired(false);
      setConnected(true);
      localStorage.setItem(TV_IP_KEY, tvIp);
      localStorage.setItem(TV_NAME_KEY, tvName);
      const state = await getTvState();
      setTvAwake(state?.power ?? null);
      setTvMuted(state?.volume?.available && typeof state.volume.muted === 'boolean' ? state.volume.muted : null);
      const target = remoteTargetForCurrentApp(state?.currentApp);
      if (target) setRemoteTarget(target);
      setCompanionConnected(await checkTvCompanion(tvIp));
      setMessage(`${tvName} paired successfully`);
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      setBusy('');
    }
  }

  async function checkCompanion() {
    setBusy('Turning TV on…');
    try {
      if (!await requireHomeWifi()) return;
      await wakeTv(tvIp, setBusy);
      setTvAwake(true);
      setBusy('Checking TV companion…');
      const ready = await checkTvCompanion(tvIp);
      setCompanionConnected(ready);
      if (ready) {
        setConnectionIssue(null);
        setMessage('JioTV+ shortcuts are ready');
      } else {
        await showConnectionFailure('companion');
      }
    } catch {
      await showConnectionFailure('tv');
    } finally {
      setBusy('');
    }
  }

  async function command(value: RemoteCommand, target: RemoteTarget = remoteTarget) {
    const knownWifiIssue = networkState ? wifiRequiredIssue(networkState) : null;
    if (knownWifiIssue) {
      setConnectionIssue(knownWifiIssue);
      setMessage(knownWifiIssue.title);
      return;
    }
    const powerOperation = value === 'wake' || value === 'power';
    if (powerOperation) setBusy(value === 'wake' ? 'Turning TV on…' : 'Putting TV in standby…');
    try {
      if (value === 'wake') {
        await wakeTv(tvIp);
        setTvAwake(true);
      } else if (value === 'power') {
        await sleepTv(tvIp);
      } else if (target === 'jio') {
        await sendJioCommand(tvIp, value);
      } else {
        await sendCommand(value);
      }
      setConnectionIssue(null);
      if (value === 'power') {
        // Sony can close the remote connection before reporting its asleep
        // transition. Optimistically switch the Power button to turn-on behavior.
        setTvAwake(false);
        setMessage('TV sleeping · Press Power to turn it on');
      }
    } catch {
      await showConnectionFailure(target === 'jio' ? 'jio' : 'tv');
    } finally {
      if (powerOperation) setBusy('');
    }
  }

  function stopVolumeRepeat() {
    stopVolumeRepeatRef.current?.();
    stopVolumeRepeatRef.current = null;
  }

  function startVolumeRepeat(value: 'volumeDown' | 'volumeUp') {
    stopVolumeRepeat();
    stopVolumeRepeatRef.current = startPressRepeat(() => {
      void command(value, 'tv');
    });
  }

  async function toggleSonyMute() {
    try {
      if (!await requireHomeWifi()) return;
      const audio = await toggleTvMute(tvIp);
      setTvMuted(audio.muted);
      setConnectionIssue(null);
      setMessage(audio.muted ? 'TV muted · Volume 0' : `TV unmuted · Volume ${audio.volume}`);
    } catch {
      await showConnectionFailure('companion');
    }
  }

  async function chooseHdmi1() {
    setBusy('Selecting HDMI 1…');
    try {
      if (!await requireHomeWifi()) return;
      await wakeTv(tvIp);
      await selectHdmi1();
      setTvAwake(true);
      setRemoteTarget('jio');
      setConnectionIssue(null);
      setMessage('HDMI 1 selected');
    } catch {
      await showConnectionFailure('tv');
    } finally {
      setBusy('');
    }
  }

  async function launchTvApp(command: 'youtube' | 'hotstar', name: string) {
    setBusy(`Opening ${name}…`);
    try {
      if (!await requireHomeWifi()) return;
      await openTvApp(tvIp, command);
      setTvAwake(true);
      setRemoteTarget('tv');
      setConnectionIssue(null);
      setMessage(`${name} opened · Navigation controls Sony TV`);
    } catch {
      await showConnectionFailure('tv');
    } finally {
      setBusy('');
    }
  }

  async function loadTvApplications(force = false) {
    if ((!force && tvApplications !== null) || tvApplicationsLoading) return;
    setTvApplicationsLoading(true);
    setTvApplicationsError('');
    try {
      if (!await requireHomeWifi()) return;
      const apps = await listTvApplications(tvIp);
      setTvApplications(apps);
      setCompanionConnected(true);
    } catch {
      setTvApplicationsError('TV apps could not be loaded. Check Family TV Companion and retry.');
    } finally {
      setTvApplicationsLoading(false);
    }
  }

  function changeCatalogueTab(tab: 'channels' | 'apps') {
    setCatalogueTab(tab);
    setCatalogueQuery('');
    setCatalogueLimit(CATALOGUE_PAGE_SIZE);
    if (tab === 'apps') void loadTvApplications();
  }

  async function launchInstalledApplication(app: TvApplication) {
    setBusy(`Opening ${app.name}…`);
    try {
      if (!await requireHomeWifi()) return;
      await openInstalledTvApplication(tvIp, app);
      setRemoteTarget('tv');
      setTvAwake(true);
      setConnectionIssue(null);
      setMessage(`${app.name} opened · Navigation controls Sony TV`);
    } catch {
      await showConnectionFailure('companion');
    } finally {
      setBusy('');
    }
  }

  async function submitText() {
    if (!text.trim()) return;
    try {
      if (!await requireHomeWifi()) return;
      if (remoteTarget === 'jio') await sendJioText(tvIp, text.trim());
      else await sendText(text.trim());
      setConnectionIssue(null);
      setMessage(`Text sent to ${remoteTarget === 'jio' ? 'Jio box' : 'TV'}`);
    } catch {
      await showConnectionFailure(remoteTarget === 'jio' ? 'jio' : 'tv');
    }
  }

  async function runChannel(channel: JioChannelTarget) {
    setBusy(`Turning TV on for ${channel.name}…`);
    try {
      if (!await requireHomeWifi()) return;
      await playJioTvChannel(tvIp, channel, setBusy);
      setTvAwake(true);
      setRemoteTarget('jio');
      setCompanionConnected(true);
      setConnectionIssue(null);
      setMessage(`${channel.name} requested from JioTV+`);
    } catch {
      const companionReady = await checkTvCompanion(tvIp);
      setCompanionConnected(companionReady);
      await showConnectionFailure(companionReady ? 'jio' : 'companion');
    } finally {
      setBusy('');
    }
  }

  const localOnlyNote = networkState?.wifiConnected && !networkState.internetAvailable
    ? 'Remote controls are available offline · Streaming apps may not play'
    : '';

  if (!connected || pinRequired) {
    return (
      <main className={`setup-shell ${busy ? 'is-busy' : ''}`} aria-busy={!!busy}>
        <section className="setup-card">
          <div className="brand-mark">TV</div>
          <h1>Family TV Remote</h1>
          <p>{message}</p>
          {connectionIssue && (
            <ConnectionIssueCard issue={connectionIssue} onRetry={retryConnection} onOpenWifi={showWifiSettings} />
          )}
          {localOnlyNote && <small className="network-note">{localOnlyNote}</small>}

          {pinRequired ? (
            <>
              <label>Pairing code</label>
              <input value={pin} onChange={(event) => setPin(event.target.value.toUpperCase())} maxLength={6} placeholder="ABC123" />
              <button className="primary" onClick={() => void submitPin()} disabled={!!busy}>Pair TV</button>
              <button className="link-button" onClick={() => setPinRequired(false)}>Cancel</button>
            </>
          ) : !connectionIssue ? (
            <>
              <button className="primary find-tv" onClick={() => void scan()} disabled={!!busy}>
                {devices.length ? 'Search again' : 'Find TVs'}
              </button>
              {devices.length > 0 && <label>Available TVs</label>}
              {devices.map((device) => (
                <button className="device" key={device.ip} onClick={() => void connect(device.ip, device.name)}>
                  <strong>{device.name}</strong><span>›</span>
                </button>
              ))}
            </>
          ) : null}
          <small className={busy ? 'operation-progress' : ''}>
            {busy && <span className="spinner" />}
            {busy || (!isNative && 'Preview mode: native TV commands are logged in the console.')}
          </small>
        </section>
      </main>
    );
  }

  return (
    <main className={`app-shell ${busy ? 'is-busy' : ''}`} aria-busy={!!busy}>
      <header>
        <div><p className="eyebrow">{tvName}</p><h1>TV Remote</h1></div>
        <div className={`status ${tvAwake === false ? 'standby' : ''} ${busy ? 'working' : ''}`}><span />{busy || (tvAwake === false ? 'Connected · Standby' : 'Connected · Awake')}</div>
      </header>

      {connectionIssue && (
        <ConnectionIssueCard issue={connectionIssue} onRetry={retryConnection} onOpenWifi={showWifiSettings} />
      )}
      {localOnlyNote && <p className="network-note">{localOnlyNote}</p>}

      {!connectionIssue && !companionConnected && tvAwake !== false && (
        <section className="companion-panel">
          <div>
            <strong>Enable JioTV+ shortcuts</strong>
            <p>Install and open Family TV Companion once on the TV.</p>
          </div>
          <button className="primary" onClick={() => void checkCompanion()}>Check again</button>
        </section>
      )}

      <section className="essential-controls">
        <div className="section-title"><div><p className="eyebrow">Everyday controls</p><h2>TV</h2></div></div>
        <div className="essential-grid">
          <button className="essential power" onClick={() => void command(tvAwake === false ? 'wake' : 'power', 'tv')} disabled={!!busy}>
            <span>P</span><strong>Power</strong>
          </button>
          <button
            className={`essential mute ${tvMuted ? 'active' : ''}`}
            onClick={() => void toggleSonyMute()}
            disabled={!!busy}
            aria-pressed={tvMuted === true}
          >
            <span>{tvMuted ? '✓' : 'M'}</span><strong>{tvMuted ? 'Muted' : 'Mute'}</strong>
          </button>
          <button
            className="essential volume-control"
            onPointerDown={(event) => {
              if (event.button !== 0) return;
              event.currentTarget.setPointerCapture(event.pointerId);
              startVolumeRepeat('volumeDown');
            }}
            onPointerUp={stopVolumeRepeat}
            onPointerCancel={stopVolumeRepeat}
            onLostPointerCapture={stopVolumeRepeat}
            onClick={(event) => { if (event.detail === 0) void command('volumeDown', 'tv'); }}
            onContextMenu={(event) => event.preventDefault()}
            disabled={!!busy}
            aria-label="Volume down"
          >
            <span>−</span><strong>Volume</strong>
          </button>
          <button
            className="essential volume-control"
            onPointerDown={(event) => {
              if (event.button !== 0) return;
              event.currentTarget.setPointerCapture(event.pointerId);
              startVolumeRepeat('volumeUp');
            }}
            onPointerUp={stopVolumeRepeat}
            onPointerCancel={stopVolumeRepeat}
            onLostPointerCapture={stopVolumeRepeat}
            onClick={(event) => { if (event.detail === 0) void command('volumeUp', 'tv'); }}
            onContextMenu={(event) => event.preventDefault()}
            disabled={!!busy}
            aria-label="Volume up"
          >
            <span>+</span><strong>Volume</strong>
          </button>
        </div>
      </section>

      <section className="navigation-section">
        <div className="section-title"><div><p className="eyebrow">Remote control</p><h2>Navigation</h2></div></div>
        <div className="navigation-card">
          <div className="navigation-actions">
            <button onClick={() => void command('home')}>Home</button>
            <button onClick={() => void command('back')}>Back</button>
          </div>

          <div className="dpad" aria-label={`Directional pad controlling ${remoteTarget === 'jio' ? 'Jio box' : 'Sony TV'}`}>
            <button className="up" onClick={() => void command('up')}>▲</button>
            <button className="left" onClick={() => void command('left')}>◀</button>
            <button className="ok" onClick={() => void command('ok')}>OK</button>
            <button className="right" onClick={() => void command('right')}>▶</button>
            <button className="down" onClick={() => void command('down')}>▼</button>
          </div>
        </div>
      </section>

      <section className="app-shortcuts-section">
        <div className="section-title"><div><p className="eyebrow">Sony TV apps</p><h2>Apps</h2></div></div>
        <div className="app-shortcuts-grid">
          <button className="app-shortcut youtube" onClick={() => void launchTvApp('youtube', 'YouTube')} disabled={!!busy}>
            <span>▶</span><strong>YouTube</strong>
          </button>
          <button className="app-shortcut hotstar" onClick={() => void launchTvApp('hotstar', 'Hotstar')} disabled={!!busy}>
            <span>★</span><strong>Hotstar</strong>
          </button>
        </div>
      </section>

      <section className="favorites-section">
        <div className="section-title"><div><p className="eyebrow">One tap · JioTV+</p><h2>Favourite channels</h2></div></div>
        {categories.map((category) => (
          <div className="channel-group" key={category}>
            <h3>{category}</h3>
            <div className="favorites-grid">
              {groupedChannels[category].map((channel) => (
                <button
                  className="favorite"
                  style={{ '--favorite-color': channel.color } as React.CSSProperties}
                  key={channel.id}
                  onClick={() => void runChannel(channel)}
                  disabled={!!busy}
                >
                  <span className="favorite-logo">
                    <img
                      src={`/channel-logos/${channel.id}.jpg`}
                      alt=""
                      loading="lazy"
                      onError={(event) => { event.currentTarget.hidden = true; }}
                    />
                    <b>{channel.shortName}</b>
                  </span>
                  <strong>{channel.name}</strong>
                  <small>Ch {channel.channelNumber}</small>
                </button>
              ))}
            </div>
          </div>
        ))}
      </section>

      <details className="more-controls">
        <summary><strong>More controls</strong><span>Playback and text</span></summary>
        <section className="remote-card">
          <div className="advanced-actions">
            <button onClick={() => void command('playPause')}>Play / Pause</button>
            <button onClick={() => void command('search')}>Search</button>
            <button onClick={() => void chooseHdmi1()}>HDMI 1</button>
          </div>

          <div className="text-remote">
            <input value={text} onChange={(event) => setText(event.target.value)} placeholder="Type into a search field" />
            <button onClick={() => void submitText()}>Send</button>
            <button onClick={() => void command('keyboardSearch')}>Go</button>
          </div>
        </section>
      </details>

      <details
        className="catalogue-browser"
        onToggle={(event) => {
          if (event.currentTarget.open && catalogueTab === 'apps') void loadTvApplications();
        }}
      >
        <summary>
          <span><strong>All channels & TV apps</strong><small>Search the complete catalogue</small></span>
          <b>＋</b>
        </summary>
        <section className="catalogue-body">
          <div className="catalogue-tabs" role="tablist" aria-label="Full catalogue">
            <button
              className={catalogueTab === 'channels' ? 'active' : ''}
              onClick={() => changeCatalogueTab('channels')}
              role="tab"
              aria-selected={catalogueTab === 'channels'}
            >
              Channels
            </button>
            <button
              className={catalogueTab === 'apps' ? 'active' : ''}
              onClick={() => changeCatalogueTab('apps')}
              role="tab"
              aria-selected={catalogueTab === 'apps'}
            >
              TV apps
            </button>
          </div>

          <input
            className="catalogue-search"
            type="search"
            value={catalogueQuery}
            onChange={(event) => {
              setCatalogueQuery(event.target.value);
              setCatalogueLimit(CATALOGUE_PAGE_SIZE);
            }}
            placeholder={catalogueTab === 'channels'
              ? 'Search name, channel number or content ID'
              : 'Search installed TV apps'}
            aria-label={catalogueTab === 'channels' ? 'Search all Jio channels' : 'Search installed TV apps'}
          />

          {catalogueTab === 'channels' ? (
            <>
              <p className="catalogue-count">
                {matchedCatalogueChannels.length} channels · try “319”, “id:300330” or “Zee Cinema”
              </p>
              <div className="catalogue-results">
                {matchedCatalogueChannels.slice(0, catalogueLimit).map((channel) => (
                  <button
                    className="catalogue-result"
                    key={channel.key}
                    onClick={() => void runChannel(channel)}
                    disabled={!!busy}
                  >
                    <span className="catalogue-icon">
                      {initials(channel.name)}
                      {channel.logo && (
                        <img
                          src={channel.logo}
                          alt=""
                          onError={(event) => { event.currentTarget.style.display = 'none'; }}
                        />
                      )}
                    </span>
                    <span className="catalogue-copy">
                      <strong>{channel.name}</strong>
                      <small>
                        Ch {channel.channelNumber}
                        {channel.contentId ? ` · ID ${channel.contentId}` : ''}
                        {channel.language ? ` · ${channel.language}` : ''}
                        {channel.quality ? ` · ${channel.quality}` : ''}
                      </small>
                    </span>
                    <span className="catalogue-chevron">›</span>
                  </button>
                ))}
              </div>
              {matchedCatalogueChannels.length === 0 && <p className="catalogue-empty">No channels match this search.</p>}
              {catalogueLimit < matchedCatalogueChannels.length && (
                <button
                  className="catalogue-more"
                  onClick={() => setCatalogueLimit((current) => current + CATALOGUE_PAGE_SIZE)}
                >
                  Show more
                </button>
              )}
            </>
          ) : (
            <>
              {tvApplicationsLoading && <p className="catalogue-count"><span className="spinner" /> Loading TV apps…</p>}
              {tvApplicationsError && (
                <div className="catalogue-empty">
                  <p>{tvApplicationsError}</p>
                  <button className="secondary" onClick={() => void loadTvApplications(true)}>Retry</button>
                </div>
              )}
              {!tvApplicationsLoading && !tvApplicationsError && tvApplications !== null && (
                <>
                  <p className="catalogue-count">{matchedTvApplications.length} launchable TV apps</p>
                  <div className="catalogue-results">
                    {matchedTvApplications.map((app) => (
                      <button
                        className="catalogue-result"
                        key={app.id}
                        onClick={() => void launchInstalledApplication(app)}
                        disabled={!!busy}
                      >
                        <span className="catalogue-icon app">
                          {initials(app.name)}
                          {app.icon && (
                            <img
                              src={app.icon}
                              alt=""
                              onError={(event) => { event.currentTarget.style.display = 'none'; }}
                            />
                          )}
                        </span>
                        <span className="catalogue-copy"><strong>{app.name}</strong><small>Open on Sony TV</small></span>
                        <span className="catalogue-chevron">›</span>
                      </button>
                    ))}
                  </div>
                  {matchedTvApplications.length === 0 && <p className="catalogue-empty">No TV apps match this search.</p>}
                </>
              )}
            </>
          )}
        </section>
      </details>

      <footer>{message}</footer>
    </main>
  );
}

export default App;
