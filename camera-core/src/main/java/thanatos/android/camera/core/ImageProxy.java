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

import android.graphics.Rect;
import android.media.Image;
import android.support.annotation.Nullable;

import java.nio.ByteBuffer;

/** An image proxy which has an analogous interface as {@link Image}. */
public interface ImageProxy extends AutoCloseable {
    /**
     * Closes the underlying {@link Image}.
     *
     * <p>If obtained from an {@link ImageAnalysis.Analyzer} the image will be closed on return
     * from the {@link ImageAnalysis.Analyzer} function.
     *
     * <p>@see {@link Image#close()}.
     */
    void close();

    /**
     * Returns the crop rectangle.
     *
     * <p>@see {@link Image#getCropRect()}.
     */
    Rect getCropRect();

    /**
     * Sets the crop rectangle.
     *
     * <p>@see {@link Image#setCropRect(Rect)}.
     */
    void setCropRect(Rect rect);

    /**
     * Returns the image format.
     *
     * <p>@see {@link Image#getFormat()}.
     */
    int getFormat();

    /**
     * Returns the image height.
     *
     * <p>@see {@link Image#getHeight()}.
     */
    int getHeight();

    /**
     * Returns the image width.
     *
     * <p>@see {@link Image#getWidth()}.
     */
    int getWidth();

    /**
     * Returns the timestamp.
     *
     * <p>@see {@link Image#getTimestamp()}.
     */
    long getTimestamp();

    /**
     * Sets the timestamp.
     *
     * <p>@see {@link Image#setTimestamp(long)}.
     */
    void setTimestamp(long timestamp);

    /**
     * Returns the array of planes.
     *
     * <p>@see {@link Image#getPlanes()}.
     */
    PlaneProxy[] getPlanes();

    /** A plane proxy which has an analogous interface as {@link Image.Plane}. */
    interface PlaneProxy {
        /**
         * Returns the row stride.
         *
         * <p>@see {@link Image.Plane#getRowStride()}.
         */
        int getRowStride();

        /**
         * Returns the pixel stride.
         *
         * <p>@see {@link Image.Plane#getPixelStride()}.
         */
        int getPixelStride();

        /**
         * Returns the pixels buffer.
         *
         * <p>@see {@link Image.Plane#getBuffer()}.
         */
        ByteBuffer getBuffer();
    }

    // TODO(b/123902197): HardwareBuffer access is provided on higher API levels. Wrap
    // getHardwareBuffer() once we figure out how to provide compatibility with lower API levels.

    /**
     * Returns the {@link ImageInfo}.
     *
     * <p> Will be null if there is no associated additional metadata.
     */
    @Nullable
    ImageInfo getImageInfo();

    /**
     * Returns the android {@link Image}.
     *
     * <p>If the ImageProxy is a wrapper for an android {@link Image}, it will return the
     * {@link Image}. It is possible for an ImageProxy to wrap something that isn't an
     * {@link Image}. If that's the case then it will return null.
     *
     * <p>The returned image should not be closed by the application. Instead it should be closed by
     * the ImageProxy, which happens, for example, on return from the {@link ImageAnalysis.Analyzer}
     * function.  Destroying the {@link ImageAnalysis} will close the underlying
     * {@link android.media.ImageReader}.  So an {@link Image} obtained with this method will behave
     * as such @see {@link Image#close()}.
     *
     * @return the android image.
     */
    @Nullable
    Image getImage();
}
