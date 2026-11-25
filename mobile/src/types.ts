export type RemoteCommand =
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'ok'
  | 'home'
  | 'back'
  | 'menu'
  | 'search'
  | 'allApps'
  | 'volumeUp'
  | 'volumeDown'
  | 'mute'
  | 'power'
  | 'wake'
  | 'playPause'
  | 'channelUp'
  | 'channelDown'
  | 'youtube'
  | 'hotstar'
  | 'netflix'
  | 'enter'
  | 'delete'
  | 'keyboardSearch'
  | 'hdmi1';

export type RemoteTarget = 'jio' | 'tv';

export type ChannelCategory = 'Sports' | 'Hindi News' | 'Entertainment';

export interface JioChannelTarget {
  name: string;
  channelNumber: number;
}

export interface JioCatalogueChannel extends JioChannelTarget {
  key: string;
  contentId: string | null;
  logo: string | null;
  genre: string;
  language: string;
  quality: string;
  vendor: string;
  premium: boolean;
}

export interface TvApplication {
  id: string;
  name: string;
  icon?: string;
}

export interface ChannelShortcut extends JioChannelTarget {
  id: string;
  name: string;
  shortName: string;
  contentId: string;
  channelNumber: number;
  provider: string;
  vendor: string;
  category: ChannelCategory;
  color: string;
}
