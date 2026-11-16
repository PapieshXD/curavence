import io
import json
import zipfile
import numpy as np
import pytest
from app.ecg import preview


def test_headerless_csv_keeps_first_sample_and_never_invents_calibration():
    result = preview('record.csv', b'0.77849\n0.67543\n0.31717\n')
    c = result['channels'][0]
    assert c['samples'] == [0.77849, 0.67543, 0.31717]
    assert c['sampleRate'] is None and c['unit'] is None and c['durationSeconds'] is None
    assert 'samples' not in result['evidence']['channels'][0]


def test_time_units_decimal_comma_and_multiple_leads():
    result = preview('record.csv', b'time [ms];I [mV];II [mV]\n0;0,1;0,2\n2;0,3;0,4\n4;0,5;0,6')
    assert len(result['channels']) == 2
    assert result['channels'][0]['sampleRate'] == 500
    assert result['channels'][0]['samples'] == [.1, .3, .5]
    assert result['channels'][0]['unit'] == 'mV'


def test_missing_samples_become_gaps_and_are_counted():
    result = preview('record.csv', b'lead\n1\nNaN\n3\n')
    assert result['channels'][0]['samples'] == [1, None, 3]
    assert result['channels'][0]['statistics']['missingSamples'] == 1


def test_empty_rows_preserve_sample_positions():
    result = preview('record.csv', b'1\n\n3\n')
    assert result['channels'][0]['samples'] == [1, None, 3]


@pytest.mark.parametrize('format', ['PNG', 'TIFF', 'WEBP', 'BMP', 'GIF', 'JPEG'])
def test_raster_preview_never_invents_samples(format):
    from PIL import Image
    output = io.BytesIO()
    Image.new('RGB', (100, 50), 'white').save(output, format=format)
    result = preview('record.' + format.lower(), output.getvalue())
    assert result['channels'] == []
    assert result['imageDataUrl'].startswith('data:image/png;base64,')
    assert result['evidence']['kind'] == 'image'


def test_json_metadata_and_user_calibration():
    data = json.dumps({'fs': 250, 'unit': 'mV', 'channels': {'I': [1, 2, 3], 'II': [3, 2, 1]}}).encode()
    result = preview('record.json', data, 500, 'V')
    assert result['channels'][0]['sampleRate'] == 250
    assert result['channels'][0]['unit'] == 'mV'
    c = preview('record.csv', b'1\n2\n3', 500, 'uV')['channels'][0]
    assert c['calibrationSource'] == 'user' and c['sampleRate'] == 500


def test_explicit_xml_samples():
    result = preview('record.xml', b'<ecg><samplingRate>500</samplingRate><lead name="II" unit="mV"><samples>1 2 3</samples></lead></ecg>')
    assert result['channels'][0]['samples'] == [1, 2, 3]
    assert result['channels'][0]['sampleRate'] == 500


def test_mat():
    from scipy.io import savemat
    output = io.BytesIO()
    savemat(output, {'val': np.arange(100).reshape(2, 50), 'fs': 250})
    result = preview('record.mat', output.getvalue())
    assert len(result['channels']) == 2
    assert result['channels'][1]['samples'][0] == 50


def test_wfdb_zip(tmp_path):
    import wfdb
    wfdb.wrsamp('record', fs=250, units=['mV'], sig_name=['II'], p_signal=np.sin(np.arange(100)[:,None]), fmt=['16'], write_dir=str(tmp_path))
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as z:
        for path in tmp_path.iterdir():
            z.write(path, path.name)
    result = preview('record.zip', output.getvalue())
    assert result['channels'][0]['sampleCount'] == 100
    assert result['channels'][0]['sampleRate'] == 250
    np.testing.assert_allclose(result['channels'][0]['samples'], np.sin(np.arange(100)), atol=.001)


@pytest.mark.parametrize('extension', ['edf', 'bdf'])
def test_edf_bdf(tmp_path, extension):
    import pyedflib
    path = tmp_path / ('record.' + extension)
    kind = pyedflib.FILETYPE_EDFPLUS if extension == 'edf' else pyedflib.FILETYPE_BDFPLUS
    with pyedflib.EdfWriter(str(path), 1, file_type=kind) as writer:
        writer.setSignalHeader(0, dict(label='II', dimension='mV', sample_frequency=100, physical_min=-2, physical_max=2, digital_min=-32768, digital_max=32767, transducer='', prefilter=''))
        writer.writeSamples([np.sin(np.arange(100))])
    result = preview(path.name, path.read_bytes())
    assert result['channels'][0]['sampleRate'] == 100
    assert result['channels'][0]['unit'] == 'mV'


def test_dicom_waveform():
    from pydicom.dataset import FileDataset, FileMetaDataset, Dataset
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid
    meta = FileMetaDataset()
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    meta.MediaStorageSOPClassUID = '1.2.840.10008.5.1.4.1.1.9.1.1'
    meta.MediaStorageSOPInstanceUID = generate_uid()
    ds = FileDataset(None, {}, file_meta=meta, preamble=b'\0'*128)
    group = Dataset()
    group.NumberOfWaveformChannels = 1
    group.NumberOfWaveformSamples = 3
    group.SamplingFrequency = 500
    group.WaveformBitsAllocated = 16
    group.WaveformSampleInterpretation = 'SS'
    definition = Dataset()
    definition.ChannelLabel = 'II'
    definition.ChannelSensitivity = .01
    definition.ChannelSensitivityCorrectionFactor = 1
    definition.ChannelBaseline = 0
    units = Dataset()
    units.CodeValue = 'mV'
    definition.ChannelSensitivityUnitsSequence = [units]
    group.ChannelDefinitionSequence = [definition]
    group.WaveformData = np.array([10, 20, 30], dtype='<i2').tobytes()
    ds.WaveformSequence = [group]
    output = io.BytesIO()
    ds.save_as(output, enforce_file_format=True)
    result = preview('record.dcm', output.getvalue())
    np.testing.assert_allclose(result['channels'][0]['samples'], [.1, .2, .3])


def test_zip_rejects_traversal():
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as z:
        z.writestr('../outside.csv', '1\n2\n3')
    with pytest.raises(ValueError, match='ścieżkę'):
        preview('record.zip', output.getvalue())


@pytest.mark.parametrize('name,content', [('record.csv', b'1,2\n3'), ('record.dat', b'123'), ('record.scp', b'123'), ('record.csv', b'time [s],I\n0,1\n2,2\n1,3')])
def test_invalid_and_unsupported_inputs(name, content):
    with pytest.raises(ValueError):
        preview(name, content)


def test_xml_entities_are_not_expanded():
    with pytest.raises(Exception):
        preview('record.xml', b'<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><a>&x;</a>')


def test_display_filter_attenuates_interference_without_replacing_original():
    from app.ecg import display_signal
    t = np.arange(16000)
    clean = .2*np.sin(2*np.pi*t/256)
    noisy = clean + .5*np.sin(2*np.pi*50*t/256)
    original = noisy.copy()
    display, notes, window = display_signal(noisy, 256)
    assert notes and 100 <= window <= 10000
    assert np.std(display[1000:-1000] - clean[1000:-1000]) < .01
    np.testing.assert_array_equal(noisy, original)


def test_display_filter_preserves_clean_signal_and_gaps():
    from app.ecg import display_signal
    clean = np.sin(2*np.pi*np.arange(4096)/256)
    display, notes, _ = display_signal(clean, 256)
    np.testing.assert_array_equal(display, clean)
    assert not notes
    clean[100] = np.nan
    display, notes, _ = display_signal(clean, 256)
    np.testing.assert_array_equal(display, clean)
    assert not notes


def test_display_samples_are_excluded_from_ai_context():
    result = preview('record.csv', b'1\n2\n3\n')
    assert 'displaySamples' not in result['evidence']['channels'][0]


def test_header_metadata_fills_missing_calibration_and_reports_source():
    c = preview('record.txt', b'# fs=250\n# unit=mV\n1\n2\n3')['channels'][0]
    assert c['sampleRate'] == 250 and c['unit'] == 'mV'
    assert c['parameterSources']['samplingRate'] == 'header'


def test_conflicting_header_and_time_column_is_rejected():
    with pytest.raises(ValueError, match='sprzeczna'):
        preview('record.csv', b'# fs=500\ntime [s],I\n0,1\n0.004,2\n0.008,3')


def test_statistics_have_known_numeric_values():
    c = preview('record.csv', b'1\n2\n3')['channels'][0]
    assert c['statistics']['mean'] == 2
    assert c['statistics']['median'] == 2
    assert c['statistics']['peakToPeak'] == 2
    assert c['statistics']['rms'] == pytest.approx(np.sqrt(14/3))


def test_features_never_invent_hz_and_detect_known_peak_intervals():
    from app.ecg import detect_features
    t = np.arange(5000)
    samples = sum(np.exp(-.5*((t-center)/3)**2) for center in range(250,4751,250))
    c = detect_features(samples)
    assert c['medianSpacingSamples'] == 250
    assert c['peakRatePerMinute'] is None and c['medianSpacingMs'] is None
    calibrated = detect_features(samples, 250)
    assert calibrated['peakRatePerMinute'] == 60
    assert calibrated['medianSpacingMs'] == 1000


def test_full_record_is_available_beyond_old_preview_limit():
    count = 240010
    c = preview('record.csv', ('1\n'*count).encode())['channels'][0]
    assert len(c['samples']) == count
    assert c['sampleCount'] == c['displayedSamples']
