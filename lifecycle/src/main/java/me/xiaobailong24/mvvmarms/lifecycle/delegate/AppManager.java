package me.xiaobailong24.mvvmarms.lifecycle.delegate;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.support.design.widget.Snackbar;
import android.view.View;

import org.simple.eventbus.EventBus;
import org.simple.eventbus.Subscriber;
import org.simple.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import me.xiaobailong24.mvvmarms.lifecycle.ConfigLifecycle;
import timber.log.Timber;

/**
 * @author xiaobailong24
 * @date 2017/6/16
 * 用于管理所有 activity,和在前台的 activity
 * 可以通过直接持有 AppManager 对象执行对应方法
 * 也可以通过 {@link #post(Message)} ,远程遥控执行对应方法,用法和 EventBus 类似
 */
@Singleton
public final class AppManager {
    protected final String TAG = this.getClass().getSimpleName();
    public static final String APPMANAGER_MESSAGE = "appmanager_message";
    /**
     * true 为不需要加入到 Activity 容器进行统一管理,反之亦然
     */
    public static final String IS_NOT_ADD_ACTIVITY_LIST = "is_not_add_activity_list";
    public static final int START_ACTIVITY = 5000;
    public static final int SHOW_SNACKBAR = 5001;
    public static final int KILL_ALL = 5002;
    public static final int APP_EXIT = 5003;
    private Application mApplication;
    /**
     * 管理所有activity
     */
    public List<Activity> mActivityList;
    /**
     * 当前在前台的activity
     */
    private Activity mCurrentActivity;
    /**
     * 提供给外部扩展 AppManager 的 onReceive 方法
     */
    private HandleListener mHandleListener;

    @Inject
    public AppManager(Application application) {
        this.mApplication = application;
        EventBus.getDefault().register(this);
    }


    /**
     * 通过 eventbus post 事件,远程遥控执行对应方法
     */
    @Subscriber(tag = APPMANAGER_MESSAGE, mode = ThreadMode.MAIN)
    public void onReceive(Message message) {
        switch (message.what) {
            case START_ACTIVITY:
                if (message.obj == null) {
                    break;
                }
                dispatchStart(message);
                break;
            case SHOW_SNACKBAR:
                if (message.obj == null) {
                    break;
                }
                showSnackbar((String) message.obj, message.arg1 != 0);
                break;
            case KILL_ALL:
                killAll();
                break;
            case APP_EXIT:
                appExit();
                break;
            default:
                Timber.tag(TAG).w("The message.what not match");
                break;
        }
        if (mHandleListener != null) {
            mHandleListener.handleMessage(this, message);
        }
    }

    private void dispatchStart(Message message) {
        if (message.obj instanceof Intent) {
            startActivity((Intent) message.obj);
        } else if (message.obj instanceof Class) {
            startActivity((Class) message.obj);
        }
    }


    public HandleListener getHandleListener() {
        return mHandleListener;
    }

    /**
     * 提供给外部扩展 AppManager 的 @{@link #onReceive} 方法(远程遥控 AppManager 的功能)
     * 建议在 {@link ConfigLifecycle#injectAppLifecycle(Context, List)} 中
     * 通过 {@link AppLifecycles#onCreate(Application)} 在 App 初始化时,使用此方法传入自定义的 {@link HandleListener}
     *
     * @param handleListener HandleListener
     */
    public void setHandleListener(HandleListener handleListener) {
        this.mHandleListener = handleListener;
    }

    /**
     * 通过此方法远程遥控 AppManager ,使 {@link #onReceive(Message)} 执行对应方法
     *
     * @param msg Message 消息
     */
    public static void post(Message msg) {
        EventBus.getDefault().post(msg, APPMANAGER_MESSAGE);
    }

    /**
     * 让在前台的 activity,使用 snackbar 显示文本内容
     *
     * @param message 文本内容
     * @param isLong  时间长短
     */
    public void showSnackbar(String message, boolean isLong) {
        if (getCurrentActivity() == null) {
            Timber.tag(TAG).w("mCurrentActivity == null when showSnackbar(String,boolean)");
            return;
        }
        View view = getCurrentActivity().getWindow().getDecorView().findViewById(android.R.id.content);
        Snackbar.make(view, message, isLong ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT).show();
    }


    /**
     * 让在栈顶的 activity ,打开指定的 activity
     *
     * @param intent Intent
     */
    public void startActivity(Intent intent) {
        if (getTopActivity() == null) {
            Timber.tag(TAG).w("mCurrentActivity == null when startActivity(Intent)");
            //如果没有前台的activity就使用new_task模式启动activity
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mApplication.startActivity(intent);
            return;
        }
        getTopActivity().startActivity(intent);
    }

    /**
     * 让在栈顶的 activity ,打开指定的 activity
     *
     * @param activityClass 目标 activity class
     */
    public void startActivity(Class activityClass) {
        startActivity(new Intent(mApplication, activityClass));
    }

    /**
     * 释放资源
     */
    public void release() {
        EventBus.getDefault().unregister(this);
        mActivityList.clear();
        mHandleListener = null;
        mActivityList = null;
        mCurrentActivity = null;
        mApplication = null;
    }

    /**
     * 将在前台的 activity 赋值给 currentActivity,注意此方法是在 onResume 方法执行时将栈顶的 activity 赋值给 currentActivity
     * 所以在栈顶的 activity 执行 onCreate 方法时使用 {@link #getCurrentActivity()} 获取的就不是当前栈顶的 activity,可能是上一个 activity
     * 如果在 App 的第一个 activity 执行 onCreate 方法时使用 {@link #getCurrentActivity()} 则会出现返回为 null 的情况
     * 想避免这种情况请使用 {@link #getTopActivity()}
     *
     * @param currentActivity 前台 Activity
     */
    public void setCurrentActivity(Activity currentActivity) {
        this.mCurrentActivity = currentActivity;
    }

    /**
     * 获取在前台的 activity (保证获取到的 activity 正处于可见状态,即未调用 onStop),获取的 activity 存续时间
     * 是在 onStop 之前,所以如果当此 activity 调用 onStop 方法之后,没有其他的 activity 回到前台(用户返回桌面或者打开了其他 App 会出现此状况)
     * 这时调用该方法有可能返回 null,所以请注意使用场景和 {@link #getTopActivity()} 不一样
     * <p>
     * Example usage:
     * 使用场景比较适合,只需要在可见状态的 activity 上执行的操作
     * 如当后台 service 执行某个任务时,需要让前台 activity ,做出某种响应操作或其他操作,如弹出 Dialog,这时在 service 中就可以使用该方法
     * 如果返回为 null ,说明没有前台 activity (用户返回桌面或者打开了其他 App 会出现此状况),则不做任何操作,不为 null ,则弹出 Dialog
     *
     * @return 前台 Activity
     */
    public Activity getCurrentActivity() {
        return mCurrentActivity != null ? mCurrentActivity : null;
    }

    /**
     * 获取位于栈顶的 activity,此方法不保证获取到的 activity 正处于可见状态,即使 App 进入后台也会返回当前栈顶的 activity
     * 因此基本不会出现 null 的情况,比较适合大部分的使用场景,如 startActivity,Glide 加载图片
     *
     * @return 栈顶 Activity
     */
    public Activity getTopActivity() {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when getTopActivity()");
            return null;
        }
        return mActivityList.size() > 0 ? mActivityList.get(mActivityList.size() - 1) : null;
    }


    /**
     * 返回一个存储所有未销毁的 activity 的集合
     *
     * @return 所有 Activity 列表
     */
    public List<Activity> getActivityList() {
        if (mActivityList == null) {
            mActivityList = new LinkedList<>();
        }
        return mActivityList;
    }


    /**
     * 添加 activity 到集合
     *
     * @param activity Activity
     */
    public void addActivity(Activity activity) {
        if (mActivityList == null) {
            mActivityList = new LinkedList<>();
        }
        synchronized (AppManager.class) {
            if (!mActivityList.contains(activity)) {
                mActivityList.add(activity);
            }
        }
    }

    /**
     * 删除集合里的指定的 activity 实例
     *
     * @param activity 目标 Activity
     */
    public void removeActivity(Activity activity) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when removeActivity(Activity)");
            return;
        }
        synchronized (AppManager.class) {
            if (mActivityList.contains(activity)) {
                mActivityList.remove(activity);
            }
        }
    }

    /**
     * 删除集合里的指定位置的 activity
     *
     * @param location 指定位置
     */
    public Activity removeActivity(int location) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when removeActivity(int)");
            return null;
        }
        synchronized (AppManager.class) {
            if (location > 0 && location < mActivityList.size()) {
                return mActivityList.remove(location);
            }
        }
        return null;
    }

    /**
     * 关闭指定的 activity class 的所有的实例
     *
     * @param activityClass activity class
     */
    public void killActivity(Class<?> activityClass) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when killActivity(Class)");
            return;
        }
        for (Activity activity : mActivityList) {
            if (activity.getClass().equals(activityClass)) {
                activity.finish();
            }
        }
    }


    /**
     * 指定的 activity 实例是否存活
     *
     * @param activity Activity
     * @return 是否存活
     */
    public boolean activityInstanceIsLive(Activity activity) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when activityInstanceIsLive(Activity)");
            return false;
        }
        return mActivityList.contains(activity);
    }


    /**
     * 指定的 activity class 是否存活(同一个 activity class 可能有多个实例)
     *
     * @param activityClass activity class
     * @return 是否存活
     */
    public boolean activityClassIsLive(Class<?> activityClass) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when activityClassIsLive(Class)");
            return false;
        }
        for (Activity activity : mActivityList) {
            if (activity.getClass().equals(activityClass)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 获取指定 activity class 的实例,没有则返回 null(同一个 activity class 有多个实例,则返回最早的实例)
     *
     * @param activityClass activity class
     * @return Activity
     */
    public Activity findActivity(Class<?> activityClass) {
        if (mActivityList == null) {
            Timber.tag(TAG).w("mActivityList == null when findActivity(Class)");
            return null;
        }
        for (Activity activity : mActivityList) {
            if (activity.getClass().equals(activityClass)) {
                return activity;
            }
        }
        return null;
    }


    /**
     * 关闭所有 activity
     */
    public void killAll() {
        Iterator<Activity> iterator = getActivityList().iterator();
        while (iterator.hasNext()) {
            Activity next = iterator.next();
            iterator.remove();
            next.finish();
        }
    }

    /**
     * 关闭所有 activity,排除指定的 activity
     *
     * @param excludeActivityClasses activity class
     */
    public void killAll(Class<?>... excludeActivityClasses) {
        List<Class<?>> excludeList = Arrays.asList(excludeActivityClasses);
        Iterator<Activity> iterator = getActivityList().iterator();
        while (iterator.hasNext()) {
            Activity next = iterator.next();

            if (excludeList.contains(next.getClass())) {
                continue;
            }

            iterator.remove();
            next.finish();
        }
    }

    /**
     * 关闭所有 activity,排除指定的 activity
     *
     * @param excludeActivityName activity 的完整全路径
     */
    public void killAll(String... excludeActivityName) {
        List<String> excludeList = Arrays.asList(excludeActivityName);
        Iterator<Activity> iterator = getActivityList().iterator();
        while (iterator.hasNext()) {
            Activity next = iterator.next();

            if (excludeList.contains(next.getClass().getName())) {
                continue;
            }

            iterator.remove();
            next.finish();
        }
    }


    /**
     * 退出应用程序
     */
    public void appExit() {
        try {
            killAll();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public interface HandleListener {
        /**
         * 扩展 AppManager 的远程遥控功能
         *
         * @param appManager AppManager
         * @param message    Message
         */
        void handleMessage(AppManager appManager, Message message);
    }
}
