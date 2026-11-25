#!/usr/bin/env node
import readline from "node:readline/promises";
import { loadConfig, certificatePath } from "./config.js";
import { parseIntent } from "./intents.js";
import { TvRemote } from "./tv.js";
import { recordPushToTalk, transcribe } from "./voice.js";

function usage() {
  console.log(`Audio TV Remote

Usage:
  npm run pair
  npm run command -- "open aaj tak"
  npm run command -- "play lo-fi music on youtube"
  npm run voice
  npm start

Interactive commands:
  /voice   record one spoken command
  /quit    exit

Saved pairing certificate: ${certificatePath}`);
}

async function executeText(text, remote, config) {
  const plan = parseIntent(text, config);
  if (!plan) {
    console.log(`I did not understand: "${text}"`);
    return false;
  }
  console.log(`→ ${plan.summary}`);
  await remote.execute(plan);
  return true;
}

async function listenOnce(remote, config) {
  const audioPath = await recordPushToTalk(config.voice.audioDevice);
  console.log("Transcribing...");
  const transcript = await transcribe(audioPath, config.voice);
  console.log(`You said: ${transcript}`);
  return executeText(transcript, remote, config);
}

async function main() {
  const config = await loadConfig();
  const [mode = "help", ...args] = process.argv.slice(2);
  if (["help", "--help", "-h"].includes(mode)) return usage();

  const remote = await new TvRemote(config).connect();
  console.log(`Connected to ${config.tv.name} (${config.tv.host})`);

  try {
    if (mode === "pair") return;
    if (mode === "command") {
      if (!args.length) throw new Error("Pass a command, for example: npm run command -- \"open aaj tak\"");
      await executeText(args.join(" "), remote, config);
      return;
    }
    if (mode === "voice") {
      await listenOnce(remote, config);
      return;
    }
    if (mode !== "interactive") throw new Error(`Unknown mode: ${mode}`);

    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    console.log("Type a command, /voice, or /quit.");
    while (true) {
      const input = (await rl.question("tv> ")).trim();
      if (!input) continue;
      if (input === "/quit") break;
      if (input === "/voice") await listenOnce(remote, config);
      else await executeText(input, remote, config);
    }
    rl.close();
  } finally {
    remote.stop();
  }
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(`Error: ${error.message}`);
    process.exit(1);
  });
