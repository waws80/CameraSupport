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

import android.content.Context;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;

import thanatos.android.camera.camera2.impl.Camera2CameraFactory;
import thanatos.android.camera.camera2.impl.Camera2DeviceSurfaceManager;
import thanatos.android.camera.camera2.impl.ImageAnalysisConfigProvider;
import thanatos.android.camera.camera2.impl.ImageCaptureConfigProvider;
import thanatos.android.camera.camera2.impl.PreviewConfigProvider;
import thanatos.android.camera.camera2.impl.VideoCaptureConfigProvider;
import thanatos.android.camera.core.AppConfig;
import thanatos.android.camera.core.CameraDeviceSurfaceManager;
import thanatos.android.camera.core.CameraFactory;
import thanatos.android.camera.core.ExtendableUseCaseConfigFactory;
import thanatos.android.camera.core.ImageAnalysisConfig;
import thanatos.android.camera.core.ImageCaptureConfig;
import thanatos.android.camera.core.PreviewConfig;
import thanatos.android.camera.core.VideoCaptureConfig;

/**
 * Convenience class for generating a pre-populated Camera2 {@link AppConfig}.
 *
 * @hide Until CameraX.init() is made public
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class Camera2AppConfig {

    private Camera2AppConfig() {
    }

    /**
     * Creates the {@link AppConfig} containing the Camera2 implementation pieces for
     * CameraX.
     */
    public static AppConfig create(Context context) {
        // Create the camera factory for creating Camera2 camera objects
        CameraFactory cameraFactory = new Camera2CameraFactory(context);

        // Create the DeviceSurfaceManager for Camera2
        CameraDeviceSurfaceManager surfaceManager = new Camera2DeviceSurfaceManager(context);

        // Create default configuration factory
        ExtendableUseCaseConfigFactory configFactory = new ExtendableUseCaseConfigFactory();
        configFactory.installDefaultProvider(
                ImageAnalysisConfig.class, new ImageAnalysisConfigProvider(cameraFactory, context));
        configFactory.installDefaultProvider(
                ImageCaptureConfig.class, new ImageCaptureConfigProvider(cameraFactory, context));
        configFactory.installDefaultProvider(
                VideoCaptureConfig.class, new VideoCaptureConfigProvider(cameraFactory, context));
        configFactory.installDefaultProvider(
                PreviewConfig.class, new PreviewConfigProvider(cameraFactory, context));

        AppConfig.Builder appConfigBuilder =
                new AppConfig.Builder()
                        .setCameraFactory(cameraFactory)
                        .setDeviceSurfaceManager(surfaceManager)
                        .setUseCaseConfigFactory(configFactory);

        return appConfigBuilder.build();
    }
}
