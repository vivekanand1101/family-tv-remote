import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";
import { readFile, unlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import readline from "node:readline/promises";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";

const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function waitForExit(child) {
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolve() : reject(new Error(`ffmpeg exited with code ${code}`)));
  });
}

export async function recordPushToTalk(audioDevice) {
  const output = path.join(os.tmpdir(), `audio-tv-remote-${randomUUID()}.wav`);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  await rl.question("Press Enter to start recording...");

  const ffmpeg = spawn("ffmpeg", [
    "-hide_banner", "-loglevel", "error",
    "-f", "avfoundation", "-i", `:${audioDevice}`,
    "-ac", "1", "-ar", "16000", "-y", output,
  ], { stdio: ["pipe", "ignore", "inherit"] });

  await new Promise((resolve) => setTimeout(resolve, 350));
  await rl.question("Listening... press Enter when done. ");
  ffmpeg.stdin.write("q\n");
  ffmpeg.stdin.end();
  await waitForExit(ffmpeg);
  rl.close();
  return output;
}

async function transcribeWithMacOS(audioPath, voiceConfig) {
  const binary = path.join(
    projectRoot,
    "bin",
    "Audio TV Remote Transcriber.app",
    "Contents",
    "MacOS",
    "macos-transcribe",
  );
  const { stdout } = await execFileAsync(binary, [audioPath, voiceConfig.language || "en-IN"], {
    timeout: 50000,
  });
  return stdout.trim();
}

async function transcribeWithOpenAI(audioPath, voiceConfig) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) throw new Error("OPENAI_API_KEY is not set");

  const audio = await readFile(audioPath);
  const form = new FormData();
  form.append("file", new Blob([audio], { type: "audio/wav" }), path.basename(audioPath));
  form.append("model", voiceConfig.model || "gpt-4o-mini-transcribe");
  if (voiceConfig.language) form.append("language", voiceConfig.language.split("-")[0]);

  const response = await fetch("https://api.openai.com/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  });
  if (!response.ok) throw new Error(`Transcription failed (${response.status}): ${await response.text()}`);
  const result = await response.json();
  return result.text?.trim() || "";
}

export async function transcribe(audioPath, voiceConfig) {
  try {
    if (voiceConfig.provider === "openai") return await transcribeWithOpenAI(audioPath, voiceConfig);
    if (process.platform !== "darwin") throw new Error("The macOS speech provider only works on macOS");
    return await transcribeWithMacOS(audioPath, voiceConfig);
  } finally {
    await unlink(audioPath).catch(() => {});
  }
}
