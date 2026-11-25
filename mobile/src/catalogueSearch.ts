import type { JioCatalogueChannel, TvApplication } from './types';

function normalize(value: string): string {
  return value
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/&/g, ' and ')
    .toLocaleLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function numericQuery(query: string): { kind: 'channel' | 'id' | 'either'; value: string } | null {
  const channel = query.match(/^(?:ch|channel)\s*[:#-]?\s*(\d+)$/i);
  if (channel) return { kind: 'channel', value: channel[1] };
  const id = query.match(/^(?:id|content)\s*[:#-]?\s*(\d+)$/i);
  if (id) return { kind: 'id', value: id[1] };
  return /^\d+$/.test(query.trim()) ? { kind: 'either', value: query.trim() } : null;
}

export function searchJioChannels(
  channels: JioCatalogueChannel[],
  query: string,
): JioCatalogueChannel[] {
  const raw = query.trim();
  if (!raw) return channels;
  const numeric = numericQuery(raw);
  const normalizedQuery = normalize(raw);
  const tokens = normalizedQuery.split(' ').filter(Boolean);

  return channels
    .map((channel) => {
      const channelNumber = String(channel.channelNumber);
      const contentId = channel.contentId ?? '';
      let score = Number.POSITIVE_INFINITY;
      if (numeric) {
        if (numeric.kind !== 'id' && channelNumber === numeric.value) score = 0;
        else if (numeric.kind !== 'channel' && contentId === numeric.value) score = 1;
        else if (numeric.kind !== 'id' && channelNumber.startsWith(numeric.value)) score = 10;
        else if (numeric.kind !== 'channel' && contentId.startsWith(numeric.value)) score = 11;
      } else {
        const name = normalize(channel.name);
        const metadata = normalize(`${channel.vendor} ${channel.genre} ${channel.language} ${channel.quality}`);
        if (name === normalizedQuery) score = 0;
        else if (name.startsWith(normalizedQuery)) score = 10;
        else if (tokens.every((token) => name.includes(token))) score = 20;
        else if (tokens.every((token) => `${name} ${metadata}`.includes(token))) score = 30;
        else if (name.includes(normalizedQuery)) score = 40;
      }
      return { channel, score };
    })
    .filter((result) => Number.isFinite(result.score))
    .sort((left, right) => left.score - right.score
      || left.channel.channelNumber - right.channel.channelNumber
      || left.channel.name.localeCompare(right.channel.name))
    .map((result) => result.channel);
}

export function searchTvApplications(apps: TvApplication[], query: string): TvApplication[] {
  const normalizedQuery = normalize(query);
  if (!normalizedQuery) return apps;
  const tokens = normalizedQuery.split(' ').filter(Boolean);
  return apps
    .map((app) => {
      const name = normalize(app.name);
      const id = normalize(app.id);
      const score = name === normalizedQuery ? 0
        : name.startsWith(normalizedQuery) ? 10
          : tokens.every((token) => name.includes(token)) ? 20
            : tokens.every((token) => `${name} ${id}`.includes(token)) ? 30
              : Number.POSITIVE_INFINITY;
      return { app, score };
    })
    .filter((result) => Number.isFinite(result.score))
    .sort((left, right) => left.score - right.score || left.app.name.localeCompare(right.app.name))
    .map((result) => result.app);
}
