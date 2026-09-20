package ndika.monitor;

import android.os.Bundle;

interface IRemoteFpsCallback {
    void onFrameData(in Bundle data);
    void onServiceStatusChanged(boolean isRunning);
}
