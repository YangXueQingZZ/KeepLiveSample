package mrkang.keeplivesample;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.youth.xframe.utils.XEmptyUtils;
import com.youth.xframe.utils.XNetworkUtils;
import com.youth.xframe.utils.XPreferencesUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public class TraceServiceImpl extends AbsWorkService {

    //是否 任务完成, 不再需要服务运行?
    public static boolean sShouldStopService;
    public static Disposable sDisposable;
    private NetWorkReceiver mNetWorkReceiver;
    private boolean isFirst = true;
    private Socket mSocket;
    private PrintWriter mOut;
    private boolean isInitReceiver = true;
    //3、获取输入流，并读取服务器端的响应信息
    private InputStream is = null;
    private BufferedReader br = null;
    private String info = null;
    private String token = null;
    private KeyguardManager mKeyguard;
    private KeyguardManager.KeyguardLock mKeylock;
    public static void stopService() {
        //我们现在不再需要服务运行了, 将标志位置为 true
        sShouldStopService = true;
        //取消对任务的订阅
        if (sDisposable != null) sDisposable.dispose();
        //取消 Job / Alarm / Subscription
        cancelJobAlarmSub();
    }

    /**
     * 是否 任务完成, 不再需要服务运行?
     *
     * @return 应当停止服务, true; 应当启动服务, false; 无法判断, 什么也不做, null.
     */
    @Override
    public Boolean shouldStopService(Intent intent, int flags, int startId) {
        return sShouldStopService;
    }

    @Override
    public void startWork(Intent intent, int flags, int startId) {

        mKeyguard = (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
        mKeylock = mKeyguard.newKeyguardLock("Charge");
        mKeylock.disableKeyguard();
        initReceiver();//初始化网络状态广播接收器
        System.out.println("检查磁盘中是否有上次销毁时保存的数据");
        sDisposable = Observable
                .interval(3, TimeUnit.SECONDS)
                //取消任务时取消定时唤醒
                .doOnDispose(() -> {
                    stopSocket();
                    System.out.println("断开Socket,取消心跳发送,并保存数据到磁盘。");
                    cancelJobAlarmSub();
                })
                .subscribe(count -> {
                    System.out.println("每 3 秒采集一次数据.并发送一次心跳.. count = " + count);
                    startSocket();
                    if (count > 0 && count % 18 == 0)
                        System.out.println("保存数据到磁盘。 saveCount = " + (count / 18 - 1));
                });
    }

    @Override
    public void stopWork(Intent intent, int flags, int startId) {
        stopService();
    }

    /**
     * 任务是否正在运行?
     *
     * @return 任务正在运行, true; 任务当前不在运行, false; 无法判断, 什么也不做, null.
     */
    @Override
    public Boolean isWorkRunning(Intent intent, int flags, int startId) {
        //若还没有取消订阅, 就说明任务仍在运行.
        return sDisposable != null && !sDisposable.isDisposed();
    }

    @Override
    public IBinder onBind(Intent intent, Void v) {
        return null;
    }

    @Override
    public void onServiceKilled(Intent rootIntent) {
        System.out.println("保存数据到磁盘。");
    }

    private void initReceiver() {
        if (mNetWorkReceiver == null) {
            mNetWorkReceiver = new NetWorkReceiver();
        }
        IntentFilter filter = new IntentFilter();
        IntentFilter mRLFilter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetWorkReceiver, filter);
        isInitReceiver = true;
    }

    private void startSocket() {
        acquireWakeLock();
        try {
            if (isFirst) {
                boolean isOnLine = XNetworkUtils.isAvailable();
                if (!isOnLine) {
                    return;
                }
                if (!XEmptyUtils.isEmpty(mSocket) && !mSocket.isClosed()) {
                    return;
                }
                mSocket = new Socket(Constants.TestSocketUrl, Constants.TestSocketPort);//mHost为服务器地址，mPort和服务器的端口号一样
                is = mSocket.getInputStream();
                br = new BufferedReader(new InputStreamReader(is));
                mOut = new PrintWriter(new BufferedWriter(new OutputStreamWriter(mSocket.getOutputStream())), true);//写入数据
                String userId = (String) XPreferencesUtils.get("USERID", "null");
                StringBuffer buffer = new StringBuffer("{'userid':'" + userId + "','tocken':'" + "000000000000000" + "','online':'1'}" + "\n");
                acquireWakeLock();
                mOut.write(buffer.toString());
                mOut.flush();
                Log.e("Socket", "初始化发送成功");
                isFirst = false;
                isInitReceiver = false;
            }
            //获取实时的登录状态
            boolean isOnLine = XNetworkUtils.isAvailable();
            if (!isOnLine) {
                //若无网络,则关闭Socket,并重新初始化.
                mSocket.close();
                mOut.close();
                br.close();
                is.close();
                return;
            }
            acquireWakeLock();
//                            mSocket.sendUrgentData(0xff);
            mOut.write("ok\n");
            mOut.flush();
            info = br.readLine();
            if (XEmptyUtils.isEmpty(info) || !info.equals("ok")) {
                mSocket.close();
                mOut.close();
                br.close();
                is.close();
                sDisposable.dispose();
                isFirst = true;
                startSocket();
                return;
            }
            info = null;
            Log.i("Socket" + mSocket.hashCode(), android.os.Process.myPid() + "线程发送");
        } catch (IOException e) {
            try {
                if(XEmptyUtils.isEmpty(mSocket)){
                    return;
                }
                mSocket.close();
                mOut.close();
                br.close();
                is.close();
                sDisposable.dispose();
                isFirst = true;
                return;
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
    }
    private void stopSocket(){
        try {
            releaseWakeLock();
            if (XEmptyUtils.isEmpty(mSocket)) {
                return;
            }
            boolean isClosed = mSocket.isClosed();
            if (!isClosed) {
                mSocket.close();
            } else {
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void reConnectSoc() {
        isFirst = true;
        if (sDisposable != null) {
            sDisposable.dispose();
        }
        boolean isLogout = (boolean) XPreferencesUtils.get("LOGOUT", true); //默认是已注销,未登录状态
        if (isLogout || isInitReceiver) {
            return;
        } else {
            startSocket();
            Log.e("ping后,重连Socket", "执行");
        }
    }

    private PowerManager.WakeLock mWakelock;

    /**
     * 在锁屏状态下唤醒休眠的cpu
     */
    private void acquireWakeLock() {
        if (mWakelock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lock");
        }
        mWakelock.acquire();
    }

    private void releaseWakeLock() {
        if (mWakelock != null && mWakelock.isHeld()) {
            mWakelock.release();
        }
        mWakelock = null;
    }

    public class NetWorkReceiver extends BroadcastReceiver {

        public NetWorkReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {

            Log.e("网络状态发生变化", "网络状态发生变化");
            //检测API是不是小于23，因为到了API23之后getNetworkInfo(int networkType)方法被弃用
            if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                //获得ConnectivityManager对象
                ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                //获取ConnectivityManager对象对应的NetworkInfo对象
                //获取WIFI连接的信息
                NetworkInfo wifiNetworkInfo = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                //获取移动数据连接的信息
                NetworkInfo dataNetworkInfo = connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
                if (XEmptyUtils.isEmpty(wifiNetworkInfo) || XEmptyUtils.isEmpty(dataNetworkInfo) || isInitReceiver) {
                    return;
                }
                if (wifiNetworkInfo.isConnected() && dataNetworkInfo.isConnected()) {
                    reConnectSoc();
                } else if (wifiNetworkInfo.isConnected() && !dataNetworkInfo.isConnected()) {
                    reConnectSoc();
                } else if (!wifiNetworkInfo.isConnected() && dataNetworkInfo.isConnected()) {
                    reConnectSoc();
                } else {
                    reConnectSoc();
                }
                //API大于23时使用下面的方式进行网络监听
            } else {
                //获得ConnectivityManager对象
                ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                //获取所有网络连接的信息
                Network[] networks = connMgr.getAllNetworks();
                //用于存放网络连接信息
//                StringBuilder sb = new StringBuilder();
                //通过循环将网络信息逐个取出来
                for (int i = 0; i < networks.length; i++) {
                    //获取ConnectivityManager对象对应的NetworkInfo对象
                    NetworkInfo networkInfo = connMgr.getNetworkInfo(networks[i]);
                    if (networkInfo.isConnected() && !isInitReceiver) {
                        reConnectSoc();
                    }
                }
            }
        }
    }
}
