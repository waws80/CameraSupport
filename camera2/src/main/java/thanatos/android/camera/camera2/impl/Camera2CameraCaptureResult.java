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

import android.hardware.camera2.CaptureResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import thanatos.android.camera.core.CameraCaptureMetaData;
import thanatos.android.camera.core.CameraCaptureResult;

/** The camera2 implementation for the capture result of a single image capture. */
final class Camera2CameraCaptureResult implements CameraCaptureResult {
    private static final String TAG = "C2CameraCaptureResult";

    private final Object mTag;

    /** The actual camera2 {@link CaptureResult}. */
    private final CaptureResult mCaptureResult;

    Camera2CameraCaptureResult(@Nullable Object tag, CaptureResult captureResult) {
        mTag = tag;
        mCaptureResult = captureResult;
    }

    /**
     * Converts the camera2 {@link CaptureResult#CONTROL_AF_MODE} to {@link CameraCaptureMetaData.AfMode}.
     *
     * @return the {@link CameraCaptureMetaData.AfMode}.
     */
    @NonNull
    @Override
    public CameraCaptureMetaData.AfMode getAfMode() {
        Integer mode = mCaptureResult.get(CaptureResult.CONTROL_AF_MODE);
        if (mode == null) {
            return CameraCaptureMetaData.AfMode.UNKNOWN;
        }
        switch (mode) {
            case CaptureResult.CONTROL_AF_MODE_OFF:
            case CaptureResult.CONTROL_AF_MODE_EDOF:
                return CameraCaptureMetaData.AfMode.OFF;
            case CaptureResult.CONTROL_AF_MODE_AUTO:
            case CaptureResult.CONTROL_AF_MODE_MACRO:
                return CameraCaptureMetaData.AfMode.ON_MANUAL_AUTO;
            case CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
            case CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                return CameraCaptureMetaData.AfMode.ON_CONTINUOUS_AUTO;
            default: // fall out
        }
        Log.e(TAG, "Undefined af mode: " + mode);
        return CameraCaptureMetaData.AfMode.UNKNOWN;
    }

    /**
     * Converts the camera2 {@link CaptureResult#CONTROL_AF_STATE} to {@link CameraCaptureMetaData.AfState}.
     *
     * @return the {@link CameraCaptureMetaData.AfState}.
     */
    @NonNull
    @Override
    public CameraCaptureMetaData.AfState getAfState() {
        Integer state = mCaptureResult.get(CaptureResult.CONTROL_AF_STATE);
        if (state == null) {
            return CameraCaptureMetaData.AfState.UNKNOWN;
        }
        switch (state) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                return CameraCaptureMetaData.AfState.INACTIVE;
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                return CameraCaptureMetaData.AfState.SCANNING;
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                return CameraCaptureMetaData.AfState.LOCKED_FOCUSED;
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                return CameraCaptureMetaData.AfState.LOCKED_NOT_FOCUSED;
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                return CameraCaptureMetaData.AfState.FOCUSED;
            default: // fall out
        }
        Log.e(TAG, "Undefined af state: " + state);
        return CameraCaptureMetaData.AfState.UNKNOWN;
    }

    /**
     * Converts the camera2 {@link CaptureResult#CONTROL_AE_STATE} to {@link CameraCaptureMetaData.AeState}.
     *
     * @return the {@link CameraCaptureMetaData.AeState}.
     */
    @NonNull
    @Override
    public CameraCaptureMetaData.AeState getAeState() {
        Integer state = mCaptureResult.get(CaptureResult.CONTROL_AE_STATE);
        if (state == null) {
            return CameraCaptureMetaData.AeState.UNKNOWN;
        }
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE:
                return CameraCaptureMetaData.AeState.INACTIVE;
            case CaptureResult.CONTROL_AE_STATE_SEARCHING:
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE:
                return CameraCaptureMetaData.AeState.SEARCHING;
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED:
                return CameraCaptureMetaData.AeState.FLASH_REQUIRED;
            case CaptureResult.CONTROL_AE_STATE_CONVERGED:
                return CameraCaptureMetaData.AeState.CONVERGED;
            case CaptureResult.CONTROL_AE_STATE_LOCKED:
                return CameraCaptureMetaData.AeState.LOCKED;
            default: // fall out
        }
        Log.e(TAG, "Undefined ae state: " + state);
        return CameraCaptureMetaData.AeState.UNKNOWN;
    }

    /**
     * Converts the camera2 {@link CaptureResult#CONTROL_AWB_STATE} to {@link CameraCaptureMetaData.AwbState}.
     *
     * @return the {@link CameraCaptureMetaData.AwbState}.
     */
    @NonNull
    @Override
    public CameraCaptureMetaData.AwbState getAwbState() {
        Integer state = mCaptureResult.get(CaptureResult.CONTROL_AWB_STATE);
        if (state == null) {
            return CameraCaptureMetaData.AwbState.UNKNOWN;
        }
        switch (state) {
            case CaptureResult.CONTROL_AWB_STATE_INACTIVE:
                return CameraCaptureMetaData.AwbState.INACTIVE;
            case CaptureResult.CONTROL_AWB_STATE_SEARCHING:
                return CameraCaptureMetaData.AwbState.METERING;
            case CaptureResult.CONTROL_AWB_STATE_CONVERGED:
                return CameraCaptureMetaData.AwbState.CONVERGED;
            case CaptureResult.CONTROL_AWB_STATE_LOCKED:
                return CameraCaptureMetaData.AwbState.LOCKED;
            default: // fall out
        }
        Log.e(TAG, "Undefined awb state: " + state);
        return CameraCaptureMetaData.AwbState.UNKNOWN;
    }

    /**
     * Converts the camera2 {@link CaptureResult#FLASH_STATE} to {@link CameraCaptureMetaData.FlashState}.
     *
     * @return the {@link CameraCaptureMetaData.FlashState}.
     */
    @NonNull
    @Override
    public CameraCaptureMetaData.FlashState getFlashState() {
        Integer state = mCaptureResult.get(CaptureResult.FLASH_STATE);
        if (state == null) {
            return CameraCaptureMetaData.FlashState.UNKNOWN;
        }
        switch (state) {
            case CaptureResult.FLASH_STATE_UNAVAILABLE:
            case CaptureResult.FLASH_STATE_CHARGING:
                return CameraCaptureMetaData.FlashState.NONE;
            case CaptureResult.FLASH_STATE_READY:
                return CameraCaptureMetaData.FlashState.READY;
            case CaptureResult.FLASH_STATE_FIRED:
            case CaptureResult.FLASH_STATE_PARTIAL:
                return CameraCaptureMetaData.FlashState.FIRED;
            default: // fall out
        }
        Log.e(TAG, "Undefined flash state: " + state);
        return CameraCaptureMetaData.FlashState.UNKNOWN;
    }

    /** {@inheritDoc} */
    @Override
    public long getTimestamp() {
        Long timestamp = mCaptureResult.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null) {
            return -1L;
        }

        return timestamp;
    }

    @Override
    public Object getTag() {
        return mTag;
    }

    CaptureResult getCaptureResult() {
        return mCaptureResult;
    }
}
