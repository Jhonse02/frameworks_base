/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.am;

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.DEFAULT_DISPLAY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import org.mockito.invocation.InvocationOnMock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import com.android.server.AttributeCache;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.StackWindowController;
import com.android.server.wm.TaskWindowContainerController;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowTestUtils;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

/**
 * A base class to handle common operations in activity related unit tests.
 */
public class ActivityTestsBase {
    private final Context mContext = InstrumentationRegistry.getContext();
    private HandlerThread mHandlerThread;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandlerThread = new HandlerThread("ActivityTestsBaseThread");
        mHandlerThread.start();
    }

    @After
    public void tearDown() {
        mHandlerThread.quitSafely();
    }

    protected ActivityManagerService createActivityManagerService() {
        final ActivityManagerService service = spy(new TestActivityManagerService(mContext));
        service.mWindowManager = prepareMockWindowManager();
        return service;
    }

    protected static ActivityRecord createActivity(ActivityManagerService service,
            ComponentName component, TaskRecord task) {
        return createActivity(service, component, task, 0 /* userId */);
    }

    protected static ActivityRecord createActivity(ActivityManagerService service,
            ComponentName component, TaskRecord task, int uid) {
        Intent intent = new Intent();
        intent.setComponent(component);
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();
        aInfo.applicationInfo.uid = uid;
        AttributeCache.init(service.mContext);
        final ActivityRecord activity = new ActivityRecord(service, null /* caller */,
                0 /* launchedFromPid */, 0, null, intent, null,
                aInfo /*aInfo*/, new Configuration(), null /* resultTo */, null /* resultWho */,
                0 /* reqCode */, false /*componentSpecified*/, false /* rootVoiceInteraction */,
                service.mStackSupervisor, null /* options */, null /* sourceRecord */);
        activity.mWindowContainerController = mock(AppWindowContainerController.class);

        if (task != null) {
            task.addActivityToTop(activity);
        }

        return activity;
    }

    protected static TaskRecord createTask(ActivityStackSupervisor supervisor,
            ComponentName component, ActivityStack stack) {
        return createTask(supervisor, component, 0 /* flags */, 0 /* taskId */, stack);
    }

    protected static TaskRecord createTask(ActivityStackSupervisor supervisor,
            ComponentName component, int flags, int taskId, ActivityStack stack) {
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();

        Intent intent = new Intent();
        intent.setComponent(component);
        intent.setFlags(flags);

        final TaskRecord task = new TaskRecord(supervisor.mService, taskId, aInfo,
                intent /*intent*/, null /*_taskDescription*/);
        supervisor.setFocusStackUnchecked("test", stack);
        stack.addTask(task, true, "creating test task");
        task.setStack(stack);
        task.setWindowContainerController(mock(TaskWindowContainerController.class));

        return task;
    }

    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    protected static class TestActivityManagerService extends ActivityManagerService {
        TestActivityManagerService(Context context) {
            super(context);
            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;
            mWindowManager = WindowTestUtils.getMockWindowManagerService();
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            return new TestActivityStackSupervisor(this, mHandlerThread.getLooper());
        }

        @Override
        void updateUsageStats(ActivityRecord component, boolean resumed) {
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected static class TestActivityStackSupervisor extends ActivityStackSupervisor {
        private final ActivityDisplay mDisplay;
        private boolean mLastResizeable;

        public TestActivityStackSupervisor(ActivityManagerService service, Looper looper) {
            super(service, looper);
            mDisplayManager =
                    (DisplayManager) mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
            mWindowManager = prepareMockWindowManager();
            mDisplay = new TestActivityDisplay(this, DEFAULT_DISPLAY);
            attachDisplay(mDisplay);
        }

        @Override
        ActivityDisplay getDefaultDisplay() {
            return mDisplay;
        }

        // TODO: Use Mockito spy instead. Currently not possible due to TestActivityStackSupervisor
        // access to ActivityDisplay
        @Override
        boolean canPlaceEntityOnDisplay(int displayId, boolean resizeable, int callingPid,
                int callingUid, ActivityInfo activityInfo) {
            mLastResizeable = resizeable;
            return super.canPlaceEntityOnDisplay(displayId, resizeable, callingPid, callingUid,
                    activityInfo);
        }

        // TODO: remove and use Mockito verify once {@link #canPlaceEntityOnDisplay} override is
        // removed.
        public boolean getLastResizeableFromCanPlaceEntityOnDisplay() {
            return mLastResizeable;
        }

        // No home stack is set.
        @Override
        void moveHomeStackToFront(String reason) {
        }

        @Override
        boolean moveHomeStackTaskToTop(String reason) {
            return true;
        }

        // Invoked during {@link ActivityStack} creation.
        @Override
        void updateUIDsPresentOnDisplay() {
        }

        // Just return the current front task.
        @Override
        ActivityStack getNextFocusableStackLocked(ActivityStack currentFocus) {
            return mFocusedStack;
        }

        // Called when moving activity to pinned stack.
        @Override
        void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
                boolean preserveWindows) {
        }

        // Always keep things awake
        @Override
        boolean hasAwakeDisplay() {
            return true;
        }
    }

    private static class TestActivityDisplay extends ActivityDisplay {

        private final ActivityStackSupervisor mSupervisor;
        TestActivityDisplay(ActivityStackSupervisor supervisor, int displayId) {
            super(supervisor, displayId);
            mSupervisor = supervisor;
        }

        @Override
        <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
                int stackId, boolean onTop) {
            if (windowingMode == WINDOWING_MODE_PINNED) {
                return (T) new PinnedActivityStack(this, stackId, mSupervisor, onTop) {
                    @Override
                    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
                        return new Rect(50, 50, 100, 100);
                    }

                    @Override
                    PinnedStackWindowController createStackWindowController(int displayId,
                            boolean onTop, Rect outBounds) {
                        return mock(PinnedStackWindowController.class);
                    }
                };
            } else {
                return (T) new TestActivityStack(
                        this, stackId, mSupervisor, windowingMode, activityType, onTop);
            }
        }
    }

    private static WindowManagerService prepareMockWindowManager() {
        final WindowManagerService service = WindowTestUtils.getMockWindowManagerService();

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(service).inSurfaceTransaction(any());

        return service;
    }

    protected interface ActivityStackReporter {
        int onActivityRemovedFromStackInvocationCount();
    }

    /**
     * Overrided of {@link ActivityStack} that tracks test metrics, such as the number of times a
     * method is called. Note that its functionality depends on the implementations of the
     * construction arguments.
     */
    protected static class TestActivityStack<T extends StackWindowController>
            extends ActivityStack<T> implements ActivityStackReporter {
        private int mOnActivityRemovedFromStackCount = 0;
        private T mContainerController;

        TestActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor,
                int windowingMode, int activityType, boolean onTop) {
            super(display, stackId, supervisor, windowingMode, activityType, onTop);
        }

        @Override
        void onActivityRemovedFromStack(ActivityRecord r) {
            mOnActivityRemovedFromStackCount++;
            super.onActivityRemovedFromStack(r);
        }

        // Returns the number of times {@link #onActivityRemovedFromStack} has been called
        @Override
        public int onActivityRemovedFromStackInvocationCount() {
            return mOnActivityRemovedFromStackCount;
        }

        @Override
        protected T createStackWindowController(int displayId, boolean onTop, Rect outBounds) {
            mContainerController = (T) WindowTestUtils.createMockStackWindowContainerController();
            return mContainerController;
        }

        @Override
        T getWindowContainerController() {
            return mContainerController;
        }
    }
}
