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

package thanatos.android.camera.core;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;

/**
 * Configuration containing options for configuring a Camera device.
 *
 * <p>This includes options for camera device intrinsics, such as the lens facing direction.
 *
 * @hide
 */
@RestrictTo(Scope.LIBRARY_GROUP)
public interface CameraDeviceConfig {

    // Option Declarations:
    // *********************************************************************************************

    /**
     * Option: camerax.core.camera.lensFacing
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    Config.Option<CameraX.LensFacing> OPTION_LENS_FACING =
            Config.Option.create("camerax.core.camera.lensFacing", CameraX.LensFacing.class);

    /**
     * Option: camerax.core.camera.cameraIdFilter
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    Config.Option<CameraIdFilter> OPTION_CAMERA_ID_FILTER =
            Config.Option.create("camerax.core.camera.cameraIdFilter", CameraIdFilter.class);

    // *********************************************************************************************

    /**
     * Returns the lens-facing direction of the camera being configured.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    CameraX.LensFacing getLensFacing(@Nullable CameraX.LensFacing valueIfMissing);

    /**
     * Retrieves the lens facing direction for the primary camera to be configured.
     *
     * @return The stored value, if it exists in this configuration.
     * @throws IllegalArgumentException if the option does not exist in this configuration.
     */
    @NonNull
    CameraX.LensFacing getLensFacing();

    /**
     * Retrieves the {@link CameraIdFilter} that filter out the unavailable camera ids.
     *
     * @param valueIfMissing The value to return if this configuration option has not been set.
     * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
     * configuration.
     */
    @Nullable
    CameraIdFilter getCameraIdFilter(@Nullable CameraIdFilter valueIfMissing);

    /**
     * Retrieves the {@link CameraIdFilter} that filter out the unavailable camera ids.
     *
     * @return The stored value, if it exists in this configuration.
     * @throws IllegalArgumentException if the option does not exist in this configuration.
     */
    @NonNull
    CameraIdFilter getCameraIdFilter();

    /**
     * Builder for a {@link CameraDeviceConfig}.
     *
     * @param <B> The top level builder type for which this builder is composed with.
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    interface Builder<B> {

        /**
         * Sets the primary camera to be configured based on the direction the lens is facing.
         *
         * <p>If multiple cameras exist with equivalent lens facing direction, the first ("primary")
         * camera for that direction will be chosen.
         *
         * @param lensFacing The direction of the camera's lens.
         * @return the current Builder.
         */
        B setLensFacing(@NonNull CameraX.LensFacing lensFacing);

        /**
         * Sets the {@link CameraIdFilter} that filter out the unavailable camera ids.
         *
         * @param cameraIdFilter The {@link CameraIdFilter}.
         * @return the current Builder.
         */
        B setCameraIdFilter(@NonNull CameraIdFilter cameraIdFilter);
    }
}
