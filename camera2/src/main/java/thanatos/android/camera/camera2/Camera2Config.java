/*
 * Copyright (C) 2019 The Android Open Source Project
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

package thanatos.android.camera.camera2;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;
import android.view.Surface;

import java.util.HashSet;
import java.util.Set;

import thanatos.android.camera.camera2.impl.CameraEventCallbacks;
import thanatos.android.camera.core.Config;
import thanatos.android.camera.core.MutableConfig;
import thanatos.android.camera.core.MutableOptionsBundle;
import thanatos.android.camera.core.OptionsBundle;

/** Configuration options related to the {@link android.hardware.camera2} APIs. */
public final class Camera2Config implements Config {

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public static final String CAPTURE_REQUEST_ID_STEM = "camera2.captureRequest.option.";

    // Option Declarations:
    // *********************************************************************************************

    static final Config.Option<Integer> TEMPLATE_TYPE_OPTION =
            Option.create("camera2.captureRequest.templateType", int.class);
    static final Option<StateCallback> DEVICE_STATE_CALLBACK_OPTION =
            Option.create("camera2.cameraDevice.stateCallback", StateCallback.class);
    static final Option<CameraCaptureSession.StateCallback> SESSION_STATE_CALLBACK_OPTION =
            Option.create(
                    "camera2.cameraCaptureSession.stateCallback",
                    CameraCaptureSession.StateCallback.class);
    static final Option<CaptureCallback> SESSION_CAPTURE_CALLBACK_OPTION =
            Option.create("camera2.cameraCaptureSession.captureCallback", CaptureCallback.class);

    /** @hide */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public static final Option<CameraEventCallbacks> CAMERA_EVENT_CALLBACK_OPTION =
            Option.create("camera2.cameraEvent.callback", CameraEventCallbacks.class);
    // *********************************************************************************************

    private final Config mConfig;

    /**
     * Creates a Camera2Config for reading Camera2 options from the given config.
     *
     * @param config The config that potentially contains Camera2 options.
     */
    public Camera2Config(@NonNull Config config) {
        mConfig = config;
    }

    // Unfortunately, we can't get the Class<T> from the CaptureRequest.Key, so we're forced to
    // erase the type. This shouldn't be a problem as long as we are only using these options
    // within the Camera2Config and Camera2Config.Builder classes.
    static Option<Object> createCaptureRequestOption(CaptureRequest.Key<?> key) {
        return Option.create(CAPTURE_REQUEST_ID_STEM + key.getName(), Object.class, key);
    }

    /**
     * Returns a value for the given {@link CaptureRequest.Key}.
     *
     * @param key            The key to retrieve.
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @param <ValueT>       The type of the value.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    public <ValueT> ValueT getCaptureRequestOption(
            @NonNull CaptureRequest.Key<ValueT> key, @Nullable ValueT valueIfMissing) {
        @SuppressWarnings(
                "unchecked") // Type should have been only set via Builder#setCaptureRequestOption()
                Option<ValueT> opt = (Option<ValueT>) Camera2Config.createCaptureRequestOption(key);
        return mConfig.retrieveOption(opt, valueIfMissing);
    }

    /**
     * Returns all capture request options contained in this configuration.
     * @hide
     */
    @RestrictTo(Scope.LIBRARY)
    @NonNull
    public Set<Option<?>> getCaptureRequestOptions() {
        final Set<Option<?>> optionSet = new HashSet<>();
        findOptions(
                Camera2Config.CAPTURE_REQUEST_ID_STEM,
                new OptionMatcher() {
                    @Override
                    public boolean onOptionMatched(Option<?> option) {
                        optionSet.add(option);
                        return true;
                    }
                });
        return optionSet;
    }

    /**
     * Returns the CameraDevice template from the given configuration.
     *
     * <p>See {@link CameraDevice} for valid template types. For example, {@link
     * CameraDevice#TEMPLATE_PREVIEW}.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     * @hide
     */
    @RestrictTo(Scope.LIBRARY)
    public int getCaptureRequestTemplate(int valueIfMissing) {
        return mConfig.retrieveOption(TEMPLATE_TYPE_OPTION, valueIfMissing);
    }

    /**
     * Returns the stored {@link CameraDevice.StateCallback}.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    public CameraDevice.StateCallback getDeviceStateCallback(
            @Nullable CameraDevice.StateCallback valueIfMissing) {
        return mConfig.retrieveOption(DEVICE_STATE_CALLBACK_OPTION, valueIfMissing);
    }

    /**
     * Returns the stored {@link CameraCaptureSession.StateCallback}.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    public CameraCaptureSession.StateCallback getSessionStateCallback(
            @Nullable CameraCaptureSession.StateCallback valueIfMissing) {
        return mConfig.retrieveOption(SESSION_STATE_CALLBACK_OPTION, valueIfMissing);
    }

    /**
     * Returns the stored {@link CameraCaptureSession.CaptureCallback}.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    public CameraCaptureSession.CaptureCallback getSessionCaptureCallback(
            @Nullable CameraCaptureSession.CaptureCallback valueIfMissing) {
        return mConfig.retrieveOption(SESSION_CAPTURE_CALLBACK_OPTION, valueIfMissing);
    }

    /**
     * Returns the stored CameraEventCallbacks instance.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    public CameraEventCallbacks getCameraEventCallback(
            @Nullable CameraEventCallbacks valueIfMissing) {
        return mConfig.retrieveOption(CAMERA_EVENT_CALLBACK_OPTION, valueIfMissing);
    }

    // Start of the default implementation of Config
    // *********************************************************************************************

    // Implementations of Config default methods

    /** @hide */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    public boolean containsOption(@NonNull Option<?> id) {
        return mConfig.containsOption(id);
    }

    /** @hide */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    @Nullable
    public <ValueT> ValueT retrieveOption(@NonNull Option<ValueT> id) {
        return mConfig.retrieveOption(id);
    }

    /** @hide */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    @Nullable
    public <ValueT> ValueT retrieveOption(@NonNull Option<ValueT> id,
                                          @Nullable ValueT valueIfMissing) {
        return mConfig.retrieveOption(id, valueIfMissing);
    }

    /** @hide */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    public void findOptions(@NonNull String idStem, @NonNull OptionMatcher matcher) {
        mConfig.findOptions(idStem, matcher);
    }

    /** @hide */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    @NonNull
    public Set<Option<?>> listOptions() {
        return mConfig.listOptions();
    }

    // End of the default implementation of Config
    // *********************************************************************************************

    /** Extends a {@link Config.ExtendableBuilder} to add Camera2 options. */
    public static final class Extender {

        Config.ExtendableBuilder mBaseBuilder;

        /**
         * Creates an Extender that can be used to add Camera2 options to another Builder.
         *
         * @param baseBuilder The builder being extended.
         */
        public Extender(@NonNull Config.ExtendableBuilder baseBuilder) {
            mBaseBuilder = baseBuilder;
        }

        /**
         * Sets a {@link CaptureRequest.Key} and Value on the configuration.
         *
         * @param key      The {@link CaptureRequest.Key} which will be set.
         * @param value    The value for the key.
         * @param <ValueT> The type of the value.
         * @return The current Extender.
         */
        @NonNull
        public <ValueT> Extender setCaptureRequestOption(
                @NonNull CaptureRequest.Key<ValueT> key, @NonNull ValueT value) {
            // Reify the type so we can obtain the class
            Option<Object> opt = Camera2Config.createCaptureRequestOption(key);
            mBaseBuilder.getMutableConfig().insertOption(opt, value);
            return this;
        }

        /**
         * Sets a CameraDevice template on the given configuration.
         *
         * <p>See {@link CameraDevice} for valid template types. For example, {@link
         * CameraDevice#TEMPLATE_PREVIEW}.
         *
         * @param templateType The template type to set.
         * @return The current Extender.
         * @hide
         */
        @RestrictTo(Scope.LIBRARY)
        @NonNull
        public Extender setCaptureRequestTemplate(int templateType) {
            mBaseBuilder.getMutableConfig().insertOption(TEMPLATE_TYPE_OPTION, templateType);
            return this;
        }

        /**
         * Sets a {@link CameraDevice.StateCallback}.
         *
         * <p>The caller is expected to use the {@link CameraDevice} instance accessed through the
         * callback methods responsibly. Generally safe usages include: (1) querying the device for
         * its id, (2) using the callbacks to determine what state the device is currently in.
         * Generally unsafe usages include: (1) creating a new {@link CameraCaptureSession}, (2)
         * creating a new {@link CaptureRequest}, (3) closing the device. When the caller uses the
         * device beyond the safe usage limits, the usage may still work in conjunction with
         * CameraX, but any strong guarantees provided by CameraX about the validity of the camera
         * state become void.
         *
         * @param stateCallback The {@link CameraDevice.StateCallback}.
         * @return The current Extender.
         */
        @NonNull
        public Extender setDeviceStateCallback(@NonNull CameraDevice.StateCallback stateCallback) {
            mBaseBuilder.getMutableConfig().insertOption(DEVICE_STATE_CALLBACK_OPTION,
                    stateCallback);
            return this;
        }

        /**
         * Sets a {@link CameraCaptureSession.StateCallback}.
         *
         * <p>The caller is expected to use the {@link CameraCaptureSession} instance accessed
         * through the callback methods responsibly. Generally safe usages include: (1) querying the
         * session for its properties, (2) using the callbacks to determine what state the session
         * is currently in. Generally unsafe usages include: (1) submitting a new {@link
         * CaptureRequest}, (2) stopping an existing {@link CaptureRequest}, (3) closing the
         * session, (4) attaching a new {@link Surface} to the session. When the caller uses the
         * session beyond the safe usage limits, the usage may still work in conjunction with
         * CameraX, but any strong gurantees provided by CameraX about the validity of the camera
         * state become void.
         *
         * @param stateCallback The {@link CameraCaptureSession.StateCallback}.
         * @return The current Extender.
         */
        @NonNull
        public Extender setSessionStateCallback(
                @NonNull CameraCaptureSession.StateCallback stateCallback) {
            mBaseBuilder.getMutableConfig().insertOption(SESSION_STATE_CALLBACK_OPTION,
                    stateCallback);
            return this;
        }

        /**
         * Sets a {@link CameraCaptureSession.CaptureCallback}.
         *
         * <p>The caller is expected to use the {@link CameraCaptureSession} instance accessed
         * through the callback methods responsibly. Generally safe usages include: (1) querying the
         * session for its properties. Generally unsafe usages include: (1) submitting a new {@link
         * CaptureRequest}, (2) stopping an existing {@link CaptureRequest}, (3) closing the
         * session, (4) attaching a new {@link Surface} to the session. When the caller uses the
         * session beyond the safe usage limits, the usage may still work in conjunction with
         * CameraX, but any strong gurantees provided by CameraX about the validity of the camera
         * state become void.
         *
         * <p>The caller is generally free to use the {@link CaptureRequest} and {@link
         * CaptureResult} instances accessed through the callback methods.
         *
         * @param captureCallback The {@link CameraCaptureSession.CaptureCallback}.
         * @return The current Extender.
         */
        @NonNull
        public Extender setSessionCaptureCallback(
                @NonNull CameraCaptureSession.CaptureCallback captureCallback) {
            mBaseBuilder.getMutableConfig().insertOption(SESSION_CAPTURE_CALLBACK_OPTION,
                    captureCallback);
            return this;
        }

        /**
         * Sets a CameraEventCallbacks instance.
         *
         * @param cameraEventCallbacks The CameraEventCallbacks.
         * @return The current Extender.
         */
        @NonNull
        public Extender setCameraEventCallback(@NonNull CameraEventCallbacks cameraEventCallbacks) {
            mBaseBuilder.getMutableConfig().insertOption(CAMERA_EVENT_CALLBACK_OPTION,
                    cameraEventCallbacks);
            return this;
        }
    }

    /**
     * Builder for creating {@link Camera2Config} instance.
     *
     * <p>Use {@link Builder} for creating {@link Config} which contains camera2 options
     * only. And use {@link Extender} to add Camera2 options on existing other {@link
     * Config.ExtendableBuilder}.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final class Builder implements ExtendableBuilder {

        private final MutableOptionsBundle mMutableOptionsBundle = MutableOptionsBundle.create();

        @Override
        @NonNull
        public MutableConfig getMutableConfig() {
            return mMutableOptionsBundle;
        }

        /**
         * Inserts new capture request option with specific {@link CaptureRequest.Key} setting.
         */
        @NonNull
        public <ValueT> Builder setCaptureRequestOption(
                @NonNull CaptureRequest.Key<ValueT> key, @NonNull ValueT value) {
            Option<Object> opt = Camera2Config.createCaptureRequestOption(key);
            mMutableOptionsBundle.insertOption(opt, value);
            return this;
        }

        /** Inserts options from other {@link Config} object. */
        @NonNull
        public Builder insertAllOptions(@NonNull Config config) {
            for (Option<?> option : config.listOptions()) {
                @SuppressWarnings("unchecked") // Options/values are being copied directly
                        Option<Object> objectOpt = (Option<Object>) option;
                mMutableOptionsBundle.insertOption(objectOpt, config.retrieveOption(objectOpt));
            }
            return this;
        }

        /**
         * Builds an immutable {@link Camera2Config} from the current state.
         *
         * @return A {@link Camera2Config} populated with the current state.
         */
        @NonNull
        public Camera2Config build() {
            return new Camera2Config(OptionsBundle.from(mMutableOptionsBundle));
        }
    }
}
