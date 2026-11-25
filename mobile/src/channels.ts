import type { ChannelShortcut } from './types';

export const channels: ChannelShortcut[] = [
  // English sports: SonyLIV and JioHotstar channels inside JioTV+.
  { id: 'sony-ten-1-hd', name: 'Sony Sports Ten 1 HD', shortName: 'TEN 1', contentId: '300256', channelNumber: 470, provider: 'SonyLIV', vendor: 'Sony', category: 'Sports', color: '#2962d9' },
  { id: 'sony-ten-2-hd', name: 'Sony Sports Ten 2 HD', shortName: 'TEN 2', contentId: '300273', channelNumber: 473, provider: 'SonyLIV', vendor: 'Sony', category: 'Sports', color: '#3478e5' },
  { id: 'sony-ten-5-hd', name: 'Sony Sports Ten 5 HD', shortName: 'TEN 5', contentId: '300258', channelNumber: 483, provider: 'SonyLIV', vendor: 'Sony', category: 'Sports', color: '#2451a6' },
  { id: 'star-sports-1-hd', name: 'Star Sports 1 HD', shortName: 'STAR 1', contentId: '301981', channelNumber: 454, provider: 'Hotstar', vendor: 'JioStar', category: 'Sports', color: '#1e81b0' },
  { id: 'star-sports-2-hd', name: 'Star Sports 2 HD', shortName: 'STAR 2', contentId: '301983', channelNumber: 456, provider: 'Hotstar', vendor: 'JioStar', category: 'Sports', color: '#176b91' },
  { id: 'star-select-1-hd', name: 'Star Sports Select 1 HD', shortName: 'S1', contentId: '302113', channelNumber: 463, provider: 'Hotstar', vendor: 'JioStar', category: 'Sports', color: '#13546f' },

  // Common Hindi news channels.
  { id: 'aaj-tak', name: 'Aaj Tak', shortName: 'AAJ', contentId: '301036', channelNumber: 509, provider: 'JioCinema', vendor: 'TV Today', category: 'Hindi News', color: '#d9232e' },
  { id: 'abp-news', name: 'ABP News', shortName: 'ABP', contentId: '300001', channelNumber: 504, provider: 'JioCinema', vendor: 'ABP', category: 'Hindi News', color: '#d8482e' },
  { id: 'news18-india', name: 'News18 India', shortName: '18', contentId: '300209', channelNumber: 519, provider: 'JioCinema', vendor: 'Network18', category: 'Hindi News', color: '#bd2735' },
  { id: 'india-tv', name: 'India TV', shortName: 'ITV', contentId: '300007', channelNumber: 514, provider: 'JioCinema', vendor: 'India TV', category: 'Hindi News', color: '#e06a28' },
  { id: 'zee-news', name: 'Zee News', shortName: 'ZEE', contentId: '301343', channelNumber: 511, provider: 'JioCinema', vendor: 'Zee News ZMCL', category: 'Hindi News', color: '#54369a' },
  { id: 'ndtv-india', name: 'NDTV India', shortName: 'NDTV', contentId: '301049', channelNumber: 506, provider: 'JioCinema', vendor: 'NDTV', category: 'Hindi News', color: '#9f2430' },
  { id: 'republic-bharat', name: 'Republic Bharat', shortName: 'R.', contentId: '301371', channelNumber: 1152, provider: 'JioCinema', vendor: 'Republic', category: 'Hindi News', color: '#e34835' },
  { id: 'news18-bihar-jharkhand', name: 'News18 Bihar Jharkhand', shortName: '18 BJ', contentId: '300284', channelNumber: 1129, provider: 'JioCinema', vendor: 'Network18', category: 'Hindi News', color: '#bd2735' },

  // General entertainment channels surfaced inside JioTV+.
  { id: 'and-tv-hd', name: '&TV HD', shortName: '&TV', contentId: '300377', channelNumber: 137, provider: 'Zee5', vendor: 'Zee Entertainment', category: 'Entertainment', color: '#df5435' },
  { id: 'zee-tv-hd', name: 'Zee TV HD', shortName: 'ZEE TV', contentId: '300378', channelNumber: 141, provider: 'Zee5', vendor: 'Zee Entertainment', category: 'Entertainment', color: '#7652bc' },
  { id: 'zee-cinema-hd', name: 'Zee Cinema HD', shortName: 'ZEE CINEMA', contentId: '300330', channelNumber: 319, provider: 'Zee5', vendor: 'Zee Entertainment', category: 'Entertainment', color: '#d79b1d' },
  { id: 'and-pictures-hd', name: '&Pictures HD', shortName: '&PIC', contentId: '300328', channelNumber: 331, provider: 'Zee5', vendor: 'Zee Entertainment', category: 'Entertainment', color: '#d74826' },
  { id: 'sony-entertainment-hd', name: 'Sony Entertainment Television HD', shortName: 'SONY', contentId: '300266', channelNumber: 128, provider: 'SonyLIV', vendor: 'Sony', category: 'Entertainment', color: '#3448a5' },
  { id: 'star-plus-hd', name: 'Star Plus HD', shortName: 'STAR+', contentId: '301998', channelNumber: 115, provider: 'Hotstar', vendor: 'JioStar', category: 'Entertainment', color: '#c72652' },
];
