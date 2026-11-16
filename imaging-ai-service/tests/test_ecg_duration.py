import pytest
from app.ecg import table, structured, rate_from_duration

def test_duration_header_recovers_time_without_assuming_unit():
    c=table('# durationSeconds=2\n1\n2\n3\n4')[0]
    assert c['fs']==2 and c['fsSource']=='duration' and c['unit'] is None

def test_json_duration_is_repeatable():
    source={'samples':[1,2,3,4], 'durationSeconds':2,'unit':'mV'}
    for _ in range(3):
        c=structured(source)[0]
        assert c['fs']==2 and c['unit']=='mV'

def test_multichannel_duration():
    c=structured({'channels':[{'samples':[1,2,3,4]}],'durationSeconds':2})[0]
    assert c['fs']==2

@pytest.mark.parametrize('duration',[0,-1,float('nan'),float('inf')])
def test_invalid_duration(duration):
    with pytest.raises(ValueError): rate_from_duration(1000,duration)

def test_conflicting_duration_rejected():
    with pytest.raises(ValueError): table('# fs=250\n# durationSeconds=2\n1\n2\n3\n4')
