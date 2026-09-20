package ndika.monitor;

import android.os.Bundle;
import ndika.monitor.IRemoteFpsCallback;

interface IRemoteFpsService {
    void registerCallback(IRemoteFpsCallback callback);
    void unregisterCallback(IRemoteFpsCallback callback);
    void updateConfig(in Bundle config);
    void startMonitoring();
    void stopMonitoring();
    boolean isRunning();
    void destroy();
}
