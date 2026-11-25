import { describe, expect, it } from 'vitest';
import { searchJioChannels, searchTvApplications } from '../src/catalogueSearch';
import { jioChannels } from '../src/jioChannels.generated';

function firstChannel(query: string) {
  return searchJioChannels(jioChannels, query)[0];
}

describe('full Jio catalogue search', () => {
  it('finds a channel by channel number', () => {
    expect(firstChannel('319').name).toBe('Zee Cinema HD');
    expect(firstChannel('ch:319').channelNumber).toBe(319);
  });

  it('finds the same channel by Jio content ID', () => {
    expect(firstChannel('300330').channelNumber).toBe(319);
    expect(firstChannel('id:300330').name).toBe('Zee Cinema HD');
  });

  it('finds channels by normalized name and metadata', () => {
    expect(firstChannel('zee cinema hd').channelNumber).toBe(319);
    expect(searchJioChannels(jioChannels, 'hindi entertainment')).not.toHaveLength(0);
  });

  it('returns the complete number-sorted catalogue for an empty query', () => {
    const results = searchJioChannels(jioChannels, '');
    expect(results).toHaveLength(1102);
    expect(results[0].channelNumber).toBeLessThanOrEqual(results.at(-1)?.channelNumber ?? 0);
  });
});

describe('TV application search', () => {
  const apps = [
    { id: 'com.google.android.youtube.tv', name: 'YouTube' },
    { id: 'in.startv.hotstar', name: 'JioHotstar' },
  ];

  it('searches friendly names and keeps package identifiers internal', () => {
    expect(searchTvApplications(apps, 'youtube')).toEqual([apps[0]]);
    expect(searchTvApplications(apps, 'startv')).toEqual([apps[1]]);
  });
});
