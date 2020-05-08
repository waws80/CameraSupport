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

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import thanatos.android.camera.camera2.Camera2Config;
import thanatos.android.camera.camera2.impl.compat.CameraCaptureSessionCompat;
import thanatos.android.camera.camera2.impl.compat.CameraDeviceCompat;
import thanatos.android.camera.camera2.impl.compat.params.OutputConfigurationCompat;
import thanatos.android.camera.camera2.impl.compat.params.SessionConfigurationCompat;
import thanatos.android.camera.core.CameraCaptureCallback;
import thanatos.android.camera.core.CameraCaptureSessionStateCallbacks;
import thanatos.android.camera.core.CaptureConfig;
import thanatos.android.camera.core.Config;
import thanatos.android.camera.core.DeferrableSurface;
import thanatos.android.camera.core.DeferrableSurfaces;
import thanatos.android.camera.core.MutableOptionsBundle;
import thanatos.android.camera.core.SessionConfig;
import thanatos.android.camera.core.external.Preconditions;
import thanatos.android.camera.core.external.futures.CallbackToFutureAdapter;
import thanatos.android.camera.core.impl.utils.executor.CameraXExecutors;
import thanatos.android.camera.core.impl.utils.futures.Futures;

/**
 * A session for capturing images from the camera which is tied to a specific {@link CameraDevice}.
 *
 * <p>A session can only be opened a single time. Once has {@link CaptureSession#close()} been
 * called then it is permanently closed so a new session has to be created for capturing images.
 */
final class CaptureSession {
    private static final String TAG = "CaptureSession";
    /** Lock on whether the camera is open or closed. */
    final Object mStateLock = new Object();
    /** Executor for all the callbacks from the {@link CameraCaptureSession}. */
    private final Executor mExecutor;
    /** The configuration for the currently issued single capture requests. */
    private final List<CaptureConfig> mCaptureConfigs = new ArrayList<>();
    /** Callback for handling image captures. */
    private final CaptureCallback mCaptureCallback =
            new CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                }
            };
    private final StateCallback mCaptureSessionStateCallback = new StateCallback();
    /** The framework camera capture session held by this session. */
    @Nullable
    CameraCaptureSession mCameraCaptureSession;
    /** The configuration for the currently issued capture requests. */
    @Nullable
    volatile SessionConfig mSessionConfig;
    /** The capture options from CameraEventCallback.onRepeating(). **/
    @Nullable
    volatile Config mCameraEventOnRepeatingOptions;
    /**
     * The map of DeferrableSurface to Surface. It is both for restoring the surfaces used to
     * configure the current capture session and for getting the configured surface from a
     * DeferrableSurface.
     */
    private Map<DeferrableSurface, Surface> mConfiguredSurfaceMap = new HashMap<>();


    /** The list of DeferrableSurface used to notify surface detach events */
    @GuardedBy("mConfiguredDeferrableSurfaces")
    List<DeferrableSurface> mConfiguredDeferrableSurfaces = Collections.emptyList();
    /** Tracks the current state of the session. */
    @GuardedBy("mStateLock")
    State mState = State.UNINITIALIZED;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @GuardedBy("mStateLock")
    ListenableFuture<Void> mReleaseFuture;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @GuardedBy("mStateLock")
    CallbackToFutureAdapter.Completer<Void> mReleaseCompleter;

    /**
     * Constructor for CaptureSession.
     *
     * @param executor The executor is responsible for queuing up callbacks from capture requests.
     */
    CaptureSession(@NonNull Executor executor) {
        mState = State.INITIALIZED;

        // Ensure tasks posted to the executor are executed sequentially.
        if (CameraXExecutors.isSequentialExecutor(executor)) {
            mExecutor = executor;
        } else {
            mExecutor = CameraXExecutors.newSequentialExecutor(executor);
        }
    }

    /**
     * Returns the configurations of the capture session, or null if it has not yet been set
     * or if the capture session has been closed.
     */
    @Nullable
    SessionConfig getSessionConfig() {
        synchronized (mStateLock) {
            return mSessionConfig;
        }
    }

    /**
     * Sets the active configurations for the capture session.
     *
     * <p>Once both the session configuration has been set and the session has been opened, then the
     * capture requests will immediately be issued.
     *
     * @param sessionConfig has the configuration that will currently active in issuing capture
     *                      request. The surfaces contained in this must be a subset of the
     *                      surfaces that were used to open this capture session.
     */
    void setSessionConfig(SessionConfig sessionConfig) {
        synchronized (mStateLock) {
            switch (mState) {
                case UNINITIALIZED:
                    throw new IllegalStateException(
                            "setSessionConfig() should not be possible in state: " + mState);
                case INITIALIZED:
                case OPENING:
                    mSessionConfig = sessionConfig;
                    break;
                case OPENED:
                    mSessionConfig = sessionConfig;

                    if (!mConfiguredSurfaceMap.keySet().containsAll(sessionConfig.getSurfaces())) {
                        Log.e(TAG, "Does not have the proper configured lists");
                        return;
                    }

                    Log.d(TAG, "Attempting to submit CaptureRequest after setting");
                    issueRepeatingCaptureRequests();
                    break;
                case CLOSED:
                case RELEASING:
                case RELEASED:
                    throw new IllegalStateException(
                            "Session configuration cannot be set on a closed/released session.");
            }
        }
    }

    /**
     * Opens the capture session synchronously.
     *
     * <p>When the session is opened and the configurations have been set then the capture requests
     * will be issued.
     *
     * @param sessionConfig which is used to configure the camera capture session. This contains
     *                      configurations which may or may not be currently active in issuing
     *                      capture requests.
     * @param cameraDevice  the camera with which to generate the capture session
     * @throws CameraAccessException if the camera is in an invalid start state
     */
    void open(SessionConfig sessionConfig, CameraDevice cameraDevice)
            throws CameraAccessException {
        synchronized (mStateLock) {
            switch (mState) {
                case UNINITIALIZED:
                    throw new IllegalStateException(
                            "open() should not be possible in state: " + mState);
                case INITIALIZED:
                    List<DeferrableSurface> surfaces = sessionConfig.getSurfaces();

                    // Before creating capture session, some surfaces may need to refresh.
                    DeferrableSurfaces.refresh(surfaces);

                    mConfiguredDeferrableSurfaces = new ArrayList<>(surfaces);

                    List<Surface> configuredSurfaces = new ArrayList<>(
                            DeferrableSurfaces.surfaceList(mConfiguredDeferrableSurfaces));
                    if (configuredSurfaces.isEmpty()) {
                        Log.e(TAG, "Unable to open capture session with no surfaces. ");
                        return;
                    }

                    // Establishes the mapping of DeferrableSurface to Surface. Capture request will
                    // use this mapping to get the Surface from DeferrableSurface.
                    mConfiguredSurfaceMap.clear();
                    for (int i = 0; i < configuredSurfaces.size(); i++) {
                        mConfiguredSurfaceMap.put(mConfiguredDeferrableSurfaces.get(i),
                                configuredSurfaces.get(i));
                    }

                    // Some DeferrableSurfaces might actually point to the same Surface. And we need
                    // to pass the unique Surface list to createCaptureSession.
                    List<Surface> uniqueConfiguredSurface = new ArrayList<>(
                            new HashSet<>(configuredSurfaces));

                    notifySurfaceAttached();
                    mState = State.OPENING;
                    Log.d(TAG, "Opening capture session.");
                    List<CameraCaptureSession.StateCallback> callbacks =
                            new ArrayList<>(sessionConfig.getSessionStateCallbacks());
                    callbacks.add(mCaptureSessionStateCallback);
                    CameraCaptureSession.StateCallback comboCallback =
                            CameraCaptureSessionStateCallbacks.createComboCallback(callbacks);

                    // Start check preset CaptureStage information.
                    CameraEventCallbacks eventCallbacks = new Camera2Config(
                            sessionConfig.getImplementationOptions()).getCameraEventCallback(
                            CameraEventCallbacks.createEmptyCallback());
                    List<CaptureConfig> presetList =
                            eventCallbacks.createComboCallback().onPresetSession();

                    // Generate the CaptureRequest builder from repeating request since Android
                    // recommend use the same template type as the initial capture request. The
                    // tag and output targets would be ignored by default.
                    CaptureConfig.Builder captureConfigBuilder = CaptureConfig.Builder.from(
                            sessionConfig.getRepeatingCaptureConfig());

                    for (CaptureConfig config : presetList) {
                        captureConfigBuilder.addImplementationOptions(
                                config.getImplementationOptions());
                    }

                    List<OutputConfigurationCompat> outputConfigList = new LinkedList<>();
                    for (Surface surface : uniqueConfiguredSurface) {
                        outputConfigList.add(new OutputConfigurationCompat(surface));
                    }

                    SessionConfigurationCompat sessionConfigCompat =
                            new SessionConfigurationCompat(
                                    SessionConfigurationCompat.SESSION_REGULAR,
                                    outputConfigList,
                                    getExecutor(),
                                    comboCallback);

                    CaptureRequest captureRequest =
                            Camera2CaptureRequestBuilder.buildWithoutTarget(
                                    captureConfigBuilder.build(),
                                    cameraDevice);

                    if (captureRequest != null) {
                        sessionConfigCompat.setSessionParameters(captureRequest);
                    }

                    CameraDeviceCompat.createCaptureSession(cameraDevice, sessionConfigCompat);
                    break;
                default:
                    Log.e(TAG, "Open not allowed in state: " + mState);
            }
        }
    }

    /**
     * Closes the capture session.
     *
     * <p>Close needs be called on a session in order to safely open another session. However, this
     * stops minimal resources so that another session can be quickly opened.
     *
     * <p>Once a session is closed it can no longer be opened again. After the session is closed all
     * method calls on it do nothing.
     */
    void close() {
        synchronized (mStateLock) {
            switch (mState) {
                case UNINITIALIZED:
                    throw new IllegalStateException(
                            "close() should not be possible in state: " + mState);
                case INITIALIZED:
                    mState = State.RELEASED;
                    break;
                case OPENED:
                    // Only issue onDisableSession requests at OPENED state.
                    if (mSessionConfig != null) {
                        CameraEventCallbacks eventCallbacks = new Camera2Config(
                                mSessionConfig.getImplementationOptions()).getCameraEventCallback(
                                CameraEventCallbacks.createEmptyCallback());
                        List<CaptureConfig> configList =
                                eventCallbacks.createComboCallback().onDisableSession();
                        if (!configList.isEmpty()) {
                            try {
                                issueCaptureRequests(setupConfiguredSurface(configList));
                            } catch (IllegalStateException e) {
                                // We couldn't issue the request before close the capture session,
                                // but we should continue the close flow.
                                Log.e(TAG, "Unable to issue the request before close the capture "
                                        + "session", e);
                            }
                        }
                    }
                    // Not break close flow.
                case OPENING:
                    mState = State.CLOSED;
                    mSessionConfig = null;
                    mCameraEventOnRepeatingOptions = null;
                    break;
                case CLOSED:
                case RELEASING:
                case RELEASED:
                    break;
            }
        }
    }

    /**
     * Releases the capture session.
     *
     * <p>This releases all of the sessions resources and should be called when ready to close the
     * camera.
     *
     * <p>Once a session is released it can no longer be opened again. After the session is released
     * all method calls on it do nothing.
     */
    ListenableFuture<Void> release(boolean abortInFlightCaptures) {
        synchronized (mStateLock) {
            switch (mState) {
                case UNINITIALIZED:
                    throw new IllegalStateException(
                            "release() should not be possible in state: " + mState);
                case OPENED:
                case CLOSED:
                    if (mCameraCaptureSession != null) {
                        if (abortInFlightCaptures) {
                            try {
                                mCameraCaptureSession.abortCaptures();
                            } catch (CameraAccessException e) {
                                // We couldn't abort the captures, but we should continue on to
                                // release the session.
                                Log.e(TAG, "Unable to abort captures.", e);
                            }
                        }
                        mCameraCaptureSession.close();
                    }
                    // Fall through
                case OPENING:
                    mState = State.RELEASING;
                    // Fall through
                case RELEASING:
                    if (mReleaseFuture == null) {
                        mReleaseFuture = CallbackToFutureAdapter.getFuture(
                                new CallbackToFutureAdapter.Resolver<Void>() {
                                    @Override
                                    public Object attachCompleter(@NonNull
                                            CallbackToFutureAdapter.Completer<Void> completer) {
                                        Preconditions.checkState(Thread.holdsLock(mStateLock));
                                        Preconditions.checkState(mReleaseCompleter == null,
                                                "Release completer expected to be null");
                                        mReleaseCompleter = completer;
                                        return "Release[session=" + CaptureSession.this + "]";
                                    }
                                });
                    }

                    return mReleaseFuture;
                case INITIALIZED:
                    mState = State.RELEASED;
                    // Fall through
                case RELEASED:
                    break;
            }
        }

        // Already released. Return success immediately.
        return Futures.immediateFuture(null);
    }

    // Notify the surface is attached to a new capture session.
    void notifySurfaceAttached() {
        synchronized (mConfiguredDeferrableSurfaces) {
            for (DeferrableSurface deferrableSurface : mConfiguredDeferrableSurfaces) {
                deferrableSurface.notifySurfaceAttached();
            }
        }
    }

    // Notify the surface is detached from current capture session.
    void notifySurfaceDetached() {
        synchronized (mConfiguredDeferrableSurfaces) {
            for (DeferrableSurface deferredSurface : mConfiguredDeferrableSurfaces) {
                deferredSurface.notifySurfaceDetached();
            }
            // Clears the mConfiguredDeferrableSurfaces to prevent from duplicate
            // notifySurfaceDetached calls.
            mConfiguredDeferrableSurfaces.clear();
        }
    }

    /**
     * Issues capture requests.
     *
     * @param captureConfigs which is used to construct {@link CaptureRequest}.
     */
    void issueCaptureRequests(List<CaptureConfig> captureConfigs) {
        synchronized (mStateLock) {
            switch (mState) {
                case UNINITIALIZED:
                    throw new IllegalStateException(
                            "issueCaptureRequests() should not be possible in state: "
                                    + mState);
                case INITIALIZED:
                case OPENING:
                    mCaptureConfigs.addAll(captureConfigs);
                    break;
                case OPENED:
                    mCaptureConfigs.addAll(captureConfigs);
                    issueBurstCaptureRequest();
                    break;
                case CLOSED:
                case RELEASING:
                case RELEASED:
                    throw new IllegalStateException(
                            "Cannot issue capture request on a closed/released session.");
            }
        }
    }

    /** Returns the configurations of the capture requests. */
    List<CaptureConfig> getCaptureConfigs() {
        synchronized (mStateLock) {
            return Collections.unmodifiableList(mCaptureConfigs);
        }
    }

    /** Returns the current state of the session. */
    State getState() {
        synchronized (mStateLock) {
            return mState;
        }
    }

    /**
     * Sets the {@link CaptureRequest} so that the camera will start producing data.
     *
     * <p>Will skip setting requests if there are no surfaces since it is illegal to do so.
     */
    void issueRepeatingCaptureRequests() {
        if (mSessionConfig == null) {
            Log.d(TAG, "Skipping issueRepeatingCaptureRequests for no configuration case.");
            return;
        }

        CaptureConfig captureConfig = mSessionConfig.getRepeatingCaptureConfig();

        try {
            Log.d(TAG, "Issuing request for session.");

            // The override priority for implementation options
            // P1 CameraEventCallback onRepeating options
            // P2 SessionConfig options
            CaptureConfig.Builder captureConfigBuilder = CaptureConfig.Builder.from(captureConfig);

            CameraEventCallbacks eventCallbacks = new Camera2Config(
                    mSessionConfig.getImplementationOptions()).getCameraEventCallback(
                    CameraEventCallbacks.createEmptyCallback());

            mCameraEventOnRepeatingOptions = mergeOptions(
                    eventCallbacks.createComboCallback().onRepeating());
            if (mCameraEventOnRepeatingOptions != null) {
                captureConfigBuilder.addImplementationOptions(mCameraEventOnRepeatingOptions);
            }

            CaptureRequest captureRequest = Camera2CaptureRequestBuilder.build(
                    captureConfigBuilder.build(), mCameraCaptureSession.getDevice(),
                    mConfiguredSurfaceMap);
            if (captureRequest == null) {
                Log.d(TAG, "Skipping issuing empty request for session.");
                return;
            }

            CaptureCallback comboCaptureCallback =
                    createCamera2CaptureCallback(
                            captureConfig.getCameraCaptureCallbacks(),
                            mCaptureCallback);

            CameraCaptureSessionCompat.setSingleRepeatingRequest(mCameraCaptureSession,
                    captureRequest, mExecutor, comboCaptureCallback);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to access camera: " + e.getMessage());
            Thread.dumpStack();
        }
    }

    /** Issues mCaptureConfigs to {@link CameraCaptureSession}. */
    void issueBurstCaptureRequest() {
        if (mCaptureConfigs.isEmpty()) {
            return;
        }
        try {
            CameraBurstCaptureCallback callbackAggregator = new CameraBurstCaptureCallback();
            List<CaptureRequest> captureRequests = new ArrayList<>();
            Log.d(TAG, "Issuing capture request.");
            for (CaptureConfig captureConfig : mCaptureConfigs) {
                if (captureConfig.getSurfaces().isEmpty()) {
                    Log.d(TAG, "Skipping issuing empty capture request.");
                    continue;
                }

                // Validate all surfaces belong to configured surfaces map
                boolean surfacesValid = true;
                for (DeferrableSurface surface : captureConfig.getSurfaces()) {
                    if (!mConfiguredSurfaceMap.containsKey(surface)) {
                        Log.d(TAG, "Skipping capture request with invalid surface: " + surface);
                        surfacesValid = false;
                        break;
                    }
                }

                if (!surfacesValid) {
                    // An invalid surface was detected in this request.
                    // Skip it and go on to the next request.
                    // TODO (b/133710422): Report this request as an error.
                    continue;
                }

                CaptureConfig.Builder captureConfigBuilder = CaptureConfig.Builder.from(
                        captureConfig);

                // The override priority for implementation options
                // P1 Single capture options
                // P2 CameraEventCallback onRepeating options
                // P3 SessionConfig options
                if (mSessionConfig != null) {
                    captureConfigBuilder.addImplementationOptions(
                            mSessionConfig.getRepeatingCaptureConfig().getImplementationOptions());
                }

                if (mCameraEventOnRepeatingOptions != null) {
                    captureConfigBuilder.addImplementationOptions(mCameraEventOnRepeatingOptions);
                }

                // Need to override again since single capture options has highest priority.
                captureConfigBuilder.addImplementationOptions(
                        captureConfig.getImplementationOptions());

                CaptureRequest captureRequest = Camera2CaptureRequestBuilder.build(
                        captureConfigBuilder.build(),
                        mCameraCaptureSession.getDevice(), mConfiguredSurfaceMap);
                if (captureRequest == null) {
                    Log.d(TAG, "Skipping issuing request without surface.");
                    return;
                }

                List<CaptureCallback> cameraCallbacks = new ArrayList<>();
                for (CameraCaptureCallback callback : captureConfig.getCameraCaptureCallbacks()) {
                    CaptureCallbackConverter.toCaptureCallback(callback, cameraCallbacks);
                }
                callbackAggregator.addCamera2Callbacks(captureRequest, cameraCallbacks);
                captureRequests.add(captureRequest);
            }

            if (!captureRequests.isEmpty()) {
                CameraCaptureSessionCompat.captureBurstRequests(mCameraCaptureSession,
                        captureRequests, mExecutor, callbackAggregator);
            } else {
                Log.d(TAG, "Skipping issuing burst request due to no valid request elements");
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to access camera: " + e.getMessage());
            Thread.dumpStack();
        } finally {
            mCaptureConfigs.clear();
        }
    }

    private CaptureCallback createCamera2CaptureCallback(
            List<CameraCaptureCallback> cameraCaptureCallbacks,
            CaptureCallback... additionalCallbacks) {
        List<CaptureCallback> camera2Callbacks =
                new ArrayList<>(cameraCaptureCallbacks.size() + additionalCallbacks.length);
        for (CameraCaptureCallback callback : cameraCaptureCallbacks) {
            camera2Callbacks.add(CaptureCallbackConverter.toCaptureCallback(callback));
        }
        Collections.addAll(camera2Callbacks, additionalCallbacks);
        return Camera2CaptureCallbacks.createComboCallback(camera2Callbacks);
    }


    /**
     * Merges the implementation options from the input {@link CaptureConfig} list.
     *
     * <p>It will retain the first option if a conflict is detected.
     *
     * @param captureConfigList CaptureConfig list to be merged.
     * @return merged options.
     */
    @NonNull
    private static Config mergeOptions(List<CaptureConfig> captureConfigList) {
        MutableOptionsBundle options = MutableOptionsBundle.create();
        for (CaptureConfig captureConfig : captureConfigList) {
            Config newOptions = captureConfig.getImplementationOptions();
            for (Config.Option<?> option : newOptions.listOptions()) {
                @SuppressWarnings("unchecked") // Options/values are being copied directly
                        Config.Option<Object> objectOpt = (Config.Option<Object>) option;
                Object newValue = newOptions.retrieveOption(objectOpt, null);
                if (options.containsOption(option)) {
                    Object oldValue = options.retrieveOption(objectOpt, null);
                    if (!Objects.equals(oldValue, newValue)) {
                        Log.d(TAG, "Detect conflicting option "
                                + objectOpt.getId()
                                + " : "
                                + newValue
                                + " != "
                                + oldValue);
                    }
                } else {
                    options.insertOption(objectOpt, newValue);
                }
            }
        }
        return options;
    }

    enum State {
        /** The default state of the session before construction. */
        UNINITIALIZED,
        /**
         * Stable state once the session has been constructed, but prior to the {@link
         * CameraCaptureSession} being opened.
         */
        INITIALIZED,
        /**
         * Transitional state when the {@link CameraCaptureSession} is in the process of being
         * opened.
         */
        OPENING,
        /**
         * Stable state where the {@link CameraCaptureSession} has been successfully opened. During
         * this state if a valid {@link SessionConfig} has been set then the {@link
         * CaptureRequest} will be issued.
         */
        OPENED,
        /**
         * Stable state where the session has been closed. However the {@link CameraCaptureSession}
         * is still valid. It will remain valid until a new instance is opened at which point {@link
         * CameraCaptureSession.StateCallback#onClosed(CameraCaptureSession)} will be called to do
         * final cleanup.
         */
        CLOSED,
        /** Transitional state where the resources are being cleaned up. */
        RELEASING,
        /**
         * Terminal state where the session has been cleaned up. At this point the session should
         * not be used as nothing will happen in this state.
         */
        RELEASED
    }

    /**
     * Callback for handling state changes to the {@link CameraCaptureSession}.
     *
     * <p>State changes are ignored once the CaptureSession has been closed.
     */
    final class StateCallback extends CameraCaptureSession.StateCallback {
        /**
         * {@inheritDoc}
         *
         * <p>Once the {@link CameraCaptureSession} has been configured then the capture request
         * will be immediately issued.
         */
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            synchronized (mStateLock) {
                switch (mState) {
                    case UNINITIALIZED:
                    case INITIALIZED:
                    case OPENED:
                    case RELEASED:
                        throw new IllegalStateException(
                                "onConfigured() should not be possible in state: " + mState);
                    case OPENING:
                        mState = State.OPENED;
                        mCameraCaptureSession = session;

                        // Issue capture request of enableSession if exists.
                        if (mSessionConfig != null) {
                            Config implOptions = mSessionConfig.getImplementationOptions();
                            CameraEventCallbacks eventCallbacks = new Camera2Config(
                                    implOptions).getCameraEventCallback(
                                    CameraEventCallbacks.createEmptyCallback());
                            List<CaptureConfig> list =
                                    eventCallbacks.createComboCallback().onEnableSession();
                            if (!list.isEmpty()) {
                                issueCaptureRequests(setupConfiguredSurface(list));
                            }
                        }

                        Log.d(TAG, "Attempting to send capture request onConfigured");
                        issueRepeatingCaptureRequests();
                        issueBurstCaptureRequest();
                        break;
                    case CLOSED:
                        mCameraCaptureSession = session;
                        break;
                    case RELEASING:
                        session.close();
                        break;
                }
                Log.d(TAG, "CameraCaptureSession.onConfigured() mState=" + mState);
            }
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            synchronized (mStateLock) {
                switch (mState) {
                    case UNINITIALIZED:
                        throw new IllegalStateException(
                                "onReady() should not be possible in state: " + mState);
                    default:
                }
                Log.d(TAG, "CameraCaptureSession.onReady()");
            }
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            synchronized (mStateLock) {
                if (mState == State.UNINITIALIZED) {
                    throw new IllegalStateException(
                            "onClosed() should not be possible in state: " + mState);
                }

                Log.d(TAG, "CameraCaptureSession.onClosed()");

                mState = State.RELEASED;
                mCameraCaptureSession = null;

                notifySurfaceDetached();

                if (mReleaseCompleter != null) {
                    mReleaseCompleter.set(null);
                    mReleaseCompleter = null;
                }
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            synchronized (mStateLock) {
                switch (mState) {
                    case UNINITIALIZED:
                    case INITIALIZED:
                    case OPENED:
                    case RELEASED:
                        throw new IllegalStateException(
                                "onConfiguredFailed() should not be possible in state: " + mState);
                    case OPENING:
                    case CLOSED:
                        mState = State.CLOSED;
                        mCameraCaptureSession = session;
                        break;
                    case RELEASING:
                        mState = State.RELEASING;
                        session.close();
                }
                Log.e(TAG, "CameraCaptureSession.onConfiguredFailed()");
            }
        }
    }

    /** Also notify the surface detach event if receives camera device close event */
    public void notifyCameraDeviceClose() {
        notifySurfaceDetached();
    }

    List<CaptureConfig> setupConfiguredSurface(List<CaptureConfig> list) {
        List<CaptureConfig> ret = new ArrayList<>();
        for (CaptureConfig c : list) {
            CaptureConfig.Builder builder = CaptureConfig.Builder.from(c);
            builder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
            for (DeferrableSurface deferrableSurface :
                    mSessionConfig.getRepeatingCaptureConfig().getSurfaces()) {
                builder.addSurface(deferrableSurface);
            }
            ret.add(builder.build());
        }

        return ret;
    }

    // TODO: We should enforce that mExecutor is never null.
    //  We can remove this method once that is the case.
    private Executor getExecutor() {
        if (mExecutor == null) {
            return CameraXExecutors.myLooperExecutor();
        }

        return mExecutor;
    }
}
