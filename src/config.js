import { readFile, mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const stateDir = path.join(os.homedir(), ".config", "audio-tv-remote");
export const certificatePath = path.join(stateDir, "certificate.json");

export async function loadConfig() {
  const raw = await readFile(path.join(projectRoot, "config.json"), "utf8");
  const config = JSON.parse(raw);
  config.tv.host = process.env.TV_HOST || config.tv.host;
  config.voice.audioDevice = process.env.TV_AUDIO_DEVICE || config.voice.audioDevice;
  return config;
}

export async function loadCertificate() {
  try {
    return JSON.parse(await readFile(certificatePath, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return undefined;
    throw error;
  }
}

export async function saveCertificate(certificate) {
  await mkdir(stateDir, { recursive: true, mode: 0o700 });
  await writeFile(certificatePath, `${JSON.stringify(certificate, null, 2)}\n`, {
    mode: 0o600,
  });
}
