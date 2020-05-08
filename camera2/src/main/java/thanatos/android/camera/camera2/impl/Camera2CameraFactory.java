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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import thanatos.android.camera.core.BaseCamera;
import thanatos.android.camera.core.CameraFactory;
import thanatos.android.camera.core.CameraInfoUnavailableException;
import thanatos.android.camera.core.CameraX;
import thanatos.android.camera.core.CameraXThreads;
import thanatos.android.camera.core.LensFacingCameraIdFilter;
import thanatos.android.camera.core.impl.utils.executor.CameraXExecutors;

/**
 * The factory class that creates {@link Camera} instances.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class Camera2CameraFactory implements CameraFactory {
    private static final String TAG = "Camera2CameraFactory";

    private static final int DEFAULT_ALLOWED_CONCURRENT_OPEN_CAMERAS = 1;

    private static final HandlerThread sHandlerThread = new HandlerThread(CameraXThreads.TAG);
    private static final Handler sHandler;
    private final CameraAvailabilityRegistry mAvailabilityRegistry;

    static {
        sHandlerThread.start();
        sHandler = new Handler(sHandlerThread.getLooper());
    }

    private final CameraManager mCameraManager;

    public Camera2CameraFactory(@NonNull Context context) {
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        mAvailabilityRegistry = new CameraAvailabilityRegistry(
                DEFAULT_ALLOWED_CONCURRENT_OPEN_CAMERAS,
                CameraXExecutors.newHandlerExecutor(sHandler));
    }

    @Override
    @NonNull
    public BaseCamera getCamera(@NonNull String cameraId) {
        Camera camera = new Camera(mCameraManager, cameraId,
                mAvailabilityRegistry.getAvailableCameraCount(), sHandler);
        mAvailabilityRegistry.registerCamera(camera);
        return camera;
    }

    @Override
    @NonNull
    public Set<String> getAvailableCameraIds() throws CameraInfoUnavailableException {
        List<String> camerasList = null;
        try {
            camerasList = Arrays.asList(mCameraManager.getCameraIdList());
        } catch (CameraAccessException e) {
            throw new CameraInfoUnavailableException(
                    "Unable to retrieve list of cameras on device.", e);
        }
        // Use a LinkedHashSet to preserve order
        return new LinkedHashSet<>(camerasList);
    }

    @Override
    @Nullable
    public String cameraIdForLensFacing(@NonNull CameraX.LensFacing lensFacing)
            throws CameraInfoUnavailableException {
        Set<String> availableCameraIds = getLensFacingCameraIdFilter(
                lensFacing).filter(getAvailableCameraIds());

        if (!availableCameraIds.isEmpty()) {
            return availableCameraIds.iterator().next();
        } else {
            return null;
        }
    }

    @Override
    @NonNull
    public LensFacingCameraIdFilter getLensFacingCameraIdFilter(@NonNull CameraX.LensFacing lensFacing) {
        return new Camera2LensFacingCameraIdFilter(mCameraManager, lensFacing);
    }
}
