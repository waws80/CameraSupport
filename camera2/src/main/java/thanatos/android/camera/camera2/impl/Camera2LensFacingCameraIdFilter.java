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
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

import thanatos.android.camera.core.CameraX;
import thanatos.android.camera.core.LensFacingCameraIdFilter;

/**
 * Filter camera id by lens facing.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class Camera2LensFacingCameraIdFilter extends LensFacingCameraIdFilter {
    private static final String TAG = "Camera2LensFacingCIF";
    private CameraX.LensFacing mLensFacing;
    private CameraManager mCameraManager;

    Camera2LensFacingCameraIdFilter(@NonNull CameraManager cameraManager,
            @NonNull CameraX.LensFacing lensFacing) {
        mLensFacing = lensFacing;
        mCameraManager = cameraManager;
    }

    @Override
    @NonNull
    public Set<String> filter(@NonNull Set<String> cameraIdSet) {
        Set<String> resultCameraIdSet = new LinkedHashSet<>();

        for (String cameraId : cameraIdSet) {
            Integer lensFacingInteger = null;
            try {
                lensFacingInteger = mCameraManager.getCameraCharacteristics(cameraId).get(
                        CameraCharacteristics.LENS_FACING);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Unable to retrieve info for camera with id " + cameraId + ".", e);
            }
            if (lensFacingInteger == null) {
                continue;
            }
            if (lensFacingInteger.equals(cameraXLensFacingToCamera2LensFacing(mLensFacing))) {
                resultCameraIdSet.add(cameraId);
            }
        }

        return resultCameraIdSet;
    }

    @Override
    @NonNull
    public CameraX.LensFacing getLensFacing() {
        return mLensFacing;
    }

    private Integer cameraXLensFacingToCamera2LensFacing(CameraX.LensFacing lensFacing) {
        Integer lensFacingInteger = -1;
        switch (lensFacing) {
            case BACK:
                lensFacingInteger = CameraMetadata.LENS_FACING_BACK;
                break;
            case FRONT:
                lensFacingInteger = CameraMetadata.LENS_FACING_FRONT;
                break;
        }
        return lensFacingInteger;
    }
}
