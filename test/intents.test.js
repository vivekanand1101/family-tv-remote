import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { parseIntent } from "../src/intents.js";

const config = JSON.parse(await readFile(new URL("../config.json", import.meta.url), "utf8"));

test("opens Aaj Tak through the configured JioTV recipe", () => {
  const plan = parseIntent('open "aaj tak"', config);
  assert.equal(plan.summary, "Open aaj tak in jiotv");
  assert.equal(plan.actions[0].type, "app");
  assert.equal(plan.actions[2].key, "KEYCODE_SEARCH");
});

test("builds a YouTube search deep link", () => {
  const plan = parseIntent("play Arijit Singh on YouTube", config);
  assert.equal(plan.summary, "Search YouTube for arijit singh");
  assert.match(plan.actions[0].link, /search_query=arijit%20singh/);
});

test("recognizes basic remote commands", () => {
  assert.equal(parseIntent("volume up", config).actions[0].key, "KEYCODE_VOLUME_UP");
  assert.equal(parseIntent("go home", config).actions[0].key, "KEYCODE_HOME");
});

test("returns null for unknown speech", () => {
  assert.equal(parseIntent("make me some tea", config), null);
});
