import numpy as np
import pytest
from app.ecg_morphology import analyze, summarize_measurements


@pytest.fixture(scope='module')
def synthetic():
    import neurokit2 as nk
    return nk.ecg_simulate(duration=20, sampling_rate=250, heart_rate=72, noise=.01, random_state=42)


def test_missing_calibration_only_returns_sample_hypotheses(synthetic):
    result = analyze(synthetic, None)
    assert result['status'] == 'estimated'
    assert result['timeBasis'] == 'samples'
    assert result['markers']
    assert result['summary']['heartRateBpm'] is None
    assert result['summary']['qtMs'] is None
    assert result['summary']['qtcFridericiaMs'] is None
    assert result['summary']['qrsSamples']['median'] > 0
    assert all(b['durationMs'] is None and b['durationSamples'] == b['endSample']-b['startSample'] for b in result['intervals'])


@pytest.mark.parametrize('values,fs', [(np.ones(3000), 250), (np.ones(50), 250), (np.arange(1000), 50), (np.full(3000, np.nan), 250)])
def test_invalid_signals_withhold_measurements(values, fs):
    result = analyze(values, fs)
    assert result['status'] == 'unavailable'
    assert not result['markers']


def test_calibrated_synthetic_waveform_is_mapped_to_original_samples(synthetic):
    result = analyze(synthetic, 250, start=500, count=3000)
    assert result['status'] == 'estimated'
    assert 67 < result['summary']['heartRateBpm']['median'] < 77
    assert set(m['label'] for m in result['markers']) == set('PQRST')
    assert all(result['startSample'] <= m['sample'] < result['endSample'] for m in result['markers'])
    for interval in result['intervals']:
        assert interval['durationMs'] == pytest.approx((interval['endSample']-interval['startSample'])/250*1000)
    for beat in result['beats']:
        measurements = beat['measurements']
        if measurements['qtcFridericiaMs'] is not None:
            assert measurements['qtcFridericiaMs'] == pytest.approx(measurements['qtMs'] / (measurements['rrMs']/1000)**(1/3))
    assert result['quality']['completeBeats'] <= result['quality']['measuredBeats']


def test_resampling_maps_back_to_original_coordinates(synthetic):
    from scipy.signal import resample_poly
    high_rate = resample_poly(synthetic, 4, 1)
    low = analyze(synthetic, 250, start=500, count=3000)
    high = analyze(high_rate, 1000, start=2000, count=12000)
    assert high['status'] == 'estimated'
    low_r = [m['sample']/250 for m in low['markers'] if m['label']=='R']
    high_r = [m['sample']/1000 for m in high['markers'] if m['label']=='R']
    assert len(low_r) == len(high_r)
    np.testing.assert_allclose(low_r, high_r, atol=.025)


def test_analysis_is_bounded_and_reports_actual_tail_coverage(synthetic):
    result = analyze(synthetic, 250, start=4500, count=50000)
    assert result['endSample'] == len(synthetic)
    assert result['startSample'] < 4500
    assert result['endSample'] - result['startSample'] <= 7500


def test_summary_does_not_turn_missing_measurements_into_zero():
    result = summarize_measurements([{'measurements':{'qrsMs':100, 'qtMs':None}}, {'measurements':{'qrsMs':120, 'qtMs':None}}])
    assert result['qrsMs'] == {'median':110, 'min':100, 'max':120, 'count':2}
    assert result['qtMs'] is None


def test_uncalibrated_offset_and_no_time_leak(synthetic):
    values = np.tile(synthetic, 3)
    r = analyze(values, None, 7000, 5000)
    assert r['status'] == 'estimated'
    assert all(r['startSample'] <= m['sample'] < r['endSample'] for m in r['markers'])
    assert all(m['beatId'] in {b['id'] for b in r['beats']} for m in r['markers'])
    assert all(v is None for b in r['beats'] for k,v in b['measurements'].items() if k.endswith('Ms') or k == 'heartRateBpm')
