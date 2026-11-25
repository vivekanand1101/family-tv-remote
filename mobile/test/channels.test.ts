import { existsSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { channels } from '../src/channels';

describe('curated JioTV+ shortcuts', () => {
  it('contains exactly 20 unique channels', () => {
    expect(channels).toHaveLength(20);
    expect(new Set(channels.map((channel) => channel.id)).size).toBe(20);
    expect(new Set(channels.map((channel) => channel.contentId)).size).toBe(20);
  });

  it('vendors a local logo for every shortcut', () => {
    for (const channel of channels) {
      expect(existsSync(`public/channel-logos/${channel.id}.jpg`), channel.id).toBe(true);
    }
  });

  it('contains the requested groups', () => {
    expect(channels.filter((channel) => channel.category === 'Sports')).toHaveLength(6);
    expect(channels.filter((channel) => channel.category === 'Hindi News')).toHaveLength(8);
    expect(channels.filter((channel) => channel.category === 'Entertainment')).toHaveLength(6);
  });

  it('matches the Jio guide channel numbers and content IDs', () => {
    const expected = {
      'sony-ten-1-hd': [470, '300256'],
      'sony-ten-2-hd': [473, '300273'],
      'sony-ten-5-hd': [483, '300258'],
      'star-sports-1-hd': [454, '301981'],
      'star-sports-2-hd': [456, '301983'],
      'star-select-1-hd': [463, '302113'],
      'aaj-tak': [509, '301036'],
      'abp-news': [504, '300001'],
      'news18-india': [519, '300209'],
      'india-tv': [514, '300007'],
      'zee-news': [511, '301343'],
      'ndtv-india': [506, '301049'],
      'republic-bharat': [1152, '301371'],
      'news18-bihar-jharkhand': [1129, '300284'],
      'and-tv-hd': [137, '300377'],
      'zee-tv-hd': [141, '300378'],
      'zee-cinema-hd': [319, '300330'],
      'and-pictures-hd': [331, '300328'],
      'sony-entertainment-hd': [128, '300266'],
      'star-plus-hd': [115, '301998'],
    } as const;

    expect(Object.fromEntries(
      channels.map(({ id, channelNumber, contentId }) => [id, [channelNumber, contentId]]),
    )).toEqual(expected);
  });

  it('includes Aaj Tak and the selected English sports channels', () => {
    expect(channels.some((channel) => channel.name === 'Aaj Tak')).toBe(true);
    expect(channels.filter((channel) => channel.category === 'Sports').map((channel) => channel.name)).toEqual([
      'Sony Sports Ten 1 HD',
      'Sony Sports Ten 2 HD',
      'Sony Sports Ten 5 HD',
      'Star Sports 1 HD',
      'Star Sports 2 HD',
      'Star Sports Select 1 HD',
    ]);
  });
});
