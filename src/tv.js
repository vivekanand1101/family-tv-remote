import readline from "node:readline/promises";
import { readFile } from "node:fs/promises";
import { createAndroidRemote, RemoteKeyCode } from "@kud/androidtv-remote";
import { loadCertificate, saveCertificate } from "./config.js";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForPairingCode(filePath) {
  console.log(`Waiting for pairing code in ${filePath}...`);
  while (true) {
    try {
      const code = (await readFile(filePath, "utf8")).trim();
      if (code) return code;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    await sleep(250);
  }
}

export class TvRemote {
  constructor(config) {
    this.config = config;
    this.remote = null;
  }

  async connect() {
    const certificate = await loadCertificate();
    this.remote = createAndroidRemote(this.config.tv.host, {
      cert: certificate,
      service_name: "Audio TV Remote",
      manufacturer: "Apple",
      model: "Mac",
    });

    this.remote.on("error", (error) => console.error(`TV protocol error: ${error.message}`));

    const ready = new Promise((resolve, reject) => {
      const timeout = setTimeout(
        () => reject(new Error(`Timed out connecting to ${this.config.tv.host}`)),
        this.config.tv.connectTimeoutMs || 30000,
      );
      this.remote.once("ready", async () => {
        clearTimeout(timeout);
        await saveCertificate(this.remote.getCertificate());
        resolve();
      });
      this.remote.once("error", (error) => {
        clearTimeout(timeout);
        reject(error);
      });
    });

    this.remote.on("secret", async () => {
      let code;
      if (process.env.TV_PAIR_CODE_FILE) {
        code = await waitForPairingCode(process.env.TV_PAIR_CODE_FILE);
      } else {
        const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
        code = await rl.question("Enter the pairing code shown on the TV: ");
        rl.close();
      }
      if (!this.remote.sendCode(code.trim())) console.error("The pairing code was rejected.");
    });
    this.remote.on("current_app", (app) => console.log(`TV app: ${app}`));
    this.remote.on("unpaired", () => console.error("The TV revoked this remote. Delete the saved certificate and pair again."));

    await this.remote.start();
    await ready;
    return this;
  }

  async execute(plan) {
    if (!this.remote) throw new Error("TV is not connected");
    for (const action of plan.actions) {
      if (action.type === "wait") {
        await sleep(action.ms);
      } else if (action.type === "app") {
        this.remote.sendAppLink(action.link);
      } else if (action.type === "text") {
        this.remote.sendText(action.value);
      } else if (action.type === "key") {
        const keyCode = RemoteKeyCode[action.key];
        if (keyCode === undefined) throw new Error(`Unknown Android key: ${action.key}`);
        this.remote.sendKey(keyCode);
      } else {
        throw new Error(`Unknown action type: ${action.type}`);
      }
      if (action.type !== "wait") await sleep(action.afterMs ?? 250);
    }
  }

  stop() {
    this.remote?.stop();
  }
}
