import io
import zipfile
import numpy as np
from app.ecg import archive

def test_twenty_wfdb_records_keep_calibration_and_natural_order():
    stream=io.BytesIO()
    values=np.tile(np.array([[200,400]],dtype='<i2'),(100,1)).tobytes()
    with zipfile.ZipFile(stream,'w') as z:
        for i in sorted(range(1,21),key=str):
            name=f'rec_{i}'
            z.writestr(f'Person_01/{name}.hea',f'{name} 2 500 100\n{name}.dat 16 200 12 0 200 20000 0 ECG I\n{name}.dat 16 200 12 0 400 -25536 0 ECG I filtered\n')
            z.writestr(f'Person_01/{name}.dat',values)
            z.writestr(f'Person_01/{name}.atr',b'')
    channels=archive(stream.getvalue())
    assert len(channels)==40
    assert channels[2]['name']=='rec_2 / ECG I'
    assert channels[-1]['name']=='rec_20 / ECG I filtered'
    assert all(c['fs']==500 and c['unit']=='mV' for c in channels)
    assert channels[0]['values'][0]==1
    assert channels[1]['values'][0]==2
