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

import android.hardware.camera2.CameraCaptureSession;
import android.support.annotation.RestrictTo;

import thanatos.android.camera.core.CaptureConfig;

/**
 * A callback object for tracking the camera capture session event and get request data.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public abstract class CameraEventCallback {

    /**
     * This will be invoked before creating a {@link CameraCaptureSession}. The returned
     * parameter in CaptureConfig will be passed to the camera device as part of the capture session
     * initialization via setSessionParameters(). The valid parameter is a subset of the
     * available capture request parameters.
     *
     * @return CaptureConfig The request information to customize the session.
     */
    public CaptureConfig onPresetSession() {
        return null;
    }

    /**
     * This will be invoked once after a {@link CameraCaptureSession} is created. The returned
     * parameter in CaptureConfig will be used to generate a single request to the current
     * configured camera device. The generated request would be submitted to camera before process
     * other single request.
     *
     * @return CaptureConfig The request information to customize the session.
     */
    public CaptureConfig onEnableSession() {
        return null;
    }

    /**
     * This callback will be invoked before starting the repeating request in the
     * {@link CameraCaptureSession}. The returned CaptureConfig will be used to generate a
     * capture request, and would be used in setRepeatingRequest().
     *
     * @return CaptureConfig The request information to customize the session.
     */
    public CaptureConfig onRepeating() {
        return null;
    }

    /**
     * This will be invoked once before the {@link CameraCaptureSession} is closed. The
     * returned parameter in CaptureConfig will be used to generate a single request to the current
     * configured camera device. The generated request would be submitted to camera before the
     * capture session was closed.
     *
     * @return CaptureConfig The request information to customize the session.
     */
    public CaptureConfig onDisableSession() {
        return null;
    }

}
