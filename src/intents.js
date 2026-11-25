const normalize = (value) => value
  .toLocaleLowerCase()
  .replace(/[“”‘’"]/g, "")
  .replace(/\s+/g, " ")
  .trim();

const keyPlan = (summary, key) => ({ summary, actions: [{ type: "key", key }] });

function launchAction(app) {
  return { type: "app", link: app.launch, afterMs: 3000 };
}

function findNamedEntry(input, entries) {
  return Object.entries(entries).find(([name, entry]) =>
    [name, ...(entry.aliases || [])].some((alias) => input.includes(normalize(alias))),
  );
}

export function parseIntent(transcript, config) {
  const input = normalize(transcript);
  if (!input) return null;

  const simpleCommands = [
    [/^(?:go )?home$/, keyPlan("Go home", "KEYCODE_HOME")],
    [/^(?:go )?back$/, keyPlan("Go back", "KEYCODE_BACK")],
    [/^(?:mute|unmute|toggle mute)$/, keyPlan("Toggle mute", "KEYCODE_MUTE")],
    [/^(?:volume up|increase volume|turn it up)$/, keyPlan("Volume up", "KEYCODE_VOLUME_UP")],
    [/^(?:volume down|decrease volume|turn it down)$/, keyPlan("Volume down", "KEYCODE_VOLUME_DOWN")],
    [/^(?:pause|play|play pause|resume)$/, keyPlan("Toggle playback", "KEYCODE_MEDIA_PLAY_PAUSE")],
    [/^(?:turn off|power off|switch off)(?: the)? tv$/, keyPlan("Toggle TV power", "KEYCODE_POWER")],
  ];
  const simple = simpleCommands.find(([pattern]) => pattern.test(input));
  if (simple) return simple[1];

  const channelMatch = findNamedEntry(input, config.channels);
  if (channelMatch && /\b(open|play|watch|show|put on|switch to)\b/.test(input)) {
    const [channelName, channel] = channelMatch;
    const app = config.apps[channel.app];
    if (!app) throw new Error(`Channel ${channelName} references unknown app ${channel.app}`);
    return {
      summary: `Open ${channelName} in ${channel.app}`,
      actions: [launchAction(app), ...(channel.actions || [])],
    };
  }

  const youtubeSearch = input.match(/^(?:play|search(?: for)?|open) (.+?) (?:on|in) youtube$/)
    || input.match(/^(?:open )?youtube (?:and )?(?:search(?: for)?|play) (.+)$/)
    || input.match(/^search youtube (?:for )?(.+)$/);
  if (youtubeSearch) {
    const query = youtubeSearch[1].trim();
    return {
      summary: `Search YouTube for ${query}`,
      actions: [{
        type: "app",
        link: `https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`,
        afterMs: 3000,
      }],
    };
  }

  const appMatch = findNamedEntry(input, config.apps);
  if (appMatch && /\b(open|launch|start)\b/.test(input)) {
    const [appName, app] = appMatch;
    return { summary: `Open ${appName}`, actions: [launchAction(app)] };
  }

  return null;
}

export { normalize };
