package mrkang.keeplivesample;

import com.youth.xframe.XFrame;
import com.youth.xframe.base.XApplication;

public class App extends XApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        //初始化日志
        XFrame.initXLog();
        //需要在 Application 的 onCreate() 中调用一次 DaemonEnv.initialize()
        DaemonEnv.initialize(this, TraceServiceImpl.class, DaemonEnv.DEFAULT_WAKE_UP_INTERVAL);
        TraceServiceImpl.sShouldStopService = false;
        DaemonEnv.startServiceMayBind(TraceServiceImpl.class);
    }
}
