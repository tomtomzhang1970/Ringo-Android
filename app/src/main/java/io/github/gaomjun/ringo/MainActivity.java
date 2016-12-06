package io.github.gaomjun.ringo;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.gaomjun.blecommunication.BLECommunication.BLEDriven;
import io.github.gaomjun.blecommunication.BLECommunication.Message.GimbalMobileBLEProtocol;
import io.github.gaomjun.blecommunication.BLECommunication.Message.RecvMessage;
import io.github.gaomjun.blecommunication.BLECommunication.Message.SendMessage;
import io.github.gaomjun.cameraengine.CameraEngine;
import io.github.gaomjun.cmttracker.CMTTracker;
import io.github.gaomjun.cvcamera.CVCamera;
import io.github.gaomjun.ringo.BluetoothDevicesList.Adapter.BluetoothDevicesListAdapter;
import io.github.gaomjun.ringo.BluetoothDevicesList.DataSource.BluetoothDevicesListCell;
import io.github.gaomjun.ringo.BluetoothDevicesList.DataSource.BluetoothDevicesListDataSource;
import io.github.gaomjun.utils.TypeConversion.TypeConversion;

public class MainActivity extends Activity implements CVCamera.FrameCallback {
    private BLEDriven bleDriven = null;
    private SendMessage sendMessage = SendMessage.getInstance();

    private RecyclerView bluetoothDevicesListRecyclerView;

    private BluetoothDevicesListAdapter bluetoothDevicesListAdapter;
    private BluetoothDevicesListDataSource bluetoothDevicesListDataSource =
            new BluetoothDevicesListDataSource();
    private List<BluetoothDevice> bluetoothDeviceList = new ArrayList<>();
    private BluetoothDevice bluetoothDevice;

    private HandlerThread trackingThread = null;
    private Handler trackingThreadHandler = null;

    private int SCREEN_WIDTH;
    private int SCREEN_HEIGHT;
    private int SCALE;

    private Point startPoint = new Point();
    private Point endPoint = new Point();

    private ViewUtils trackingBoxUtils;
    private CMTTracker cmtTracker = null;
    private View trackingBox;
    private ImageView testImageView;
    private TextureView cameraView = null;
    private CVCamera cvCamera = null;
    private CameraEngine cameraEngine = null;
    private boolean isRecrding = false;

    private View.OnClickListener btn_listener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.iv_capture:
                    {
                        ImageView imageView = (ImageView) findViewById(R.id.iv_capture);
                        Integer tag = (Integer) imageView.getTag();

                        if ((tag == null) || tag == R.drawable.iv_capture) {
                            cameraEngine.takePicture(new Camera.PictureCallback() {
                                @Override
                                public void onPictureTaken(byte[] data, Camera camera) {
                                    cameraEngine.startPreview();
                                    if (data != null) {
                                        savePhotoToAlbum(data);
                                    }
                                }
                            });

                        } else {
                            if (!isRecrding) {
                                // start record
                                imageView.setSelected(!imageView.isSelected());
                            } else {
                                isRecrding = true;
                                // stop record
                                imageView.setSelected(!imageView.isSelected());
                            }
                        }
                    }

                    break;
                case R.id.iv_switch_camera:
                    cameraEngine.switchCamera();
                    break;
                case R.id.bluetooth_devices_list_close:
                    findViewById(R.id.bluetooth_devices_list_view).setVisibility(View.GONE);
                    bleDriven.stopScanDevices();
                    break;
                case R.id.iv_ble:
                    if (findViewById(R.id.bluetooth_devices_list_view).getVisibility() == View.GONE) {
                        findViewById(R.id.bluetooth_devices_list_view).setVisibility(View.VISIBLE);
                        datasourceChanged(new ArrayList<BluetoothDevice>(), bluetoothDevice);
                        bleDriven.scanDevices();
                    } else {
                        findViewById(R.id.bluetooth_devices_list_view).setVisibility(View.GONE);
                        bleDriven.stopScanDevices();
                    }

                    break;
                case R.id.iv_tracking_status:
                    ImageView iv_tracking_status = (ImageView) findViewById(R.id.iv_tracking_status);
                    iv_tracking_status.setSelected(!iv_tracking_status.isSelected());
                    if (iv_tracking_status.isSelected()) {
                        Log.d("iv_tracking_status", "selected");
                        sendMessage.setTrackingFlag(GimbalMobileBLEProtocol.TRACKING_FLAG_ON);
                    } else {
                        Log.d("iv_tracking_status", "no selected");
                        sendMessage.setTrackingFlag(GimbalMobileBLEProtocol.TRACKING_FLAG_OFF);
                        sendMessage.setTrackingQuailty(GimbalMobileBLEProtocol.TRACKING_QUALITY_WEAK);
                        canTrackerInit = false;
                        startTracking = false;
                        if (trackingBox.getVisibility() != View.GONE) {
                            trackingBox.setVisibility(View.GONE);
                        }

                    }
                    break;
                case R.id.iv_switch_camera_mode:
                    {
                        ImageView imageView = (ImageView) findViewById(R.id.iv_switch_camera_mode);
                        Integer tag = (Integer) imageView.getTag();
                        if ((tag == null) || tag == R.drawable.camera_mode_photo) {
                            imageView.setImageResource(R.drawable.camera_mode_video);
                            imageView.setTag(R.drawable.camera_mode_video);
                            imageView.setScaleX((float) 0.8);
                            imageView.setScaleY((float) 0.8);

                            imageView = (ImageView) findViewById(R.id.iv_capture);
                            imageView.setImageResource(R.drawable.iv_record);
                            imageView.setTag(R.drawable.iv_record);
                        } else {
                            imageView.setImageResource(R.drawable.camera_mode_photo);
                            imageView.setTag(R.drawable.camera_mode_photo);
                            imageView.setScaleX((float) 0.9);
                            imageView.setScaleY((float) 0.9);

                            imageView = (ImageView) findViewById(R.id.iv_capture);
                            imageView.setImageResource(R.drawable.iv_capture);
                            imageView.setTag(R.drawable.iv_capture);
                        }
                    }

                    break;
            }
        }
    };
    private BluetoothDevicesListAdapter.CellClickCallback cellOnClickListener =
            new BluetoothDevicesListAdapter.CellClickCallback() {
        @Override
        public void cellOnClick(int position) {
            Log.d("cellOnClick", "" + position);

            if (bluetoothDevice != null) {
                if (bluetoothDevice.getAddress().equals(bluetoothDeviceList.get(position))) {
                    return;
                } else {
                    bleDriven.disconnectDevice();
                    bluetoothDevice = null;
                }
            }
            bluetoothDevice = bluetoothDeviceList.get(position);
            Log.d("connectToDevice", bluetoothDevice.getName());
            bleDriven.connectToDevice(bluetoothDevice.getAddress());
        }
    };
    private boolean canTracking = false;
    private boolean connectedToDevice = false;

    private void savePhotoToAlbum(byte[] data) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap != null) {
            File file = new File(Environment.getExternalStorageDirectory() + "/" +
                    Environment.DIRECTORY_DCIM + "/", System.currentTimeMillis() + ".jpg");
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);

                fileOutputStream.flush();
                fileOutputStream.close();

                Log.d("onPictureTaken", "take picture success");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private View.OnTouchListener onTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (canTracking == false) return true;

            final Point point = new Point(event.getX(), event.getY());
            Log.d("OnTouch", point.toString());
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPoint.x = point.x;
                    startPoint.y = point.y;

                    trackingBoxUtils.setX((int) startPoint.x, 0);
                    trackingBoxUtils.setY((int) startPoint.y, 0);

                    Log.d("MotionEvent", "touch start" + startPoint.toString());

                    {
                        canTrackerInit = false;
                        startTracking = false;
                        trackingBox.setVisibility(View.GONE);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    endPoint.x = point.x;
                    endPoint.y = point.y;

                    trackingBox.setVisibility(View.VISIBLE);
                    trackingBoxUtils.setWidth((int) Math.abs(startPoint.x - endPoint.x), 0);
                    trackingBoxUtils.setHeight((int) Math.abs(startPoint.y - endPoint.y), 0);

                    Log.d("MotionEvent", "touch move" + endPoint.toString());
                    break;
                case MotionEvent.ACTION_UP:
                    endPoint.x = point.x;
                    endPoint.y = point.y;

                    if (Math.abs(startPoint.x - endPoint.x) > 100 &&
                        Math.abs(startPoint.y - endPoint.y) > 100) {
                        canTrackerInit = true;
                    } else {
                        canTrackerInit = false;
                        startTracking = false;
                        Log.d("OnTouch", "selected box is too small");
                        sendMessage.setTrackingQuailty(GimbalMobileBLEProtocol.TRACKING_QUALITY_WEAK);

                    }
                    trackingBoxUtils.setRect(0, 0, 0, 0, 0);
                    trackingBox.setVisibility(View.GONE);
                    Log.d("MotionEvent", "touch end" + endPoint.toString());
                    break;
            }
            return true;
        }
    };
    private boolean canTrackerInit = false;
    private boolean startTracking = false;

    private class RecvDataListener implements BLEDriven.RecvCallback {

        @Override
        public void onRecvData(RecvMessage recvMessage) {
            Log.d("recv", recvMessage.getMessageHexString());

            if (Arrays.equals(recvMessage.getCommand(), GimbalMobileBLEProtocol.REMOTECOMMAND_CAPTURE)) {
                //TODO
                //capture action
                sendMessage.setCommandBack(GimbalMobileBLEProtocol.COMMANDBACK_CAPTRUE_OK);
            } else if (Arrays.equals(recvMessage.getCommand(), GimbalMobileBLEProtocol.REMOTECOMMAND_RECORD)) {
                //TODO
                //record action
                sendMessage.setCommandBack(GimbalMobileBLEProtocol.COMMANDBACK_RECORD_OK);
            } else if (Arrays.equals(recvMessage.getCommand(), GimbalMobileBLEProtocol.REMOTECOMMAND_CLEAR)){
                sendMessage.setCommandBack(GimbalMobileBLEProtocol.COMMADNBACK_CLEAR);
            }

            if (Arrays.equals(recvMessage.getGimbalStatus(), GimbalMobileBLEProtocol.GIMBALSTATUS_RUN)) {
                // enable switch tracking status button
                findViewById(R.id.iv_tracking_status).setEnabled(true);
            } else {
                // diable switch tracking status button
                findViewById(R.id.iv_tracking_status).setEnabled(false);
            }

            if (Arrays.equals(recvMessage.getGimbalMode(), GimbalMobileBLEProtocol.GIMBALMODE_FACEFOLLOW)) {
                // can tracking
                canTracking = true;
            } else {
                // disable tracking
                canTracking = false;
            }
        }
    }

    private class BLEConnectingListener implements BLEDriven.ConnectingStatusCallback {

        @Override
        public void onConnecting(int status) {
            switch (status) {
                case BLEDriven.CONNECTED:
                    Log.d("onConnecting", "CONNECTED");
                    connectedToDevice = true;
                    datasourceChanged(bluetoothDeviceList, bluetoothDevice);
                    break;
                case BLEDriven.CONNECTING:
                    connectedToDevice = false;
                    Log.d("onConnecting", "CONNECTING");
                    break;
                case BLEDriven.DISCONNECTED:
                    connectedToDevice = false;
//                    bluetoothDevice = null;
//                    datasourceChanged(bluetoothDeviceList, bluetoothDevice);
                    Log.d("onConnecting", "DISCONNECTED");
                    break;
            }
        }
    }

    private class BLEDeviceListUpdateListener implements BLEDriven.BLEDeviceListUpdateCallback {

        @Override
        public void onBLEDeviceListUpdate(List<BluetoothDevice> bluetoothDeviceList,
                                          BluetoothDevice connectedDevice) {
            MainActivity.this.bluetoothDeviceList = bluetoothDeviceList;
            datasourceChanged(bluetoothDeviceList, connectedDevice);
        }
    }

    private void datasourceChanged(List<BluetoothDevice> bluetoothDeviceList, BluetoothDevice connectedDevice) {
        bluetoothDevicesListDataSource.setBluetoothDevicesListData(bluetoothDeviceList, connectedDevice);
        bluetoothDevicesListAdapter.setDataSource(bluetoothDevicesListDataSource.getBluetoothDevicesListData());
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bluetoothDevicesListAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        initCvCamera();
        initBLEDriven();
        initTracking();
    }

    private void initBLEDriven() {
        bleDriven = new BLEDriven(MainActivity.this);
        bleDriven.setBleDeviceListUpdateCallback(new BLEDeviceListUpdateListener());
        bleDriven.setRecvCallback(new RecvDataListener());
        bleDriven.setConnectingStatusCallback(new BLEConnectingListener());
    }

    private void initTracking() {
        cmtTracker = new CMTTracker();

        trackingThread = new HandlerThread("trackingThread");
        trackingThread.start();
        trackingThreadHandler = new Handler(trackingThread.getLooper());
    }

    private void initCvCamera() {
        cvCamera = new CVCamera();
        cvCamera.delegate = MainActivity.this;
        cameraEngine = cvCamera.cameraEngine;
    }

    private void hidenNavigationBar() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!cameraView.isAvailable()) {
            cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        SurfaceTexture surfaceTexture, int width, int height) {
                    cameraEngine.openCamera();
                    cameraEngine.startPreview(surfaceTexture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                }
            });
        } else {

        }
    }

    @Override
    protected void onStop() {
        cameraEngine.releaseCamera();

        super.onStop();
    }

    private void initView() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hidenNavigationBar();

        SCREEN_WIDTH = getWindowManager().getDefaultDisplay().getWidth();
        SCREEN_HEIGHT = getWindowManager().getDefaultDisplay().getHeight();
        SCALE = SCREEN_WIDTH / 128;

        cameraView = (TextureView) findViewById(R.id.cameraView);

        findViewById(R.id.iv_capture).setOnClickListener(btn_listener);
        findViewById(R.id.iv_switch_camera).setOnClickListener(btn_listener);
        findViewById(R.id.iv_switch_camera_mode).setOnClickListener(btn_listener);
        findViewById(R.id.iv_tracking_status).setOnClickListener(btn_listener);
        findViewById(R.id.iv_ble).setOnClickListener(btn_listener);
        findViewById(R.id.iv_album).setOnClickListener(btn_listener);
        findViewById(R.id.bluetooth_devices_list_close).setOnClickListener(btn_listener);

        testImageView = (ImageView) findViewById(R.id.testImageView);

        trackingBox = findViewById(R.id.trackingBox);
        trackingBoxUtils = new ViewUtils(trackingBox);

        findViewById(R.id.activity_main).setOnTouchListener(onTouchListener);

        initBluetoothDevicesList();
    }

    private void initBluetoothDevicesList() {
        bluetoothDevicesListRecyclerView = (RecyclerView) findViewById(R.id.bluetooth_devices_list);

        bluetoothDevicesListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        bluetoothDevicesListAdapter = new BluetoothDevicesListAdapter(this);
        bluetoothDevicesListAdapter.setDataSource(
                bluetoothDevicesListDataSource.getBluetoothDevicesListData());
        bluetoothDevicesListAdapter.setCellClickCallback(cellOnClickListener);
        bluetoothDevicesListRecyclerView.setAdapter(bluetoothDevicesListAdapter);
    }

    @Override
    public void processingFrame(Mat mat) {
        trackingThreadHandler.post(new TrackingRunnable(mat));
    }

    private class TrackingRunnable implements Runnable {
        private Mat mat = null;

        public TrackingRunnable(Mat mat) {
            this.mat = mat;
        }

        @Override
        public void run() {

            Mat smallMat = mat.clone();
            Imgproc.resize(smallMat, smallMat, new org.opencv.core.Size(128, 72));

            if (canTrackerInit) {
                cmtTracker.OpenCMT(smallMat.getNativeObjAddr(),
                        (int) (startPoint.x / SCALE),
                        (int) (startPoint.y / SCALE),
                        (int) (endPoint.x / SCALE),
                        (int) (endPoint.y / SCALE),
                        cameraEngine.isFrontCamera());
                canTrackerInit = false;
                startTracking = true;
            }

            if (startTracking) {
                cmtTracker.ProcessCMT(smallMat.getNativeObjAddr(), cameraEngine.isFrontCamera());
                final int[] rect = cmtTracker.CMTgetRect();

                if (cmtTracker.CMTgetResult()) {
                    sendMessage.setTrackingQuailty(GimbalMobileBLEProtocol.TRACKING_QUALITY_GOOD);

                    final Point p = new Point(rect[0], rect[1]);

                    final int width = rect[2];
                    final int height = rect[3];

                    Log.d("CMTgetRect", p.toString() + " [" + width + "," + height + "]");

                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            final int w = ((rect[2]*SCALE)>SCREEN_WIDTH) ? SCREEN_WIDTH : rect[2]*SCALE;
                            final int h = (((rect[3]*SCALE)>SCREEN_HEIGHT) ? SCREEN_HEIGHT : rect[3]*SCALE);

                            trackingBoxUtils.setRect((int) p.x * SCALE, (int) p.y * SCALE,
                                    w, h, 0);
                            trackingBox.setVisibility(View.VISIBLE);
                        }
                    });

                    {
                        int xoffset = 0;
                        int yoffset = 0;

                        if (cameraEngine.isFrontCamera()) {
                            xoffset = (int) (mat.cols()/15/2 - (p.x + width/2.0));
                            yoffset = (int) (mat.rows()/15/2 - (p.y + height/2.0));
                        } else {
                            xoffset = (int) (-mat.cols()/15/2 + (p.x + width/2.0));
                            yoffset = (int) (-mat.rows()/15/2 + (p.y + height/2.0));
                        }

                        xoffset *= 10;
                        yoffset *= 10;

                        Log.d("tracking...", "[" + xoffset + "," + yoffset + "]");

                        sendMessage.setXoffset(TypeConversion.intToBytes(xoffset));
                        sendMessage.setYoffset(TypeConversion.intToBytes(yoffset));
                    }

                } else {
                    sendMessage.setTrackingQuailty(GimbalMobileBLEProtocol.TRACKING_QUALITY_WEAK);
                    sendMessage.setXoffset(TypeConversion.intToBytes(0));
                    sendMessage.setYoffset(TypeConversion.intToBytes(0));
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            trackingBox.setVisibility(View.GONE);
                        }
                    });
                }

            } else {
                if (trackingBox.getVisibility() == View.VISIBLE) {
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            trackingBox.setVisibility(View.GONE);
                        }
                    });
                }
            }
        }
    }
}
