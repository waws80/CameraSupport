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

import android.content.Context;
import android.hardware.camera2.CameraDevice;
import android.support.annotation.RestrictTo;
import android.util.Log;
import android.util.Rational;
import android.view.WindowManager;

import thanatos.android.camera.core.CameraFactory;
import thanatos.android.camera.core.CameraX;
import thanatos.android.camera.core.CaptureConfig;
import thanatos.android.camera.core.ConfigProvider;
import thanatos.android.camera.core.SessionConfig;
import thanatos.android.camera.core.VideoCapture;
import thanatos.android.camera.core.VideoCaptureConfig;

/**
 * Provides defaults for {@link VideoCaptureConfig} in the Camera2 implementation.
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class VideoCaptureConfigProvider implements ConfigProvider<VideoCaptureConfig> {
    private static final String TAG = "VideoCaptureProvider";
    private static final Rational DEFAULT_ASPECT_RATIO_16_9 = new Rational(16, 9);
    private static final Rational DEFAULT_ASPECT_RATIO_9_16 = new Rational(9, 16);

    private final CameraFactory mCameraFactory;
    private final WindowManager mWindowManager;

    public VideoCaptureConfigProvider(CameraFactory cameraFactory, Context context) {
        mCameraFactory = cameraFactory;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public VideoCaptureConfig getConfig(CameraX.LensFacing lensFacing) {
        VideoCaptureConfig.Builder builder =
                VideoCaptureConfig.Builder.fromConfig(
                        VideoCapture.DEFAULT_CONFIG.getConfig(lensFacing));

        // SessionConfig containing all intrinsic properties needed for VideoCapture
        SessionConfig.Builder sessionBuilder = new SessionConfig.Builder();
        // TODO(b/114762170): Must set to preview here until we allow for multiple template types
        sessionBuilder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);

        // Add options to UseCaseConfig
        builder.setDefaultSessionConfig(sessionBuilder.build());
        builder.setSessionOptionUnpacker(Camera2SessionOptionUnpacker.INSTANCE);

        CaptureConfig.Builder captureBuilder = new CaptureConfig.Builder();
        captureBuilder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
        builder.setDefaultCaptureConfig(captureBuilder.build());
        builder.setCaptureOptionUnpacker(Camera2CaptureOptionUnpacker.INSTANCE);

        try {
            // Add default lensFacing if we can
            CameraX.LensFacing checkedLensFacing =
                    (lensFacing != null) ? lensFacing : CameraX.getDefaultLensFacing();
            String defaultId = mCameraFactory.cameraIdForLensFacing(checkedLensFacing);
            if (defaultId != null) {
                builder.setLensFacing(checkedLensFacing);
            }

            int targetRotation = mWindowManager.getDefaultDisplay().getRotation();
            int rotationDegrees = CameraX.getCameraInfo(defaultId).getSensorRotationDegrees(
                    targetRotation);
            boolean isRotateNeeded = (rotationDegrees == 90 || rotationDegrees == 270);
            builder.setTargetRotation(targetRotation);
            builder.setTargetAspectRatio(
                    isRotateNeeded ? DEFAULT_ASPECT_RATIO_9_16 : DEFAULT_ASPECT_RATIO_16_9);
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine default lens facing for VideoCapture.", e);
        }

        return builder.build();
    }
}
