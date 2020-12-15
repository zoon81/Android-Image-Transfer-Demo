package com.nordicsemi.ImageTransferDemo;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class ImageTransferService extends Service {
    private final static String TAG = "lbs_tag_service";//ImageTransferService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private volatile boolean isWriting;
    private Queue<byte[]> sendQueue; //To be inited with sendQueue = new ConcurrentLinkedQueue<String>();
    private BluetoothGattCharacteristic FtChar;
    private int current_mtu_size;
    private int bulk_data_written;


    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.nordicsemi.ImageTransferDemo.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.nordicsemi.ImageTransferDemo.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.nordicsemi.ImageTransferDemo.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.nordicsemi.ImageTransferDemo.ACTION_DATA_AVAILABLE";
    public final static String ACTION_DATA_WRITTEN =
            "com.nordicsemi.ImageTransferDemo.ACTION_GATT_TRANSFER_FINISHED";
    public final static String ACTION_IMG_INFO_AVAILABLE =
            "com.nordicsemi.ImageTransferDemo.ACTION_IMG_INFO_AVAILABLE";
    public final static String ACTION_FTS_NOTIFICATION =
            "com.nordicsemi.ImageTransferDemo.ACTION_IMG_INFO_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.nordicsemi.ImageTransferDemo.EXTRA_DATA";
    public final static String DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER =
            "com.nordicsemi.ImageTransferDemo.DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER";
    public final static String ACTION_GATT_TRANSFER_FINISHED =
            "com.nordicsemi.ImageTransferDemo.ACTION_GATT_TRANSFER_FINISHED";

    public static final UUID TX_POWER_UUID = UUID.fromString("00001804-0000-1000-8000-00805f9b34fb");
    public static final UUID TX_POWER_LEVEL_UUID = UUID.fromString("00002a07-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID FIRMWARE_REVISON_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static final UUID DIS_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");

    public static final UUID IMAGE_TRANSFER_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID RX_CHAR_UUID       = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID TX_CHAR_UUID       = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID IMG_INFO_CHAR_UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID INCOMING_FILE_CHAR_UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID NOTIFICATION_CHAR_UUID = UUID.fromString("6e400006-b5a3-f393-e0a9-e50e24dcca3e");

    public static final int targetMtu = 243;
    public static final int smallestSupportedMtu = 123;

    private static final  int target_mtu_payload_size = targetMtu - 3;
    private static final  int smallestSupported_mtu_payload_size = smallestSupportedMtu - 3;

    private static final int BULK_DATA_LEN = 68 * target_mtu_payload_size; // This cache can store up to 68 payloads

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "mBluetoothGatt = " + mBluetoothGatt );

                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);

            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);

            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if(IMG_INFO_CHAR_UUID.equals(characteristic.getUuid())) {
                broadcastUpdate(ACTION_IMG_INFO_AVAILABLE, characteristic);
            }
            if(NOTIFICATION_CHAR_UUID.equals(characteristic.getUuid())){
                broadcastUpdate(ACTION_FTS_NOTIFICATION, characteristic);
            }
            else {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("TAG","onCharacteristicWrite(): Successful");
                if(isWriting){
                    isWriting = false;
                    boolean isWriteInProgress = _send();
                    if(isWriteInProgress){
                        broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                    } else {
                        broadcastUpdate(ACTION_GATT_TRANSFER_FINISHED);
                    }
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.w(TAG, "OnDescWrite!!!");
            if(TX_CHAR_UUID.equals(descriptor.getCharacteristic().getUuid())) {
                // When the first notification is set we can set all others
                BluetoothGattService ImageTransferService = mBluetoothGatt.getService(IMAGE_TRANSFER_SERVICE_UUID);
                BluetoothGattCharacteristic ImgInfoChar = ImageTransferService.getCharacteristic(IMG_INFO_CHAR_UUID);
                if (ImgInfoChar == null) {
                    showMessage("Img Info characteristic not found!");
                    broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
                    return;
                }
                mBluetoothGatt.setCharacteristicNotification(ImgInfoChar, true);

                BluetoothGattDescriptor descriptor2 = ImgInfoChar.getDescriptor(CCCD);
                descriptor2.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothGatt.writeDescriptor(descriptor2);

                BluetoothGattCharacteristic NotiChar = ImageTransferService.getCharacteristic(NOTIFICATION_CHAR_UUID);
                if (ImgInfoChar == null) {
                    showMessage("Notification characteristic not found!");
                    broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
                    return;
                }
                mBluetoothGatt.setCharacteristicNotification(ImgInfoChar, true);

                BluetoothGattDescriptor descriptor3 = ImgInfoChar.getDescriptor(CCCD);
                descriptor2.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothGatt.writeDescriptor(descriptor3);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.w(TAG, "MTU changed: " + String.valueOf(mtu));
            current_mtu_size = mtu;
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (TX_CHAR_UUID.equals(characteristic.getUuid())) {

            // Log.d(TAG, String.format("Received TX: %d",characteristic.getValue() ));
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        } else if(IMG_INFO_CHAR_UUID.equals(characteristic.getUuid())) {
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        } else if (INCOMING_FILE_CHAR_UUID.equals(characteristic.getUuid())){
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        } else if (NOTIFICATION_CHAR_UUID.equals(characteristic.getUuid())){
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        } else {

        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        ImageTransferService getService() {
            return ImageTransferService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }
        sendQueue = new ConcurrentLinkedQueue<byte[]>();
        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        //mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        mBluetoothGatt = device.connectGatt(this, true, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public boolean isConnected(){
        return (mConnectionState == STATE_CONNECTED);
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
        // mBluetoothGatt.close();
    }

    public void requestMtu(int mtu){
        Log.i(TAG, "Requesting " + mtu + " byte MTU");
        mBluetoothGatt.requestMtu(mtu);
        current_mtu_size = mtu;
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        Log.w(TAG, "mBluetoothGatt closed");
        mBluetoothDeviceAddress = null;
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    /*
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);


        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }*/

    /**
     * Enable TXNotification
     *
     * @return
     */
    public void enableTXNotification() {
        Log.w(TAG, "enable TX not.");
        BluetoothGattService ImageTransferService = mBluetoothGatt.getService(IMAGE_TRANSFER_SERVICE_UUID);
        if (ImageTransferService == null) {
            showMessage("Rx service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }

        BluetoothGattCharacteristic TxChar = ImageTransferService.getCharacteristic(TX_CHAR_UUID);
        if (TxChar == null) {
            showMessage("Tx characteristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(TxChar, true);

        BluetoothGattDescriptor descriptor = TxChar.getDescriptor(CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);


    }

    public void writeRXCharacteristic(byte[] value)
    {
        BluetoothGattService RxService = mBluetoothGatt.getService(IMAGE_TRANSFER_SERVICE_UUID);
        if (RxService == null) {
            showMessage("Rx service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }
        BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(RX_CHAR_UUID);
        if (RxChar == null) {
            showMessage("Rx characteristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }
        RxChar.setValue(value);
        boolean status = mBluetoothGatt.writeCharacteristic(RxChar);

        Log.d(TAG, "write TXchar - status=" + status);
    }

    public void writeIncomingFileCharacteristic(byte[] data)
    {
        BluetoothGattService RxService = mBluetoothGatt.getService(IMAGE_TRANSFER_SERVICE_UUID);
        if (RxService == null) {
            showMessage("Rx service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }
        FtChar = RxService.getCharacteristic(INCOMING_FILE_CHAR_UUID);
        if (FtChar == null) {
            showMessage("FileTransfer characteristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_IMAGE_TRANSFER);
            return;
        }
        int counter = 0;
        while (counter <= data.length - smallestSupported_mtu_payload_size) {
            sendQueue.add(Arrays.copyOfRange(data, counter, counter + smallestSupported_mtu_payload_size));
            counter += smallestSupported_mtu_payload_size;
        }
        if(data.length - counter > 0){
            sendQueue.add(Arrays.copyOfRange(data, counter, data.length));
        }
    }

    public boolean fts_start_transmit(){
        if( isWriting ){
            return false;
        }
        bulk_data_written = 0;
        return _send();
    }

    private boolean _send() {
        if (sendQueue.isEmpty()) {
            Log.d("TAG", "_send(): EMPTY QUEUE");
            return false;
        }

        if(bulk_data_written >= BULK_DATA_LEN){
            Log.d("TAG", "_send(): Bulk data transfer limit reached");
            isWriting = false;
            return false;
        }

        int nof_elements = (current_mtu_size - 3) / (smallestSupported_mtu_payload_size);
        if( sendQueue.size() < nof_elements){
            nof_elements = sendQueue.size();
        }

        int current_payload_size = nof_elements * smallestSupported_mtu_payload_size;
        // Log.d(TAG, "_send(): Sending: " + current_payload_size + " byte. Pulling " + nof_elements + " number of elements from queue.");
        byte[] payload = new byte[current_payload_size];
        for(int i = 0; i < nof_elements; i++){
            byte[] queue_data = sendQueue.poll();
            if(sendQueue.size() < 10){
                Log.i("TAG", "queue almost empty");
            }
            System.arraycopy(queue_data, 0, payload, i * smallestSupported_mtu_payload_size,  queue_data.length);
        }
        FtChar.setValue(payload);
        isWriting = true; // Set the write in progress flag
        bulk_data_written += current_payload_size;
        return mBluetoothGatt.writeCharacteristic(FtChar);
    }

    public void sendCommand(int command, byte []data) {
        byte []pckData;
        if(data == null) {
            pckData = new byte[1];
            pckData[0] = (byte)command;
        }
        else {
            pckData = new byte[1 + data.length];
            pckData[0] = (byte)command;
            for(int i = 0; i < data.length; i++) pckData[i + 1] = data[i];
        }

        writeRXCharacteristic(pckData);
    }

    private void showMessage(String msg) {
        Log.e(TAG, msg);
    }
    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}