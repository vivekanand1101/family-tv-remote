import { describe, expect, it } from 'vitest';
import { connectionIssueFor, wifiRequiredIssue } from '../src/connectionIssues';

const wifiWithoutInternet = {
  wifiConnected: true,
  cellularConnected: false,
  internetAvailable: false,
};

describe('local-network recovery messages', () => {
  it('does not block local control when Wi-Fi has no internet', () => {
    expect(wifiRequiredIssue(wifiWithoutInternet)).toBeNull();
  });

  it('explains that mobile data cannot reach the TV', () => {
    const issue = wifiRequiredIssue({
      wifiConnected: false,
      cellularConnected: true,
      internetAvailable: true,
    });
    expect(issue?.kind).toBe('wifi-required');
    expect(issue?.message).toContain('Mobile data');
  });

  it('distinguishes the TV, companion, and Jio box', () => {
    expect(connectionIssueFor('tv', wifiWithoutInternet, 'Living room TV').kind).toBe('tv-unreachable');
    expect(connectionIssueFor('companion', wifiWithoutInternet, 'Living room TV').kind).toBe('companion-unreachable');
    expect(connectionIssueFor('jio', wifiWithoutInternet, 'Living room TV').kind).toBe('jio-unreachable');
  });
});
