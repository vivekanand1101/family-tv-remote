export function startPressRepeat(
  action: () => void,
  initialDelayMs = 400,
  repeatIntervalMs = 140,
): () => void {
  let interval: ReturnType<typeof setInterval> | null = null;
  const delay = setTimeout(() => {
    interval = setInterval(action, repeatIntervalMs);
  }, initialDelayMs);

  action();

  return () => {
    clearTimeout(delay);
    if (interval) clearInterval(interval);
  };
}
