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
import thanatos.android.camera.core.ImageCapture;
import thanatos.android.camera.core.ImageCaptureConfig;
import thanatos.android.camera.core.SessionConfig;

/**
 * Provides defaults for {@link ImageCaptureConfig} in the Camera2 implementation.
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class ImageCaptureConfigProvider implements ConfigProvider<ImageCaptureConfig> {
    private static final String TAG = "ImageCaptureProvider";
    private static final Rational DEFAULT_ASPECT_RATIO_4_3 = new Rational(4, 3);
    private static final Rational DEFAULT_ASPECT_RATIO_3_4 = new Rational(3, 4);

    private final CameraFactory mCameraFactory;
    private final WindowManager mWindowManager;

    public ImageCaptureConfigProvider(CameraFactory cameraFactory, Context context) {
        mCameraFactory = cameraFactory;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public ImageCaptureConfig getConfig(CameraX.LensFacing lensFacing) {
        ImageCaptureConfig.Builder builder =
                ImageCaptureConfig.Builder.fromConfig(
                        ImageCapture.DEFAULT_CONFIG.getConfig(lensFacing));

        // SessionConfig containing all intrinsic properties needed for ImageCapture
        SessionConfig.Builder sessionBuilder = new SessionConfig.Builder();
        // TODO(b/114762170): Must set to preview here until we allow for multiple template types
        sessionBuilder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);

        // Add options to UseCaseConfig
        builder.setDefaultSessionConfig(sessionBuilder.build());
        builder.setSessionOptionUnpacker(Camera2SessionOptionUnpacker.INSTANCE);

        CaptureConfig.Builder captureConfig = new CaptureConfig.Builder();
        captureConfig.setTemplateType(CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.setDefaultCaptureConfig(captureConfig.build());
        builder.setCaptureOptionUnpacker(ImageCaptureOptionUnpacker.INSTANCE);

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
                    isRotateNeeded ? DEFAULT_ASPECT_RATIO_3_4 : DEFAULT_ASPECT_RATIO_4_3);
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine default lens facing for ImageCapture.", e);
        }

        return builder.build();
    }
}
