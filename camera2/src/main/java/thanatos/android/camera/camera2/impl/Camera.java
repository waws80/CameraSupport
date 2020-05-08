/*
 * Copyright 2019 The Android Open Source Project
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
 * limitations under the License.
 */

package thanatos.android.camera.camera2.impl;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import thanatos.android.camera.core.BaseCamera;
import thanatos.android.camera.core.CameraControlInternal;
import thanatos.android.camera.core.CameraDeviceStateCallbacks;
import thanatos.android.camera.core.CameraInfo;
import thanatos.android.camera.core.CameraInfoUnavailableException;
import thanatos.android.camera.core.CameraX;
import thanatos.android.camera.core.CaptureConfig;
import thanatos.android.camera.core.DeferrableSurface;
import thanatos.android.camera.core.ImmediateSurface;
import thanatos.android.camera.core.Observable;
import thanatos.android.camera.core.Preview;
import thanatos.android.camera.core.SessionConfig;
import thanatos.android.camera.core.UseCase;
import thanatos.android.camera.core.UseCaseAttachState;
import thanatos.android.camera.core.external.Preconditions;
import thanatos.android.camera.core.external.futures.CallbackToFutureAdapter;
import thanatos.android.camera.core.impl.LiveDataObservable;
import thanatos.android.camera.core.impl.utils.executor.CameraXExecutors;
import thanatos.android.camera.core.impl.utils.futures.FutureCallback;
import thanatos.android.camera.core.impl.utils.futures.Futures;

/**
 * A camera which is controlled by the change of state in use cases.
 *
 * <p>The camera needs to be in an open state in order for use cases to control the camera. Whenever
 * there is a non-zero number of use cases in the online state the camera will either have a capture
 * session open or be in the process of opening up one. If the number of uses cases in the online
 * state changes then the capture session will be reconfigured.
 *
 * <p>Capture requests will be issued only for use cases which are in both the online and active
 * state.
 */
final class Camera implements BaseCamera {
    private static final String TAG = "Camera";
    private static final int ERROR_NONE = 0;

    private final Object mAttachedUseCaseLock = new Object();

    /** Map of the use cases to the information on their state. */
    @GuardedBy("mAttachedUseCaseLock")
    private final UseCaseAttachState mUseCaseAttachState;

    /** The identifier for the {@link CameraDevice} */
    private final String mCameraId;

    /** Handle to the camera service. */
    private final CameraManager mCameraManager;

    private final Object mCameraInfoLock = new Object();
    /** The handler for camera callbacks and use case state management calls. */

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final Handler mHandler;
    private final Executor mExecutor;

    /**
     * State variable for tracking state of the camera.
     *
     * <p>Is volatile because it is initialized in the instance initializer which is not necessarily
     * called on the same thread as any of the other methods and callbacks.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    volatile InternalState mState = InternalState.INITIALIZED;
    private final LiveDataObservable<State> mObservableState =
            new LiveDataObservable<>();
    /** The camera control shared across all use cases bound to this Camera. */
    private final Camera2CameraControl mCameraControlInternal;
    private final StateCallback mStateCallback = new StateCallback();
    /** Information about the characteristics of this camera */
    // Nullable because this is lazily instantiated
    @GuardedBy("mCameraInfoLock")
    @Nullable
    private CameraInfo mCameraInfo;
    /** The handle to the opened camera. */
    @Nullable
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    CameraDevice mCameraDevice;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    int mCameraDeviceError = ERROR_NONE;
    /** The configured session which handles issuing capture requests. */
    private CaptureSession mCaptureSession;
    /** The session configuration of camera control. */
    private SessionConfig mCameraControlSessionConfig = SessionConfig.defaultEmptySessionConfig();

    private final Object mPendingLock = new Object();
    @GuardedBy("mPendingLock")
    private final List<UseCase> mPendingForAddOnline = new ArrayList<>();

    // Used to debug number of requests to release camera
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final AtomicInteger mReleaseRequestCount = new AtomicInteger(0);
    // Should only be accessed on handler thread
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
            ListenableFuture<Void> mUserReleaseFuture;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    CallbackToFutureAdapter.Completer<Void> mUserReleaseNotifier;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final Map<CaptureSession, ListenableFuture<Void>> mReleasedCaptureSessions = new HashMap<>();

    private final Observable<Integer> mAvailableCamerasObservable;
    private final Observable.Observer<Integer> mAvailableCamerasObserver;
    /**
     * Tracks the number of cameras available for opening.
     *
     * <p>If there are no cameras available to open, the camera will wait until there is at least
     * 1 camera available before opening a CameraDevice.
     *
     * <p>This number should be updated by mAvailableCamerasObserver.
     */
    @SuppressWarnings("WeakerAccess")
    int mNumAvailableCameras = 0;

    /**
     * Constructor for a camera.
     *
     * @param cameraManager the camera service used to retrieve a camera
     * @param cameraId      the name of the camera as defined by the camera service
     * @param availableCamerasObservable An observable updated with the current number of cameras
     *                                   that are available to be opened on the device.
     * @param handler       the handler for the thread on which all camera operations run
     */
    Camera(CameraManager cameraManager, String cameraId,
           @NonNull Observable<Integer> availableCamerasObservable, Handler handler) {
        mCameraManager = cameraManager;
        mCameraId = cameraId;
        mAvailableCamerasObserver = new AvailableCamerasObserver();
        mAvailableCamerasObservable = availableCamerasObservable;
        mHandler = handler;
        ScheduledExecutorService executorScheduler = CameraXExecutors.newHandlerExecutor(mHandler);
        mExecutor = executorScheduler;
        mUseCaseAttachState = new UseCaseAttachState(cameraId);
        mObservableState.postValue(State.CLOSED);

        try {
            CameraCharacteristics cameraCharacteristics =
                    mCameraManager.getCameraCharacteristics(mCameraId);
            mCameraControlInternal = new Camera2CameraControl(cameraCharacteristics,
                    this, executorScheduler, executorScheduler);
        } catch (CameraAccessException e) {
            throw new IllegalStateException("Cannot access camera", e);
        }

        mCaptureSession = new CaptureSession(mExecutor);

        // Register an observer to update the number of available cameras
        mAvailableCamerasObservable.addObserver(mExecutor, mAvailableCamerasObserver);
    }

    /**
     * Open the camera asynchronously.
     *
     * <p>Once the camera has been opened use case state transitions can be used to control the
     * camera pipeline.
     */
    @Override
    public void open() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.open();
                }
            });
            return;
        }

        switch (mState) {
            case INITIALIZED:
                openCameraDevice();
                break;
            case CLOSING:
                setState(InternalState.REOPENING);
                // If session close has not yet completed, then the camera is still open. We
                // can move directly back into an OPENED state.
                // If session close is already complete, then the camera is closing. We'll reopen
                // the camera in the camera state callback.
                // If the camera device is currently in an error state, we need to close the
                // camera before reopening, so we cannot directly reopen.
                if (!isSessionCloseComplete() && mCameraDeviceError == ERROR_NONE) {
                    Preconditions.checkState(mCameraDevice != null,
                            "Camera Device should be open if session close is not complete");
                    setState(InternalState.OPENED);
                    openCaptureSession();
                }
                break;
            default:
                Log.d(TAG, "open() ignored due to being in state: " + mState);
        }
    }

    /**
     * Close the camera asynchronously.
     *
     * <p>Once the camera is closed the camera will no longer produce data. The camera must be
     * reopened for it to produce data again.
     */
    @Override
    public void close() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.close();
                }
            });
            return;
        }

        Log.d(TAG, "Closing camera: " + mCameraId);
        switch (mState) {
            case OPENED:
                setState(InternalState.CLOSING);
                closeCamera(/*abortInFlightCaptures=*/false);
                break;
            case OPENING:
            case REOPENING:
                setState(InternalState.CLOSING);
                break;
            case PENDING_OPEN:
                // We should be able to transition directly to an initialized state since the
                // camera is not yet opening.
                Preconditions.checkState(mCameraDevice == null);
                setState(InternalState.INITIALIZED);
            default:
                Log.d(TAG, "close() ignored due to being in state: " + mState);
        }
    }

    @WorkerThread
    private void configAndClose() {
        // Configure the camera with a dummy capture session in order to clear the
        // previous session. This should be released immediately after being configured.
        final CaptureSession dummySession = new CaptureSession(mExecutor);

        final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        final Surface surface = new Surface(surfaceTexture);
        final Runnable closeAndCleanupRunner = new Runnable() {
            @Override
            public void run() {
                surface.release();
                surfaceTexture.release();
            }
        };

        SessionConfig.Builder builder = new SessionConfig.Builder();
        builder.addNonRepeatingSurface(new ImmediateSurface(surface));
        builder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
        try {
            Log.d(TAG, "Start configAndClose.");
            dummySession.open(builder.build(), mCameraDevice);

            // Don't need to abort captures since there are none submitted for this session.
            ListenableFuture<Void> releaseFuture = releaseSession(
                    dummySession, /*abortInFlightCaptures=*/false);

            // Add a listener to clear the dummy surfaces
            releaseFuture.addListener(closeAndCleanupRunner,
                    CameraXExecutors.directExecutor());

        } catch (CameraAccessException e) {
            Log.d(TAG, "Unable to configure camera " + mCameraId + " due to "
                    + e.getMessage());
            closeAndCleanupRunner.run();
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    boolean isSessionCloseComplete() {
        return mReleasedCaptureSessions.isEmpty();
    }

    // This will notify futures of completion.
    // Should only be called once the camera device is actually closed.
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void finishClose() {
        Preconditions.checkState(mState == InternalState.RELEASING
                || mState == InternalState.CLOSING);
        Preconditions.checkState(mReleasedCaptureSessions.isEmpty());

        mCameraDevice = null;
        if (mState == InternalState.CLOSING) {
            setState(InternalState.INITIALIZED);
        } else {
            setState(InternalState.RELEASED);

            // After a camera is released, it cannot be reopened, so we don't need to listen for
            // available camera changes.
            mAvailableCamerasObservable.removeObserver(mAvailableCamerasObserver);

            if (mUserReleaseNotifier != null) {
                mUserReleaseNotifier.set(null);
                mUserReleaseNotifier = null;
            }
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @WorkerThread
    void closeCamera(boolean abortInFlightCaptures) {
        Preconditions.checkState(mState == InternalState.CLOSING
                        || mState == InternalState.RELEASING
                        || (mState == InternalState.REOPENING && mCameraDeviceError != ERROR_NONE),
                "closeCamera should only be called in a CLOSING, RELEASING or REOPENING (with "
                        + "error) state. Current state: "
                        + mState + " (error: " + getErrorMessage(mCameraDeviceError) + ")");

        boolean isLegacyDevice = false;
        try {
            Camera2CameraInfo camera2CameraInfo = (Camera2CameraInfo) getCameraInfo();
            isLegacyDevice = camera2CameraInfo.getSupportedHardwareLevel()
                    == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        } catch (CameraInfoUnavailableException e) {
            Log.w(TAG, "Check legacy device failed.", e);
        }

        // TODO: Check if any sessions have been previously configured. We can probably skip
        // configAndClose if there haven't been any sessions configured yet.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < 29
                && isLegacyDevice
                && mCameraDeviceError == ERROR_NONE) { // Cannot open session on device in error
            // To configure surface again before close camera. This step would
            // disconnect previous connected surface in some legacy device to prevent exception.
            configAndClose();
        }

        // Release the current session and replace with a new uninitialized session in case the
        // camera enters a REOPENING state during session closing.
        resetCaptureSession(abortInFlightCaptures);
    }

    @WorkerThread
    private ListenableFuture<Void> releaseSession(@NonNull final CaptureSession captureSession,
            boolean abortInFlightCaptures) {
        captureSession.close();
        ListenableFuture<Void> releaseFuture = captureSession.release(abortInFlightCaptures);

        mReleasedCaptureSessions.put(captureSession, releaseFuture);

        // Add a callback to clear the future and notify if the camera and all capture sessions
        // are released
        Futures.addCallback(releaseFuture, new FutureCallback<Void>() {
            @WorkerThread
            @Override
            public void onSuccess(@Nullable Void result) {
                mReleasedCaptureSessions.remove(captureSession);
                switch (mState) {
                    case REOPENING:
                        if (mCameraDeviceError == ERROR_NONE) {
                            // When reopening, don't close the camera if there is no error.
                            break;
                        }
                        // Fall through if the camera device is in error. It needs to be closed.
                    case CLOSING:
                    case RELEASING:
                        if (isSessionCloseComplete() && mCameraDevice != null) {
                            mCameraDevice.close();
                            mCameraDevice = null;
                        }
                        break;
                    default:
                        // Ignore all other states
                }
            }

            @Override
            public void onFailure(Throwable t) {
                // Don't reset the internal release future as we want to keep track of the error
                // TODO: The camera should be put into an error state at this point
            }
            // Should always be called on the same handler thread, so directExecutor is OK here.
        }, CameraXExecutors.directExecutor());

        return releaseFuture;
    }

    /**
     * Release the camera.
     *
     * <p>Once the camera is released it is permanently closed. A new instance must be created to
     * access the camera.
     */
    @NonNull
    @Override
    public ListenableFuture<Void> release() {
        ListenableFuture<Void> releaseFuture = CallbackToFutureAdapter.getFuture(
                new CallbackToFutureAdapter.Resolver<Void>() {
                    @Override
                    public Object attachCompleter(
                            @NonNull final CallbackToFutureAdapter.Completer<Void> completer) {

                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Futures.propagate(getOrCreateUserReleaseFuture(), completer);
                            }
                        });
                        return "Release[request=" + mReleaseRequestCount.getAndIncrement() + "]";
                    }
                });

        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.releaseInternal();
                }
            });
        } else {
            releaseInternal();
        }

        return releaseFuture;
    }

    @NonNull
    @Override
    public Observable<State> getCameraState() {
        return mObservableState;
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @WorkerThread
    void releaseInternal() {
        switch (mState) {
            case INITIALIZED:
            case PENDING_OPEN:
                Preconditions.checkState(mCameraDevice == null);
                setState(InternalState.RELEASING);
                Preconditions.checkState(isSessionCloseComplete());
                finishClose();
                break;
            case OPENED:
                setState(InternalState.RELEASING);
                closeCamera(/*abortInFlightCaptures=*/true);
                break;
            case OPENING:
            case CLOSING:
            case REOPENING:
            case RELEASING:
                // Wait for the camera async callback to finish releasing
                setState(InternalState.RELEASING);
                break;
            default:
                Log.d(TAG, "release() ignored due to being in state: " + mState);
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @WorkerThread
    ListenableFuture<Void> getOrCreateUserReleaseFuture() {
        if (mUserReleaseFuture == null) {
            if (mState != InternalState.RELEASED) {
                mUserReleaseFuture = CallbackToFutureAdapter.getFuture(
                        new CallbackToFutureAdapter.Resolver<Void>() {
                            @Override
                            public Object attachCompleter(
                                    @NonNull CallbackToFutureAdapter.Completer<Void> completer) {
                                Preconditions.checkState(mUserReleaseNotifier == null,
                                        "Camera can only be released once, so release completer "
                                                + "should be null on creation.");
                                mUserReleaseNotifier = completer;
                                return "Release[camera=" + Camera.this + "]";
                            }
                        });
            } else {
                // Set to an immediately successful future if already in the released state.
                mUserReleaseFuture = Futures.immediateFuture(null);
            }
        }

        return mUserReleaseFuture;
    }

    /**
     * Sets the use case in a state to issue capture requests.
     *
     * <p>The use case must also be online in order for it to issue capture requests.
     */
    @Override
    public void onUseCaseActive(final UseCase useCase) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.onUseCaseActive(useCase);
                }
            });
            return;
        }

        Log.d(TAG, "Use case " + useCase + " ACTIVE for camera " + mCameraId);

        synchronized (mAttachedUseCaseLock) {
            reattachUseCaseSurfaces(useCase);
            mUseCaseAttachState.setUseCaseActive(useCase);
        }
        updateCaptureSessionConfig();
    }

    /** Removes the use case from a state of issuing capture requests. */
    @Override
    public void onUseCaseInactive(final UseCase useCase) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.onUseCaseInactive(useCase);
                }
            });
            return;
        }

        Log.d(TAG, "Use case " + useCase + " INACTIVE for camera " + mCameraId);
        synchronized (mAttachedUseCaseLock) {
            mUseCaseAttachState.setUseCaseInactive(useCase);
        }

        updateCaptureSessionConfig();
    }

    /** Updates the capture requests based on the latest settings. */
    @Override
    public void onUseCaseUpdated(final UseCase useCase) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.onUseCaseUpdated(useCase);
                }
            });
            return;
        }

        Log.d(TAG, "Use case " + useCase + " UPDATED for camera " + mCameraId);
        synchronized (mAttachedUseCaseLock) {
            reattachUseCaseSurfaces(useCase);
            mUseCaseAttachState.updateUseCase(useCase);
        }

        updateCaptureSessionConfig();
    }

    @Override
    public void onUseCaseReset(@NonNull final UseCase useCase) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.onUseCaseReset(useCase);
                }
            });
            return;
        }

        Log.d(TAG, "Use case " + useCase + " RESET for camera " + mCameraId);
        synchronized (mAttachedUseCaseLock) {
            reattachUseCaseSurfaces(useCase);
            mUseCaseAttachState.updateUseCase(useCase);
        }

        resetCaptureSession(/*abortInFlightCaptures=*/false);
        updateCaptureSessionConfig();
        openCaptureSession();
    }

    // Re-attaches use case's surfaces if surfaces are changed when use case is online.
    @GuardedBy("mAttachedUseCaseLock")
    private void reattachUseCaseSurfaces(UseCase useCase) {
        // if use case is offline, then DeferrableSurface attaching will happen when the use
        // case is addOnlineUsecase()'d.   So here we don't need to do the attaching.
        if (!isUseCaseOnline(useCase)) {
            return;
        }
        SessionConfig sessionConfig = mUseCaseAttachState.getUseCaseSessionConfig(useCase);
        SessionConfig newSessionConfig = useCase.getSessionConfig(mCameraId);

        List<DeferrableSurface> currentSurfaces = sessionConfig.getSurfaces();
        List<DeferrableSurface> newSurfaces = newSessionConfig.getSurfaces();

        // New added DeferrableSurfaces need to be attached.
        for (DeferrableSurface newSurface : newSurfaces) {
            if (!currentSurfaces.contains(newSurface)) {
                newSurface.notifySurfaceAttached();
            }
        }

        // Removed DeferrableSurfaces need to be detached.
        for (DeferrableSurface currentSurface : currentSurfaces) {
            if (!newSurfaces.contains(currentSurface)) {
                currentSurface.notifySurfaceDetached();
            }
        }
    }

    private void notifyAttachToUseCaseSurfaces(UseCase useCase) {
        for (DeferrableSurface surface : useCase.getSessionConfig(
                mCameraId).getSurfaces()) {
            surface.notifySurfaceAttached();
        }
    }

    private void notifyDetachFromUseCaseSurfaces(UseCase useCase) {
        for (DeferrableSurface surface : useCase.getSessionConfig(
                mCameraId).getSurfaces()) {
            surface.notifySurfaceDetached();
        }
    }

    public boolean isUseCaseOnline(UseCase useCase) {
        synchronized (mAttachedUseCaseLock) {
            return mUseCaseAttachState.isUseCaseOnline(useCase);
        }
    }

    /**
     * Sets the use case to be in the state where the capture session will be configured to handle
     * capture requests from the use case.
     */
    @Override
    public void addOnlineUseCase(@NonNull final Collection<UseCase> useCases) {
        if (useCases.isEmpty()) {
            return;
        }

        // Attaches the surfaces of use case to the Camera (prevent from surface abandon crash)
        // addOnlineUseCase could be called with duplicate use case, so we need to filter out
        // use cases that are either pending for addOnline or are already online.
        // It's ok for two thread to run here, since it‘ll do nothing if use case is already
        // pending.
        synchronized (mPendingLock) {
            for (UseCase useCase : useCases) {
                boolean isOnline = isUseCaseOnline(useCase);
                if (mPendingForAddOnline.contains(useCase) || isOnline) {
                    continue;
                }

                notifyAttachToUseCaseSurfaces(useCase);
                mPendingForAddOnline.add(useCase);
            }
        }

        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.addOnlineUseCase(useCases);
                }
            });
            return;
        }

        Log.d(TAG, "Use cases " + useCases + " ONLINE for camera " + mCameraId);
        synchronized (mAttachedUseCaseLock) {
            for (UseCase useCase : useCases) {
                mUseCaseAttachState.setUseCaseOnline(useCase);
            }
        }

        synchronized (mPendingLock) {
            mPendingForAddOnline.removeAll(useCases);
        }

        updateCaptureSessionConfig();
        resetCaptureSession(/*abortInFlightCaptures=*/false);

        if (mState == InternalState.OPENED) {
            openCaptureSession();
        } else {
            open();
        }

        updateCameraControlPreviewAspectRatio(useCases);
    }


    private void updateCameraControlPreviewAspectRatio(Collection<UseCase> useCases) {
        for (UseCase useCase : useCases) {
            if (useCase instanceof Preview) {
                Size resolutoin = useCase.getAttachedSurfaceResolution(mCameraId);
                Rational aspectRatio = new Rational(resolutoin.getWidth(), resolutoin.getHeight());
                mCameraControlInternal.setPreviewAspectRatio(aspectRatio);
                return;
            }
        }
    }

    private void clearCameraControlPreviewAspectRatio(Collection<UseCase> useCases) {
        for (UseCase useCase : useCases) {
            if (useCase instanceof Preview) {
                mCameraControlInternal.setPreviewAspectRatio(null);
                return;
            }
        }
    }

    /**
     * Removes the use case to be in the state where the capture session will be configured to
     * handle capture requests from the use case.
     */
    @Override
    public void removeOnlineUseCase(@NonNull final Collection<UseCase> useCases) {
        if (useCases.isEmpty()) {
            return;
        }

        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.removeOnlineUseCase(useCases);
                }
            });
            return;
        }

        Log.d(TAG, "Use cases " + useCases + " OFFLINE for camera " + mCameraId);
        synchronized (mAttachedUseCaseLock) {
            List<UseCase> toDetach = new ArrayList<>();
            for (UseCase useCase : useCases) {
                if (mUseCaseAttachState.isUseCaseOnline(useCase)) {
                    toDetach.add(useCase);
                }
                mUseCaseAttachState.setUseCaseOffline(useCase);
            }

            for (UseCase detach : toDetach) {
                notifyDetachFromUseCaseSurfaces(detach);
            }

            if (mUseCaseAttachState.getOnlineUseCases().isEmpty()) {
                resetCaptureSession(/*abortInFlightCaptures=*/true);
                close();
                return;
            }
        }

        updateCaptureSessionConfig();
        resetCaptureSession(/*abortInFlightCaptures=*/false);

        if (mState == InternalState.OPENED) {
            openCaptureSession();
        }

        clearCameraControlPreviewAspectRatio(useCases);
    }

    /** Returns an interface to retrieve characteristics of the camera. */
    @NonNull
    @Override
    public CameraInfo getCameraInfo() throws CameraInfoUnavailableException {
        synchronized (mCameraInfoLock) {
            if (mCameraInfo == null) {
                // Lazily instantiate camera info
                mCameraInfo = new Camera2CameraInfo(mCameraManager, mCameraId);
            }

            return mCameraInfo;
        }
    }

    /** Opens the camera device */
    // TODO(b/124268878): Handle SecurityException and require permission in manifest.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void openCameraDevice() {
        // Check that we have an available camera to open here before attempting
        // to open the camera again.
        if (mNumAvailableCameras <= 0) {
            Log.d(TAG, "No cameras available. Waiting for available camera before opening camera: "
                    + mCameraId);
            setState(InternalState.PENDING_OPEN);
            return;
        } else {
            setState(InternalState.OPENING);
        }

        Log.d(TAG, "Opening camera: " + mCameraId);

        try {
            mCameraManager.openCamera(mCameraId, createDeviceStateCallback(), mHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to open camera " + mCameraId + " due to " + e.getMessage());
            setState(InternalState.INITIALIZED);
        }
    }

    /** Updates the capture request configuration for the current capture session. */
    private void updateCaptureSessionConfig() {
        SessionConfig.ValidatingBuilder validatingBuilder;
        synchronized (mAttachedUseCaseLock) {
            validatingBuilder = mUseCaseAttachState.getActiveAndOnlineBuilder();
        }

        if (validatingBuilder.isValid()) {
            // Apply CameraControlInternal's SessionConfig to let CameraControlInternal be able
            // to control Repeating Request and process results.
            validatingBuilder.add(mCameraControlSessionConfig);

            SessionConfig sessionConfig = validatingBuilder.build();
            mCaptureSession.setSessionConfig(sessionConfig);
        }
    }

    /**
     * Opens a new capture session.
     *
     * <p>The previously opened session will be safely disposed of before the new session opened.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void openCaptureSession() {
        Preconditions.checkState(mState == InternalState.OPENED);

        SessionConfig.ValidatingBuilder validatingBuilder;
        synchronized (mAttachedUseCaseLock) {
            validatingBuilder = mUseCaseAttachState.getOnlineBuilder();
        }
        if (!validatingBuilder.isValid()) {
            Log.d(TAG, "Unable to create capture session due to conflicting configurations");
            return;
        }

        try {
            mCaptureSession.open(validatingBuilder.build(), mCameraDevice);
        } catch (CameraAccessException e) {
            Log.d(TAG, "Unable to configure camera " + mCameraId + " due to " + e.getMessage());
        }
    }

    /**
     * Replaces the old session with a new session initialized with the old session's configuration.
     *
     * <p>This does not close the previous session. The previous session should be
     * explicitly released before calling this method so the camera can track the state of
     * closing that session.
     */
    @WorkerThread
    private void resetCaptureSession(boolean abortInFlightCaptures) {
        Preconditions.checkState(mCaptureSession != null);
        Log.d(TAG, "Resetting Capture Session");
        CaptureSession oldCaptureSession = mCaptureSession;
        // Recreate an initialized (but not opened) capture session from the previous configuration
        SessionConfig previousSessionConfig = oldCaptureSession.getSessionConfig();
        List<CaptureConfig> unissuedCaptureConfigs = oldCaptureSession.getCaptureConfigs();
        mCaptureSession = new CaptureSession(mExecutor);
        mCaptureSession.setSessionConfig(previousSessionConfig);
        mCaptureSession.issueCaptureRequests(unissuedCaptureConfigs);

        releaseSession(oldCaptureSession, /*abortInFlightCaptures=*/abortInFlightCaptures);
    }

    private CameraDevice.StateCallback createDeviceStateCallback() {
        synchronized (mAttachedUseCaseLock) {
            SessionConfig config = mUseCaseAttachState.getOnlineBuilder().build();

            List<CameraDevice.StateCallback> configuredStateCallbacks =
                    config.getDeviceStateCallbacks();
            List<CameraDevice.StateCallback> allStateCallbacks =
                    new ArrayList<>(configuredStateCallbacks);
            allStateCallbacks.add(mStateCallback);
            return CameraDeviceStateCallbacks.createComboCallback(allStateCallbacks);
        }
    }

    /**
     * If the {@link CaptureConfig.Builder} hasn't had a surface attached, attaches all valid
     * repeating surfaces to it.
     *
     * @param captureConfigBuilder the configuration builder to attach repeating surfaces.
     * @return true if repeating surfaces have been successfully attached, otherwise false.
     */
    private boolean checkAndAttachRepeatingSurface(CaptureConfig.Builder captureConfigBuilder) {
        if (!captureConfigBuilder.getSurfaces().isEmpty()) {
            Log.w(TAG, "The capture config builder already has surface inside.");
            return false;
        }

        Collection<UseCase> activeUseCases;
        synchronized (mAttachedUseCaseLock) {
            activeUseCases = mUseCaseAttachState.getActiveAndOnlineUseCases();
        }

        for (UseCase useCase : activeUseCases) {
            SessionConfig sessionConfig = useCase.getSessionConfig(mCameraId);
            // Query the repeating surfaces attached to this use case, then add them to the builder.
            List<DeferrableSurface> surfaces =
                    sessionConfig.getRepeatingCaptureConfig().getSurfaces();
            if (!surfaces.isEmpty()) {
                for (DeferrableSurface surface : surfaces) {
                    captureConfigBuilder.addSurface(surface);
                }
            }
        }

        if (captureConfigBuilder.getSurfaces().isEmpty()) {
            Log.w(TAG, "Unable to find a repeating surface to attach to CaptureConfig");
            return false;
        }

        return true;
    }

    /** Returns the Camera2CameraControl attached to Camera */
    @NonNull
    @Override
    public CameraControlInternal getCameraControlInternal() {
        return mCameraControlInternal;
    }

    /**
     * Submits capture requests
     *
     * @param captureConfigs capture configuration used for creating CaptureRequest
     * @hide
     */
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    void submitCaptureRequests(final List<CaptureConfig> captureConfigs) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Camera.this.submitCaptureRequests(captureConfigs);
                }
            });
            return;
        }

        List<CaptureConfig> captureConfigsWithSurface = new ArrayList<>();
        for (CaptureConfig captureConfig : captureConfigs) {
            // Recreates the Builder to add extra config needed
            CaptureConfig.Builder builder = CaptureConfig.Builder.from(captureConfig);

            if (captureConfig.getSurfaces().isEmpty() && captureConfig.isUseRepeatingSurface()) {
                // Checks and attaches repeating surface to the request if there's no surface
                // has been already attached. If there's no valid repeating surface to be
                // attached, skip this capture request.
                if (!checkAndAttachRepeatingSurface(builder)) {
                    continue;
                }
            }
            captureConfigsWithSurface.add(builder.build());
        }

        Log.d(TAG, "issue capture request for camera " + mCameraId);

        mCaptureSession.issueCaptureRequests(captureConfigsWithSurface);
    }

    /** {@inheritDoc} */
    @Override
    public void onCameraControlUpdateSessionConfig(@NonNull SessionConfig sessionConfig) {
        mCameraControlSessionConfig = sessionConfig;
        updateCaptureSessionConfig();
    }

    /** {@inheritDoc} */
    @Override
    public void onCameraControlCaptureRequests(@NonNull List<CaptureConfig> captureConfigs) {
        submitCaptureRequests(captureConfigs);
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US, "Camera@%x[id=%s]", hashCode(), mCameraId);
    }

    enum InternalState {
        /**
         * Stable state once the camera has been constructed.
         *
         * <p>At this state the {@link CameraDevice} should be invalid, but threads should be still
         * in a valid state. Whenever a camera device is fully closed the camera should return to
         * this state.
         *
         * <p>After an error occurs the camera returns to this state so that the device can be
         * cleanly reopened.
         */
        INITIALIZED,
        /**
         * Camera is waiting for the camera to be available to open.
         *
         * <p>A camera may enter a pending state if the camera has been stolen by another process
         * or if the maximum number of available cameras is already open.
         *
         * <p>At the end of this state, the camera should move into the OPENING state.
         */
        PENDING_OPEN,
        /**
         * A transitional state where the camera device is currently opening.
         *
         * <p>At the end of this state, the camera should move into either the OPENED or CLOSING
         * state.
         */
        OPENING,
        /**
         * A stable state where the camera has been opened.
         *
         * <p>During this state the camera device should be valid. It is at this time a valid
         * capture session can be active. Capture requests should be issued during this state only.
         */
        OPENED,
        /**
         * A transitional state where the camera device is currently closing.
         *
         * <p>At the end of this state, the camera should move into the INITIALIZED state.
         */
        CLOSING,
        /**
         * A transitional state where the camera was previously closing, but not fully closed before
         * a call to open was made.
         *
         * <p>At the end of this state, the camera should move into one of two states. The OPENING
         * state if the device becomes fully closed, since it must restart the process of opening a
         * camera. The OPENED state if the device becomes opened, which can occur if a call to close
         * had been done during the OPENING state.
         */
        REOPENING,
        /**
         * A transitional state where the camera will be closing permanently.
         *
         * <p>At the end of this state, the camera should move into the RELEASED state.
         */
        RELEASING,
        /**
         * A stable state where the camera has been permanently closed.
         *
         * <p>During this state all resources should be released and all operations on the camera
         * will do nothing.
         */
        RELEASED
    }

    @WorkerThread
    void setState(InternalState state) {
        mState = state;
        // Convert the internal state to the publicly visible state
        switch (state) {
            case INITIALIZED:
                mObservableState.postValue(State.CLOSED);
                break;
            case PENDING_OPEN:
                mObservableState.postValue(State.PENDING_OPEN);
                break;
            case OPENING:
            case REOPENING:
                mObservableState.postValue(State.OPENING);
                break;
            case OPENED:
                mObservableState.postValue(State.OPEN);
                break;
            case CLOSING:
                mObservableState.postValue(State.CLOSING);
                break;
            case RELEASING:
                mObservableState.postValue(State.RELEASING);
                break;
            case RELEASED:
                mObservableState.postValue(State.RELEASED);
                break;
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERROR_NONE:
                return "ERROR_NONE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "ERROR_CAMERA_DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "ERROR_CAMERA_DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "ERROR_CAMERA_IN_USE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "ERROR_CAMERA_SERVICE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "ERROR_MAX_CAMERAS_IN_USE";
            default: // fall out
        }
        return "UNKNOWN ERROR";
    }

    final class StateCallback extends CameraDevice.StateCallback {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.d(TAG, "CameraDevice.onOpened(): " + cameraDevice.getId());
            mCameraDevice = cameraDevice;
            mCameraDeviceError = ERROR_NONE;
            switch (mState) {
                case CLOSING:
                case RELEASING:
                    // No session should have yet been opened, so close camera directly here.
                    Preconditions.checkState(isSessionCloseComplete());
                    mCameraDevice.close();
                    mCameraDevice = null;
                    break;
                case OPENING:
                case REOPENING:
                    setState(InternalState.OPENED);
                    openCaptureSession();
                    break;
                default:
                    throw new IllegalStateException(
                            "onOpened() should not be possible from state: " + mState);
            }
        }

        @Override
        public void onClosed(CameraDevice cameraDevice) {
            Log.d(TAG, "CameraDevice.onClosed(): " + cameraDevice.getId());
            Preconditions.checkState(mCameraDevice == null,
                    "Unexpected onClose callback on camera device: " + cameraDevice);
            switch (mState) {
                case CLOSING:
                case RELEASING:
                    Preconditions.checkState(isSessionCloseComplete());
                    finishClose();
                    break;
                case REOPENING:
                    openCameraDevice();
                    break;
                default:
                    CameraX.postError(
                            CameraX.ErrorCode.CAMERA_STATE_INCONSISTENT,
                            "Camera closed while in state: " + mState);
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.d(TAG, "CameraDevice.onDisconnected(): " + cameraDevice.getId());

            // onDisconnected could be called before onOpened if the camera becomes disconnected
            // during initialization, so keep track of it here.
            mCameraDevice = cameraDevice;

            switch (mState) {
                case CLOSING:
                case REOPENING:
                case OPENED:
                case OPENING:
                    // TODO: Create a "DISCONNECTED" state so camera can recover once available.
                    setState(InternalState.RELEASING);
                    break;
                case RELEASING:
                    // State will be set to RELEASED once camera finishes closing.
                    break;
                default:
                    throw new IllegalStateException(
                            "onDisconnected() should not be possible from state: " + mState);
            }
            // Not to close the in flight captures since the capture session has already been
            // closed.
            closeCamera(/*abortInFlightCaptures=*/false);
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            // onError could be called before onOpened if there is an error opening the camera
            // during initialization, so keep track of it here.
            mCameraDevice = cameraDevice;
            mCameraDeviceError = error;

            switch (mState) {
                case RELEASING:
                case CLOSING:
                    Log.e(
                            TAG,
                            "CameraDevice.onError(): "
                                    + cameraDevice.getId()
                                    + " with error: "
                                    + getErrorMessage(error));
                    closeCamera(/*abortInFlightCaptures=*/false);
                    break;
                case OPENING:
                case OPENED:
                case REOPENING:
                    handleErrorOnOpen(cameraDevice, error);
                    break;
                default:
                    throw new IllegalStateException(
                            "onError() should not be possible from state: " + mState);
            }
        }

        private void handleErrorOnOpen(@NonNull CameraDevice cameraDevice, int error) {
            Preconditions.checkState(
                    mState == InternalState.OPENING || mState == InternalState.OPENED
                            || mState == InternalState.REOPENING,
                    "Attempt to handle open error from non open state: " + mState);
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    // A fatal error occurred. The device should be reopened.
                    // Fall through.
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    // Attempt to reopen the camera again. If there are no cameras available,
                    // this will wait for the next available camera.
                    reopenCameraAfterError();
                    break;
                default:
                    // TODO: Properly handle other errors. For now, we will close the camera.
                    Log.e(
                            TAG,
                            "Error observed on open (or opening) camera device "
                                    + cameraDevice.getId()
                                    + ": "
                                    + getErrorMessage(error));
                    setState(InternalState.CLOSING);
                    closeCamera(/*abortInFlightCaptures=*/false);
                    break;
            }
        }


        private void reopenCameraAfterError() {
            // After an error, we must close the current camera device before we can open a new
            // one. To accomplish this, we will close the current camera and wait for the
            // onClosed() callback to reopen the device. It is also possible that the device can
            // be closed immediately, so in that case we will open the device manually.
            Preconditions.checkState(mCameraDeviceError != ERROR_NONE,
                    "Can only reopen camera device after error if the camera device is actually "
                            + "in an error state.");
            setState(InternalState.REOPENING);
            closeCamera(/*abortInFlightCaptures=*/false);
        }
    }

    final class AvailableCamerasObserver implements Observable.Observer<Integer> {

        @Override
        public void onNewData(@Nullable Integer value) {
            Preconditions.checkNotNull(value);
            if (value != mNumAvailableCameras) {
                mNumAvailableCameras = value;

                if (mState == InternalState.PENDING_OPEN) {
                    openCameraDevice();
                }
            }
        }

        @Override
        public void onError(@NonNull Throwable t) {
            // No errors expected from available cameras yet. May need to be handled in the future.
        }
    }
}
