package org.droidplanner.android.maps.providers.amap;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;


import com.amap.api.location.AMapLocationClient;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles the lifecycle for the google api client. Also takes care of running submitted tasks
 * when the google api client is connected.
 */
//public class AMapClientManager implements  ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
public class AMapClientManager {
    private final static String TAG = AMapClientManager.class.getSimpleName();

    public interface ManagerListener {
//        void onGoogleApiConnectionError(ConnectionResult result);

        void onUnavailableGooglePlayServices(int status);

        void onManagerStarted();

        void onManagerStopped();
    }

    /**
     * Manager background thread used to run the submitted google api client tasks.
     */
    private final Runnable mDriverRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                while (isStarted.get()) {
                    if (!mLocationClient.isStarted()) {
                        stop();
                        continue;
                    }

                    final AMapClientTask task = mTaskQueue.take();
                    if (task == null)
                        continue;

                    if (task.mRunOnBackgroundThread) {
                        mBgHandler.post(task);
                    } else {
                        mMainHandler.post(task);
                    }
                }
            } catch (InterruptedException e) {
                Log.v(TAG, e.getMessage(), e);
            }
        }
    };

    private final AMapClientTask stopTask = new AMapClientTask() {
        @Override
        protected void doRun() {
            stop();
        }
    };

    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private Thread mDriverThread;

    /**
     * This handler is in charge of running google api client tasks on the calling thread.
     */
    private final Handler mMainHandler;

    /**
     * This handler is in charge of running google api client tasks on the background thread.
     */
    private Handler mBgHandler;
    private HandlerThread mBgHandlerThread;

    /**
     * Application context.
     */
    private final Context mContext;

    /**
     * Handle to the google api client.
     */
    private final AMapLocationClient mLocationClient;

    private ManagerListener listener;

    /**
     * Holds tasks that needs to be run using the google api client.
     * A background thread will be blocking on this queue until new tasks are inserted. In which
     * case, it will retrieve the new task, and process it.
     */
    private final LinkedBlockingQueue<AMapClientTask> mTaskQueue = new LinkedBlockingQueue<>();

    public AMapClientManager(Context context, Handler handler) {
        mContext = context;
        mMainHandler = handler;

        mLocationClient = new AMapLocationClient(context);
    }

    public void setManagerListener(ManagerListener listener) {
        this.listener = listener;
    }

    private void destroyBgHandler() {
        if (mBgHandlerThread != null && mBgHandlerThread.isAlive()) {
            mBgHandlerThread.quit();
            mBgHandlerThread.interrupt();
            mBgHandlerThread = null;
        }

        mBgHandler = null;
    }

    private void destroyDriverThread() {
        if (mDriverThread != null && mDriverThread.isAlive()) {
            mDriverThread.interrupt();
            mDriverThread = null;
        }
    }

    private void initializeBgHandler() {
        if (mBgHandlerThread == null || mBgHandlerThread.isInterrupted()) {
            mBgHandlerThread = new HandlerThread("GAC Manager Background Thread");
            mBgHandlerThread.start();
            mBgHandler = null;
        }

        if (mBgHandler == null) {
            mBgHandler = new Handler(mBgHandlerThread.getLooper());
        }
    }

    private void initializeDriverThread() {
        if (mDriverThread == null || mDriverThread.isInterrupted()) {
            mDriverThread = new Thread(mDriverRunnable, "GAC Manager Driver Thread");
            mDriverThread.start();
        }
    }

    /**
     * Adds a task to the google api client manager tasks queue. This task will be scheduled to
     * run on the calling thread.
     *
     * @param task task making use of the google api client.
     * @return true if the task was successfully added to the queue.
     * @throws IllegalStateException is the start() method was not called.
     */
    public boolean addTask(AMapClientTask task) {
        if (!isStarted()) {
            Log.d(TAG, "GoogleApiClientManager is not started.");
            return false;
        }

        task.aMapLocationClient = mLocationClient;
        task.taskQueue = mTaskQueue;
        task.mRunOnBackgroundThread = false;
        return mTaskQueue.offer(task);
    }

    /**
     * Adds a task to the google api client manager tasks queue. This task will be scheduled to
     * run on a background thread.
     *
     * @param task task making use of the google api client.
     * @return true if the task was successfully added to the queue.
     * @throws IllegalStateException is the start() method was not called.
     */
    public boolean addTaskToBackground(AMapClientTask task) {
        if (!isStarted()) {
            Log.d(TAG, "GoogleApiClientManager is not started.");
            return false;
        }

        task.aMapLocationClient = mLocationClient;
        task.taskQueue = mTaskQueue;
        task.mRunOnBackgroundThread = true;
        return mTaskQueue.offer(task);
    }

    /**
     * @return true the google api client manager was started.
     */
    private boolean isStarted() {
        return isStarted.get();
    }

    /**
     * Activates the google api client manager.
     */
    public void start() {
        //Check if google play services is available.
        final boolean isValid = true;

        if (isValid) {
            //Clear the queue
            mTaskQueue.clear();

            //Toggle the started flag
            isStarted.set(true);
            if (mLocationClient.isStarted()) {
                onConnected(null);
            } else if (!mLocationClient.isStarted()) {
                //Connect to the google api.
                mLocationClient.startLocation();
            }
        } else {
            Log.e(TAG, "Google Play Services is unavailable.");
//            if (listener != null)
//                listener.onUnavailableGooglePlayServices(playStatus);
        }
    }

//    private boolean isGooglePlayServicesValid(){
//        // Check for the google play services is available
//
//        if(!isValid){
//            PendingIntent errorPI = GooglePlayServicesUtil.getErrorPendingIntent(playStatus, mContext, 0);
//            if(errorPI != null){
//                try {
//                    errorPI.send();
//                } catch (PendingIntent.CanceledException e) {
//                    Log.e(TAG, "Seems the pending intent was cancelled.", e);
//                }
//            }
//        }
//
//        return isValid;
//    }

    /**
     * Release the resources used by this manager.
     * After calling this method, start() needs to be called again to use that manager again.
     */
    private void stop() {
        isStarted.set(false);
        destroyDriverThread();
        destroyBgHandler();

        mTaskQueue.clear();
        if (mLocationClient.isStarted()) {
            mLocationClient.stopLocation();
        }

        if(listener != null)
            listener.onManagerStopped();
    }

    public void stopSafely(){
        addTask(stopTask);
    }

//    @Override
    public void onConnected(Bundle bundle) {
        initializeBgHandler();
        initializeDriverThread();

        if (listener != null)
            listener.onManagerStarted();
    }

//    @Override
//    public void onConnectionSuspended(int i) {
//
//    }
//
//    @Override
//    public void onConnectionFailed(ConnectionResult connectionResult) {
//        if(listener != null)
//            listener.onGoogleApiConnectionError(connectionResult);
//
//        stop();
//    }

    /**
     * Type for the google api client tasks.
     */
    public static abstract class AMapClientTask implements Runnable {

        /**
         * If true, this task will be scheduled to run on a background thread.
         * Otherwise, it will run on the calling thread.
         */
        private boolean mRunOnBackgroundThread = false;
        private AMapLocationClient aMapLocationClient;
        private LinkedBlockingQueue<AMapClientTask> taskQueue;

        protected AMapLocationClient getAMapLocationClient() {
            return aMapLocationClient;
        }

        @Override
        public void run() {
            if (!aMapLocationClient.isStarted()) {
                //Add the task back to the queue.
                taskQueue.offer(this);
                return;
            }

            //Run the task
            doRun();
        }

        protected abstract void doRun();

    }
}
