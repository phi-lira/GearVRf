/* Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gearvrf.io;

import org.gearvrf.GVRContext;
import org.gearvrf.GVRScene;
import org.gearvrf.utility.Log;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import android.util.SparseArray;
import android.view.InputDevice;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Use this class to translate MotionEvents generated by a mouse to manipulate
 * {@link GVRMouseController}s.
 * 
 */
class GVRMouseDeviceManager {
    private static final String TAG = GVRMouseDeviceManager.class
            .getSimpleName();
    private static final String THREAD_NAME = "GVRMouseManagerThread";
    private EventHandlerThread thread;
    private SparseArray<GVRMouseController> controllers;
    private boolean threadStarted = false;

    GVRMouseDeviceManager(Context context) {
        thread = new EventHandlerThread(THREAD_NAME);
        controllers = new SparseArray<GVRMouseController>();
    }

    GVRBaseController getCursorController(GVRContext context) {
        Log.d(TAG, "Creating Mouse Device");
        if (threadStarted == false) {
            Log.d(TAG, "Starting " + THREAD_NAME);
            thread.start();
            thread.prepareHandler();
            threadStarted = true;
        }
        GVRMouseController controller = new GVRMouseController(context,
                GVRCursorType.MOUSE, thread);
        int id = controller.getId();
        controllers.append(id, controller);
        return controller;
    }

    void removeCursorController(GVRBaseController controller) {
        int id = controller.getId();
        controllers.remove(id);

        // stop the thread if no more devices are online
        if (controllers.size() == 0 && threadStarted) {
            Log.d(TAG, "Stopping " + THREAD_NAME);
            thread.quitSafely();
            thread = new EventHandlerThread(THREAD_NAME);
            threadStarted = false;
        }
    }

    static class GVRMouseController extends GVRBaseController {

        private EventHandlerThread thread;
        private GVRContext context;
        private float x = 0.0f, y = 0.0f, z = -1.0f;

        GVRMouseController(GVRContext context, GVRCursorType cursorType,
                EventHandlerThread thread) {
            super(cursorType);
            this.context = context;
            this.thread = thread;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            // Not used
            return false;
        }

        @Override
        public boolean dispatchMotionEvent(MotionEvent event) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                return thread.submitMotionEvent(getId(), event);
            } else {
                return false;
            }
        }

        private void processMouseEvent(float x, float y, float z,
                boolean active) {
            GVRScene scene = context.getMainScene();
            if (scene != null) {
                float depth = this.z;
                if (((depth + z) <= getNearDepth())
                        && ((depth + z) >= getFarDepth())) {
                    float frustumWidth, frustumHeight;
                    depth = depth + z;

                    // calculate the frustum using the aspect ratio and FOV
                    // http://docs.unity3d.com/Manual/FrustumSizeAtDistance.html
                    float aspectRatio = scene.getMainCameraRig()
                            .getCenterCamera().getAspectRatio();
                    float fovY = scene.getMainCameraRig().getCenterCamera()
                            .getFovY();
                    float frustumHeightMultiplier = (float) Math
                            .tan(Math.toRadians(fovY / 2)) * 2.0f;
                    frustumHeight = frustumHeightMultiplier * depth;
                    frustumWidth = frustumHeight * aspectRatio;

                    this.x = frustumWidth * -x;
                    this.y = frustumHeight * -y;
                    this.z = depth;
                }
                setActive(active);
                super.setPosition(this.x, this.y, this.z);
            }
        }

        @Override
        public void setPosition(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            super.setPosition(x, y, z);
        }
    }

    private class EventHandlerThread extends HandlerThread {
        private Handler handler;
        private boolean isActive = false;
        private float x, y, z;

        EventHandlerThread(String name) {
            super(name);
        }

        void prepareHandler() {
            handler = new Handler(getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    MotionEvent motionEvent = (MotionEvent) msg.obj;
                    int id = msg.arg1;
                    dispatchMotionEvent(id, motionEvent);
                    motionEvent.recycle();
                }
            };
        }

        boolean submitMotionEvent(int id, MotionEvent event) {
            if (threadStarted) {
                MotionEvent clone = MotionEvent.obtain(event);

                Message message = Message.obtain(null, 0, id, 0, clone);
                return handler.sendMessage(message);
            }
            return false;
        }

        // The following methods are taken from the controller sample on the
        // Android Developer web site:
        // https://developer.android.com/training/game-controllers/controller-input.html
        private void dispatchMotionEvent(int id, MotionEvent event) {
            if (id != -1) {
                final int historySize = event.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    processMouseInput(id, event, i);
                }
                processMouseInput(id, event, -1);
            }
        }

        private void processMouseInput(int uniqueId, MotionEvent motionEvent,
                int historyPos) {
            if (getNormalizedCoordinates(motionEvent)) {
                GVRMouseController device = controllers.get(uniqueId);
                device.processMouseEvent(this.x, this.y, this.z, isActive);
            }
        }

        // Retrieves the normalized coordinates (-1 to 1) for any given (x,y)
        // value reported by the Android MotionEvent.
        private boolean getNormalizedCoordinates(MotionEvent motionEvent) {
            InputDevice device = motionEvent.getDevice();
            if (device != null) {
                InputDevice.MotionRange range = device.getMotionRange(
                        MotionEvent.AXIS_X, motionEvent.getSource());
                float x = range.getMax() + 1;
                range = motionEvent.getDevice().getMotionRange(
                        MotionEvent.AXIS_Y, motionEvent.getSource());
                float y = range.getMax() + 1;
                this.x = (motionEvent.getX() / x * 2.0f - 1.0f);
                this.y = 1.0f - motionEvent.getY() / y * 2.0f;
                if (motionEvent.getAction() == MotionEvent.ACTION_SCROLL) {
                    this.z = (motionEvent
                            .getAxisValue(MotionEvent.AXIS_VSCROLL) > 0 ? -1
                                    : 1);
                } else {
                    this.z = 0;
                }

                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    this.isActive = true;
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    this.isActive = false;
                }
                return true;
            }
            return false;
        }
    }

    void stop() {
        if (threadStarted) {
            thread.quitSafely();
        }
    }
}