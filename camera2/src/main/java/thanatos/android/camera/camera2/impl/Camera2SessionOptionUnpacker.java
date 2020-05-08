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

import android.hardware.camera2.CaptureRequest;

import thanatos.android.camera.camera2.Camera2Config;
import thanatos.android.camera.core.CameraCaptureSessionStateCallbacks;
import thanatos.android.camera.core.CameraDeviceStateCallbacks;
import thanatos.android.camera.core.Config;
import thanatos.android.camera.core.MutableOptionsBundle;
import thanatos.android.camera.core.OptionsBundle;
import thanatos.android.camera.core.SessionConfig;
import thanatos.android.camera.core.UseCaseConfig;

/**
 * A {@link SessionConfig.OptionUnpacker} implementation for unpacking Camera2 options into a
 * {@link SessionConfig.Builder}.
 */
final class Camera2SessionOptionUnpacker implements SessionConfig.OptionUnpacker {

    static final Camera2SessionOptionUnpacker INSTANCE = new Camera2SessionOptionUnpacker();

    @Override
    public void unpack(UseCaseConfig<?> config, final SessionConfig.Builder builder) {
        SessionConfig defaultSessionConfig =
                config.getDefaultSessionConfig(/*valueIfMissing=*/ null);

        Config implOptions = OptionsBundle.emptyBundle();
        int templateType = SessionConfig.defaultEmptySessionConfig().getTemplateType();

        // Apply/extract defaults from session config
        if (defaultSessionConfig != null) {
            templateType = defaultSessionConfig.getTemplateType();
            builder.addAllDeviceStateCallbacks(defaultSessionConfig.getDeviceStateCallbacks());
            builder.addAllSessionStateCallbacks(defaultSessionConfig.getSessionStateCallbacks());
            builder.addAllRepeatingCameraCaptureCallbacks(
                    defaultSessionConfig.getRepeatingCameraCaptureCallbacks());
            implOptions = defaultSessionConfig.getImplementationOptions();
        }

        // Set the any additional implementation options
        builder.setImplementationOptions(implOptions);

        // Get Camera2 extended options
        final Camera2Config camera2Config = new Camera2Config(config);

        // Apply template type
        builder.setTemplateType(camera2Config.getCaptureRequestTemplate(templateType));

        // Add extension callbacks
        builder.addDeviceStateCallback(
                camera2Config.getDeviceStateCallback(
                        CameraDeviceStateCallbacks.createNoOpCallback()));
        builder.addSessionStateCallback(
                camera2Config.getSessionStateCallback(
                        CameraCaptureSessionStateCallbacks.createNoOpCallback()));
        builder.addCameraCaptureCallback(
                CaptureCallbackContainer.create(
                        camera2Config.getSessionCaptureCallback(
                                Camera2CaptureCallbacks.createNoOpCallback())));

        MutableOptionsBundle cameraEventConfig = MutableOptionsBundle.create();
        cameraEventConfig.insertOption(Camera2Config.CAMERA_EVENT_CALLBACK_OPTION,
                camera2Config.getCameraEventCallback(CameraEventCallbacks.createEmptyCallback()));
        builder.addImplementationOptions(cameraEventConfig);

        // Copy extension keys
        Camera2Config.Builder configBuilder = new Camera2Config.Builder();
        for (Config.Option<?> option : camera2Config.getCaptureRequestOptions()) {
            @SuppressWarnings("unchecked")
            // No way to get actual type info here, so treat as Object
                    Config.Option<Object> typeErasedOption = (Config.Option<Object>) option;
            @SuppressWarnings("unchecked")
            CaptureRequest.Key<Object> key = (CaptureRequest.Key<Object>) option.getToken();
            configBuilder.setCaptureRequestOption(key,
                    camera2Config.retrieveOption(typeErasedOption));
        }
        builder.addImplementationOptions(configBuilder.build());
    }
}
