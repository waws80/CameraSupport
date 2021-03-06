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

import android.support.annotation.RestrictTo;

/**
 * A class which provides a {@link Config} object.
 *
 * @param <C> the {@link Config} type provided
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface ConfigProvider<C extends Config> {

    /** Retrieve the {@link Config} object.
     *
     * @param lensFacing The {@link CameraX.LensFacing} that the configuration provider will
     *                   target to.
     * */
    C getConfig(CameraX.LensFacing lensFacing);
}
