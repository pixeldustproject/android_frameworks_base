/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpEvent;
import android.mtp.MtpObjectInfo;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/**
 * The model wrapping android.mtp API.
 */
class MtpManager {
    final static int OBJECT_HANDLE_ROOT_CHILDREN = -1;

    private final UsbManager mManager;
    // TODO: Save and restore the set of opened device.
    private final SparseArray<MtpDevice> mDevices = new SparseArray<>();

    MtpManager(Context context) {
        mManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    synchronized void openDevice(int deviceId) throws IOException {
        UsbDevice rawDevice = null;
        for (final UsbDevice candidate : mManager.getDeviceList().values()) {
            if (candidate.getDeviceId() == deviceId) {
                rawDevice = candidate;
                break;
            }
        }

        if (rawDevice == null) {
            throw new IOException("Not found USB device: " + deviceId);
        }

        if (!mManager.hasPermission(rawDevice)) {
            // Permission should be obtained via app selection dialog for intent.
            throw new IOException("No parmission to operate USB device.");
        }

        final MtpDevice device = new MtpDevice(rawDevice);

        final UsbDeviceConnection connection = mManager.openDevice(rawDevice);
        if (connection == null) {
            throw new IOException("Failed to open a USB connection.");
        }

        if (!device.open(connection)) {
            throw new IOException("Failed to open a MTP device.");
        }

        // Handle devices that fail to obtain storages just after opening a MTP session.
        final int[] storageIds = device.getStorageIds();
        if (storageIds == null) {
            throw new IOException("Not found MTP storages in the device.");
        }

        mDevices.put(deviceId, device);
    }

    synchronized void closeDevice(int deviceId) throws IOException {
        getDevice(deviceId).close();
        mDevices.remove(deviceId);
    }

    synchronized int[] getOpenedDeviceIds() {
        final int[] result = new int[mDevices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = mDevices.keyAt(i);
        }
        return result;
    }

    MtpRoot[] getRoots(int deviceId) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final int[] storageIds = device.getStorageIds();
            if (storageIds == null) {
                throw new IOException("Failed to obtain storage IDs.");
            }
            final MtpRoot[] results = new MtpRoot[storageIds.length];
            for (int i = 0; i < storageIds.length; i++) {
                results[i] = new MtpRoot(
                        device.getDeviceId(),
                        device.getDeviceInfo().getModel(),
                        device.getStorageInfo(storageIds[i]));
            }
            return results;
        }
    }

    MtpObjectInfo getObjectInfo(int deviceId, int objectHandle)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getObjectInfo(objectHandle);
        }
    }

    int[] getObjectHandles(int deviceId, int storageId, int parentObjectHandle)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getObjectHandles(storageId, 0 /* all format */, parentObjectHandle);
        }
    }

    byte[] getObject(int deviceId, int objectHandle, int expectedSize)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getObject(objectHandle, expectedSize);
        }
    }

    byte[] getThumbnail(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getThumbnail(objectHandle);
        }
    }

    void deleteDocument(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            if (!device.deleteObject(objectHandle)) {
                throw new IOException("Failed to delete document");
            }
        }
    }

    int createDocument(int deviceId, MtpObjectInfo objectInfo,
            ParcelFileDescriptor source) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final MtpObjectInfo sendObjectInfoResult = device.sendObjectInfo(objectInfo);
            if (sendObjectInfoResult == null) {
                throw new IOException("Failed to create a document");
            }
            if (objectInfo.getFormat() != MtpConstants.FORMAT_ASSOCIATION) {
                if (!device.sendObject(sendObjectInfoResult.getObjectHandle(),
                        sendObjectInfoResult.getCompressedSize(), source)) {
                    throw new IOException("Failed to send contents of a document");
                }
            }
            return sendObjectInfoResult.getObjectHandle();
        }
    }

    int getParent(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final int result = (int) device.getParent(objectHandle);
            if (result < 0) {
                throw new FileNotFoundException("Not found parent object");
            }
            return result;
        }
    }

    void importFile(int deviceId, int objectHandle, ParcelFileDescriptor target)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            device.importFile(objectHandle, target);
        }
    }

    @VisibleForTesting
    MtpEvent readEvent(int deviceId, CancellationSignal signal) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        return device.readEvent(signal);
    }

    private synchronized MtpDevice getDevice(int deviceId) throws IOException {
        final MtpDevice device = mDevices.get(deviceId);
        if (device == null) {
            throw new IOException("USB device " + deviceId + " is not opened.");
        }
        return device;
    }
}
