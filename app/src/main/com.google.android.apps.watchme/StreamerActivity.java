/*
 * Copyright (c) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.watchme;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ToggleButton;

import com.google.android.apps.watchme.util.Utils;
import com.google.android.apps.watchme.util.YouTubeApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Ibrahim Ulukaya <ulukaya@google.com>
 *         <p/>
 *         StreamerActivity class which previews the camera and streams via StreamerService.
 */
public class StreamerActivity extends Activity {
    // CONSTANTS
    // TODO: Stop hardcoding this and read values from the camera's supported sizes.
    public static final int CAMERA_WIDTH = 640;
    public static final int CAMERA_HEIGHT = 480;

    private static final String     TAG = StreamerActivity.class.getName();
    private static final int        REQUEST_CODE= 100;
    private static MediaProjection  MEDIA_PROJECTION;
    private static String           STORE_DIRECTORY;
    private static int              IMAGES_PRODUCED;

    private MediaProjectionManager  mProjectionManager;
    private ImageReader             mImageReader;
    private Handler mHandler;
    private int                     mWidth;
    private int                     mHeight;

    // Member variables
    private StreamerService streamerService;
    private ServiceConnection streamerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(MainActivity.APP_NAME, "onServiceConnected");

            streamerService = ((StreamerService.LocalBinder) service).getService();

            restoreStateFromService();
            startStreaming();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(MainActivity.APP_NAME, "onServiceDisconnected");

            // This should never happen, because our service runs in the same process.
            streamerService = null;
        }
    };
    private PowerManager.WakeLock wakeLock;
    private Preview preview;
    private String rtmpUrl;
    private String broadcastId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(MainActivity.APP_NAME, "onCreate");
        super.onCreate(savedInstanceState);

        broadcastId = getIntent().getStringExtra(YouTubeApi.BROADCAST_ID_KEY);
        //Log.v(MainActivity.APP_NAME, broadcastId);

        rtmpUrl = getIntent().getStringExtra(YouTubeApi.RTMP_URL_KEY);

        if (rtmpUrl == null) {
            Log.w(MainActivity.APP_NAME, "No RTMP URL was passed in; bailing.");
            finish();
        }
        Log.i(MainActivity.APP_NAME, String.format("Got RTMP URL '%s' from calling activity.", rtmpUrl));

        setContentView(R.layout.streamer);
        preview = (Preview) findViewById(R.id.surfaceViewPreview);

        if (!bindService(new Intent(this, StreamerService.class), streamerConnection,
                BIND_AUTO_CREATE | BIND_DEBUG_UNBIND)) {
            Log.e(MainActivity.APP_NAME, "Failed to bind StreamerService!");
        }

        final ToggleButton toggleButton = (ToggleButton) findViewById(R.id.toggleBroadcasting);
        toggleButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (toggleButton.isChecked()) {
                    streamerService.startStreaming(rtmpUrl);
                } else {
                    streamerService.stopStreaming();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        Log.d(MainActivity.APP_NAME, "onResume");

        super.onResume();

        if (streamerService != null) {
            restoreStateFromService();
        }
    }

    @Override
    protected void onPause() {
        Log.d(MainActivity.APP_NAME, "onPause");

        super.onPause();

        if (preview != null) {
            preview.setCamera(null);
        }

        if (streamerService != null) {
            streamerService.releaseCamera();
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(MainActivity.APP_NAME, "onDestroy");

        super.onDestroy();

        if (streamerConnection != null) {
            unbindService(streamerConnection);
        }

        stopStreaming();

        if (streamerService != null) {
            streamerService.releaseCamera();
        }
    }

    private void restoreStateFromService() {
        preview.setCamera(Utils.getCamera(Camera.CameraInfo.CAMERA_FACING_FRONT));
    }

    private void startStreaming() {
        Log.d(MainActivity.APP_NAME, "startStreaming");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, this.getClass().getName());
        wakeLock.acquire();

        if (!streamerService.isStreaming()) {
            streamerService.startStreaming(rtmpUrl);
        }
    }

    private void stopStreaming() {
        Log.d(MainActivity.APP_NAME, "stopStreaming");

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }

        if (streamerService.isStreaming()) {
            streamerService.stopStreaming();
        }
    }

    public void endEvent(View view) {
        Intent data = new Intent();
        data.putExtra(YouTubeApi.BROADCAST_ID_KEY, broadcastId);
        if (getParent() == null) {
            setResult(Activity.RESULT_OK, data);
        } else {
            getParent().setResult(Activity.RESULT_OK, data);
        }
        finish();
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            FileOutputStream fos = null;
            Bitmap bitmap = null;

            try {
                image = mImageReader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;

                    // create bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    // write bitmap to a file
                    fos = new FileOutputStream(STORE_DIRECTORY + "/myscreen_" + IMAGES_PRODUCED + ".png");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);

                    IMAGES_PRODUCED++;
                    Log.e(TAG, "captured image: " + IMAGES_PRODUCED);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos!=null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

                if (bitmap!=null) {
                    bitmap.recycle();
                }

                if (image!=null) {
                    image.close();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            MEDIA_PROJECTION = mProjectionManager.getMediaProjection(resultCode, data);

            if (MEDIA_PROJECTION != null) {
                STORE_DIRECTORY = Environment.getExternalStorageDirectory().getAbsolutePath()+ "/screenshots/";
                File storeDirectory = new File(STORE_DIRECTORY);
                if(!storeDirectory.exists()) {
                    boolean success = storeDirectory.mkdirs();
                    if(!success) {
                        Log.e(TAG, "failed to create file storage directory.");
                        return;
                    }
                }

                DisplayMetrics metrics = getResources().getDisplayMetrics();
                int density = metrics.densityDpi;
                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
                Display display = getWindowManager().getDefaultDisplay();
                Point size = new Point();
                display.getSize(size);
                mWidth = size.x;
                mHeight = size.y;

                mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
                MEDIA_PROJECTION.createVirtualDisplay("screencap", mWidth, mHeight, density, flags, mImageReader.getSurface(), null, mHandler);
                mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
            }
        }
    }

    private void startProjection() {
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    private void stopProjection() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if(MEDIA_PROJECTION != null) MEDIA_PROJECTION.stop();
            }
        });
    }
}
