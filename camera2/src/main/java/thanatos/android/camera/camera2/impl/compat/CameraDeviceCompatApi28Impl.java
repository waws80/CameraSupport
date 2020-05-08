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

package thanatos.android.camera.camera2.impl.compat;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.params.SessionConfiguration;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;

import thanatos.android.camera.camera2.impl.compat.params.SessionConfigurationCompat;
import thanatos.android.camera.core.external.Preconditions;

@RequiresApi(28)
class CameraDeviceCompatApi28Impl extends CameraDeviceCompatApi24Impl {

    @Override
    public void createCaptureSession(@NonNull CameraDevice device,
            @NonNull SessionConfigurationCompat config) throws CameraAccessException {
        Preconditions.checkNotNull(device);

        SessionConfiguration sessionConfig = (SessionConfiguration) config.unwrap();
        Preconditions.checkNotNull(sessionConfig);

        device.createCaptureSession(sessionConfig);
    }
}
