/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.nordicsemi.ImageTransferDemo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.zip.GZIPOutputStream;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements RadioGroup.OnCheckedChangeListener {
    private static final int REQUEST_SELECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    public static final String TAG = "file_transfer_main";
    private static final int UART_PROFILE_CONNECTED = 20;
    private static final int UART_PROFILE_DISCONNECTED = 21;

    public enum AppLogFontType {APP_NORMAL, APP_ERROR, PEER_NORMAL, PEER_ERROR}

    private String mLogMessage = "";

    private TextView mTextViewLog, mTextViewFileLabel, mTextViewPictureStatus, mTextViewPictureFpsStatus, mTextViewConInt, mTextViewMtu;
    private Button mBtnDownload;
    private Button mBtnGZDownload;
    private ProgressBar mProgressBarFileStatus;
    private ImageView mMainImage;
    private Spinner mSpinnerResolution, mSpinnerPhy;

    private int mState = UART_PROFILE_DISCONNECTED;
    private ImageTransferService mService = null;
    private BluetoothDevice mDevice = null;
    private BluetoothAdapter mBtAdapter = null;
    private Button btnConnectDisconnect;
    private boolean mMtuRequested;
    private long mStartTimeImageTransfer;
    private byte[] mFileTransferBuffer;

    private Uri m_picked_file_uri;

    private static final int OPEN_REQUEST_CODE = 41;

    // File transfer variables
    private int mBytesTransfered = 0, mBytesTotal = 0;
    private byte []mDataBuffer;
    private boolean mUploadActive = false;

    private ProgressDialog mConnectionProgDialog;

    private enum AppRunMode {Disconnected, Connected, ConnectedDuringSingleTransfer, ConnectedDuringStream}
    private enum CmdInfoCharCommands {setIncomingFileParams, setConnectionParams, setOutgoingFileParams}
    private enum OutgoingFileParams {ReadyToReceive, TransmissionFinished, ReadyToReceiveContinuous, ReceiverBusy}

    // TODO There are some unused commands, cleanUp required
    public enum BleCommand {NoCommand, StartSingleCapture, StartStreaming, StopStreaming, ChangeResolution, ChangePhy, GetBleParams, SetIncomingFileParams}

    Handler guiUpdateHandler = new Handler();
    Runnable guiUpdateRunnable = new Runnable(){
        @SuppressLint("DefaultLocale")
        @Override
        public void run(){
            if(mTextViewFileLabel != null && mService != null) {
                mTextViewFileLabel.setText(String.format("Sending: %d / %d", mService.getTransmitted_bytes(), mService.getTotalTransmissionBytes()));
                if(mService.getTotalTransmissionBytes() > 0) {
                    mProgressBarFileStatus.setProgress(mService.getTransmitted_bytes() * 100 / mService.getTotalTransmissionBytes());
                }
            }
            guiUpdateHandler.postDelayed(this, 50);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBtAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        btnConnectDisconnect    = (Button) findViewById(R.id.btn_select);
        mTextViewLog = (TextView)findViewById(R.id.textViewLog);
        mTextViewFileLabel = (TextView)findViewById(R.id.textViewFileLabel);
        mTextViewPictureStatus = (TextView)findViewById(R.id.textViewImageStatus);
        mTextViewPictureFpsStatus = (TextView)findViewById(R.id.textViewImageFpsStatus);
        mTextViewConInt = (TextView)findViewById(R.id.textViewCI);
        mTextViewMtu = (TextView)findViewById(R.id.textViewMTU);
        mProgressBarFileStatus = (ProgressBar)findViewById(R.id.progressBarFile);
        mBtnDownload = (Button)findViewById(R.id.buttonTakePicture);
        mBtnGZDownload = (Button)findViewById(R.id.buttonGZDownload);
        Button mBtnChoseFile = (Button) findViewById(R.id.button_chosefile);
        mMainImage = (ImageView)findViewById(R.id.imageTransfered);
        mSpinnerResolution = (Spinner)findViewById(R.id.spinnerResolution);
        mSpinnerResolution.setSelection(1);
        mSpinnerPhy = (Spinner)findViewById(R.id.spinnerPhy);
        mConnectionProgDialog = new ProgressDialog(this);
        mConnectionProgDialog.setTitle("Connecting...");
        mConnectionProgDialog.setCancelable(false);
        service_init();

        // Handler Disconnect & Connect button
        btnConnectDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBtAdapter.isEnabled()) {
                    Log.i(TAG, "onClick - BT not enabled yet");
                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
                } else {
                    if (btnConnectDisconnect.getText().equals("Connect")) {

                        //Connect button pressed, open DeviceListActivity class, with popup windows that scan for devices

                        Intent newIntent = new Intent(MainActivity.this, DeviceListActivity.class);
                        startActivityForResult(newIntent, REQUEST_SELECT_DEVICE);
                    } else {
                        //Disconnect button pressed
                        if (mDevice != null) {
                            mService.disconnect();
                        }
                    }
                }
            }
        });

        mBtnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mService != null){
                    mService.fts_sendFile(m_picked_file_uri, false);
                    mStartTimeImageTransfer = System.currentTimeMillis();
                }
            }
        });

        mBtnGZDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mService != null){
                    mService.fts_sendFile(m_picked_file_uri, true);
                    mStartTimeImageTransfer = System.currentTimeMillis();
                }
            }
        });

        mBtnChoseFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                startActivityForResult(intent, OPEN_REQUEST_CODE);
            }
        });



        mSpinnerResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if(mService != null && mService.isConnected()){
                    byte []cmdData = new byte[1];
                    cmdData[0] = (byte)position;
                    mService.sendCommand(BleCommand.ChangeResolution.ordinal(), cmdData);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mSpinnerPhy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if(mService != null && mService.isConnected()){
                    byte []cmdData = new byte[1];
                    cmdData[0] = (byte)position;
                    mService.sendCommand(BleCommand.ChangePhy.ordinal(), cmdData);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        // Set initial UI state
        guiUpdateHandler.postDelayed(guiUpdateRunnable, 0);

        setGuiByAppMode(AppRunMode.Disconnected);
    }


    //UART service connected/disconnected
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            mService = ((ImageTransferService.LocalBinder) rawBinder).getService();
            Log.d(TAG, "onServiceConnected mService= " + mService);
            if (!mService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
       ////     mService.disconnect(mDevice);
        		mService = null;
        }
    };

    private void setGuiByAppMode(AppRunMode appMode)
    {
        switch(appMode)
        {
            case Connected:
                mBtnDownload.setEnabled(true);
                mBtnGZDownload.setEnabled(true);
                btnConnectDisconnect.setText(R.string.disconnect);
                mSpinnerResolution.setEnabled(true);
                mSpinnerPhy.setEnabled(true);
                break;

            case Disconnected:
                mBtnDownload.setEnabled(false);
                mBtnGZDownload.setEnabled(false);
                btnConnectDisconnect.setText(R.string.connect_bt_text);
                mTextViewPictureStatus.setVisibility(View.INVISIBLE);
                mTextViewPictureFpsStatus.setVisibility(View.INVISIBLE);
                mSpinnerResolution.setEnabled(false);
                mSpinnerResolution.setSelection(1);
                mSpinnerPhy.setEnabled(false);
                mSpinnerPhy.setSelection(0);
                break;

            case ConnectedDuringSingleTransfer:

            case ConnectedDuringStream:
                mBtnDownload.setEnabled(false);
                mBtnGZDownload.setEnabled(false);
                break;
        }
    }

    private void writeToLog(String message, AppLogFontType msgType){
        String currentDateTimeString = DateFormat.getTimeInstance().format(new Date());
        String newMessage = currentDateTimeString + " - " + message;
        String fontHtmlTag;
        switch(msgType){
            case APP_NORMAL:
                fontHtmlTag = "<font color='#000000'>";
                break;
            case APP_ERROR:
                fontHtmlTag = "<font color='#AA0000'>";
                break;
            case PEER_NORMAL:
                fontHtmlTag = "<font color='#0000AA'>";
                break;
            case PEER_ERROR:
                fontHtmlTag = "<font color='#FF00AA'>";
                break;
            default:
                fontHtmlTag = "<font>";
                break;
        }
        mLogMessage = fontHtmlTag + newMessage + "</font>" + "<br>" + mLogMessage;
        mTextViewLog.setText(Html.fromHtml(mLogMessage));
    }

    private final BroadcastReceiver UARTStatusChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        //*********************//
        if (action.equals(ImageTransferService.ACTION_GATT_CONNECTED)) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mMtuRequested = false;
                    mConnectionProgDialog.hide();
                    Log.d(TAG, "UART_CONNECT_MSG");
                    writeToLog("Connected", AppLogFontType.APP_NORMAL);
                }
            });
        }

          //*********************//
        if (action.equals(ImageTransferService.ACTION_GATT_DISCONNECTED)) {
            runOnUiThread(new Runnable() {
                public void run() {
                    setGuiByAppMode(AppRunMode.Disconnected);
                    mState = UART_PROFILE_DISCONNECTED;
                    mService.close();
                    mTextViewMtu.setText("-");
                    mTextViewConInt.setText("-");
                    mConnectionProgDialog.hide();
                    Log.d(TAG, "UART_DISCONNECT_MSG");
                    writeToLog("Disconnected", AppLogFontType.APP_NORMAL);
                }
            });
        }

        //*********************//
        if (action.equals(ImageTransferService.ACTION_GATT_SERVICES_DISCOVERED)) {
            mService.enableTXNotification();
            mService.sendCommand(BleCommand.GetBleParams.ordinal(), null);
            setGuiByAppMode(AppRunMode.Connected);
        }
        if (action.equals(ImageTransferService.ACTION_CMD_INFO_AVAILABLE)) {
            final byte[] txValue = intent.getByteArrayExtra(ImageTransferService.EXTRA_DATA);
            runOnUiThread(new Runnable() {
                @SuppressLint("SetTextI18n")
                public void run() {
                    try {
                        CmdInfoCharCommands cmd = CmdInfoCharCommands.values()[txValue[0] - 1];
                        switch(cmd) {
                            case setIncomingFileParams:
                                // Start a new file transfer
                                ByteBuffer byteBuffer = ByteBuffer.wrap(Arrays.copyOfRange(txValue, 1, 5));
                                byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                                int fileSize = byteBuffer.getInt();
                                mBytesTotal = fileSize;
                                mDataBuffer = new byte[fileSize];
                                mTextViewFileLabel.setText("Incoming file: " + fileSize + " bytes.");
                                mBytesTransfered = 0;
                                mStartTimeImageTransfer = System.currentTimeMillis();
                                break;

                            case setConnectionParams:
                                ByteBuffer mtuBB = ByteBuffer.wrap(Arrays.copyOfRange(txValue, 1, 3));
                                mtuBB.order(ByteOrder.LITTLE_ENDIAN);
                                short mtu = mtuBB.getShort();
                                mTextViewMtu.setText(mtu + " bytes");
                                if(!mMtuRequested && mtu < 64){
                                    mService.requestMtu(ImageTransferService.targetMtu);
                                    writeToLog("Requesting 240 byte MTU from app", AppLogFontType.APP_NORMAL);
                                    mMtuRequested = true;
                                }
                                ByteBuffer ciBB = ByteBuffer.wrap(Arrays.copyOfRange(txValue, 3, 5));
                                ciBB.order(ByteOrder.LITTLE_ENDIAN);
                                short conInterval = ciBB.getShort();
                                mTextViewConInt.setText((float)conInterval * 1.25f + "ms");
                                short txPhy = txValue[5];
                                // short rxPhy = txValue[6];
                                if(txPhy == 0x0001 && mSpinnerPhy.getSelectedItemPosition() == 1) {
                                    mSpinnerPhy.setSelection(0);
                                    writeToLog("2Mbps not supported!", AppLogFontType.APP_ERROR);
                                }
                                else {
                                    writeToLog("Parameters updated.", AppLogFontType.APP_NORMAL);
                                }
                                break;
                            case setOutgoingFileParams:
                                OutgoingFileParams ofp_cmd = OutgoingFileParams.values()[txValue[1]];
                                switch(ofp_cmd) {
                                    case ReadyToReceive:
                                        mService.fts_start_transmit();
                                        break;
                                    case TransmissionFinished:
                                        long elapsedTime = System.currentTimeMillis() - mStartTimeImageTransfer;
                                        float elapsedSeconds = (float)elapsedTime / 1000.0f;
                                        DecimalFormat df = new DecimalFormat("0.0");
                                        df.setMaximumFractionDigits(1);
                                        String elapsedSecondsString = df.format(elapsedSeconds);
                                        String kbpsString = df.format((float)mService.getTotalTransmissionBytes() / elapsedSeconds * 8.0f / 1000.0f);
                                        writeToLog("Completed in " + elapsedSecondsString + " seconds. " + kbpsString + " kbps", AppLogFontType.APP_NORMAL);
                                        break;
                                    case ReadyToReceiveContinuous:
                                        mService.setContinuousTransmissionReadyState(true);
                                        break;
                                    case ReceiverBusy:
                                        mService.setContinuousTransmissionReadyState(false);
                                        break;
                                    default:
                                        break;
                                }
                                break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                    }
                }
            });
        }
        //*********************//
        if (action.equals(ImageTransferService.DEVICE_DOES_NOT_SUPPORT_FILE_TRANSFER)){
            //showMessage("Device doesn't support UART. Disconnecting");
            writeToLog("APP: Invalid BLE service, disconnecting!",  AppLogFontType.APP_ERROR);
            mService.disconnect();
        }

        if (action.equals(ImageTransferService.ACTION_GATT_TRANSFER_FINISHED)) {
            mUploadActive = false;
            setGuiByAppMode(AppRunMode.Connected);
        }

        }
    };

    private void service_init() {
        Intent bindIntent = new Intent(this, ImageTransferService.class);
        bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
  
        LocalBroadcastManager.getInstance(this).registerReceiver(UARTStatusChangeReceiver, makeGattUpdateIntentFilter());
    }
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ImageTransferService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(ImageTransferService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(ImageTransferService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(ImageTransferService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(ImageTransferService.ACTION_GATT_TRANSFER_FINISHED);
        intentFilter.addAction(ImageTransferService.ACTION_CMD_INFO_AVAILABLE);
        intentFilter.addAction(ImageTransferService.ACTION_FTS_NOTIFICATION);
        intentFilter.addAction(ImageTransferService.DEVICE_DOES_NOT_SUPPORT_FILE_TRANSFER);
        return intentFilter;
    }
    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onDestroy() {
    	 super.onDestroy();
        Log.d(TAG, "onDestroy()");

        try {
        	LocalBroadcastManager.getInstance(this).unregisterReceiver(UARTStatusChangeReceiver);
        } catch (Exception ignore) {
            Log.e(TAG, ignore.toString());
        }
        unbindService(mServiceConnection);
        mService.stopSelf();
        mService= null;
       
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (!mBtAdapter.isEnabled()) {
            Log.i(TAG, "onResume - BT not enabled yet");
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }
 
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SELECT_DEVICE:
                //When the DeviceListActivity return, with the selected device address
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String deviceAddress = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                    mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);

                    Log.d(TAG, "... onActivityResultdevice.address==" + mDevice + "mserviceValue" + mService);
                    //((TextView) findViewById(R.id.deviceName)).setText(mDevice.getName()+ " - connecting");
                    mService.connect(deviceAddress);

                    mConnectionProgDialog.show();
                }
                break;

            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth has turned on ", Toast.LENGTH_SHORT).show();

                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, "Problem in BT Turning ON ", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            case OPEN_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK)
                {
                   m_picked_file_uri = data.getData();
                   if(!mMtuRequested){
                       mService.requestMtu(ImageTransferService.targetMtu);
                       writeToLog("Requesting 240 byte MTU from app", AppLogFontType.APP_NORMAL);
                       mMtuRequested = true;
                       mTextViewMtu.setText("240 bytes");
                   }
                }
                break;

            default:
                Log.e(TAG, "wrong request code");
                break;
        }
    }

    private byte[] readFileContent(Uri uri) throws IOException {
        // Read file data
        InputStream inputStream = getContentResolver().openInputStream(uri);
        byte[] mBuffer = new byte[inputStream.available()];
        inputStream.read(mBuffer, 0, inputStream.available());
        inputStream.close();

        // Compress file data
        ByteArrayOutputStream os = new ByteArrayOutputStream(mBuffer.length);
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(mBuffer);
        gos.close();
        byte[] mCompressedByteArray = os.toByteArray();
        os.close();
        return mCompressedByteArray;
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
       
    }

    
    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  
    }

    @Override
    public void onBackPressed() {
        if (mState == UART_PROFILE_CONNECTED) {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMain);
            showMessage("nRFUART's running in background.\n             Disconnect to exit");
        }
        else {
            finish();
        }
    }
}
