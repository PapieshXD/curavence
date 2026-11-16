import { waveformPath, sampleStatistics } from './ecg-waveform';

describe('EKG rendering', () => {
  it('calculates window statistics from actual valid values', () => {
    const stats = sampleStatistics([1, 2, null, 3]);
    expect(stats.count).toBe(3);
    expect(stats.missing).toBe(1);
    expect(stats.mean).toBe(2);
    expect(stats.peakToPeak).toBe(2);
    expect(stats.rms).toBeCloseTo(Math.sqrt(14/3));
    expect(stats.standardDeviation).toBeCloseTo(Math.sqrt(2/3));
  });
  it('reports absent statistics instead of zeros for missing windows', () => {
    expect(sampleStatistics([null, null]).mean).toBeNull();
    expect(sampleStatistics([]).min).toBeNull();
  });
  it('preserves isolated spikes when reducing samples', () => {
    const samples = Array(10000).fill(0);
    samples[5123] = 10;
    const path = waveformPath(samples, 0, 10);
    expect(path).toContain(',18.00');
    expect(path).toContain(',154.00');
  });
  it('does not connect across missing samples', () => {
    const path = waveformPath([0, 1, null, 2, 3], 0, 3);
    expect(path.match(/M/g)?.length).toBe(2);
    expect(path).not.toContain('NaN');
  });
  it('handles constant signals without invalid coordinates', () => {
    expect(waveformPath([1, 1, 1], 1, 1)).not.toContain('NaN');
  });
});
