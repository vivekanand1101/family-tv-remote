import type { NetworkState } from './remote';

export type ConnectionIssueKind = 'wifi-required' | 'tv-unreachable' | 'companion-unreachable' | 'jio-unreachable';

export interface ConnectionIssue {
  kind: ConnectionIssueKind;
  title: string;
  message: string;
}

export function wifiRequiredIssue(network: NetworkState): ConnectionIssue | null {
  if (network.wifiConnected) return null;
  return {
    kind: 'wifi-required',
    title: 'Connect to home Wi-Fi',
    message: network.cellularConnected
      ? 'Mobile data cannot reach the TV. Connect this phone to the same home Wi-Fi as Living room TV.'
      : 'This phone is not connected to Wi-Fi. Connect it to the same home Wi-Fi as Living room TV.',
  };
}

export function connectionIssueFor(
  area: 'discovery' | 'tv' | 'companion' | 'jio',
  network: NetworkState,
  tvName: string,
): ConnectionIssue {
  const wifiIssue = wifiRequiredIssue(network);
  if (wifiIssue) return wifiIssue;
  if (area === 'companion') {
    return {
      kind: 'companion-unreachable',
      title: 'Jio controls are unavailable',
      message: `${tvName} is reachable, but its Family TV Companion did not respond. Sony controls remain available.`,
    };
  }
  if (area === 'jio') {
    return {
      kind: 'jio-unreachable',
      title: 'Jio box did not respond',
      message: 'The TV is reachable, but the Jio box could not complete this request. Check that the box has power and try again.',
    };
  }
  return {
    kind: 'tv-unreachable',
    title: area === 'discovery' ? 'No TV found on this Wi-Fi' : `${tvName} is not reachable`,
    message: 'Stay within Wi-Fi range and make sure this phone and the TV are connected to the same home network.',
  };
}
