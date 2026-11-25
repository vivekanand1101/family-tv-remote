import { afterEach, describe, expect, it, vi } from 'vitest';
import { startPressRepeat } from '../src/pressRepeat';

describe('press-and-hold repeat', () => {
  afterEach(() => vi.useRealTimers());

  it('runs immediately, repeats after the hold delay, and stops on release', () => {
    vi.useFakeTimers();
    const action = vi.fn();
    const stop = startPressRepeat(action, 400, 140);

    expect(action).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(399);
    expect(action).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(141);
    expect(action).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(280);
    expect(action).toHaveBeenCalledTimes(4);

    stop();
    vi.advanceTimersByTime(1_000);
    expect(action).toHaveBeenCalledTimes(4);
  });
});
