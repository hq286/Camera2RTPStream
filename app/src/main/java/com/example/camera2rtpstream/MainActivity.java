package com.example.camera2rtpstream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.RequiresApi;

import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.R)
public class MainActivity extends Activity implements View.OnClickListener {

    //    private AutoFitTextureView mTextureView;
    private static final String TAG = "camera2";
    private AutoFitSurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private List<Surface> mSurfaces;
    private Surface mediaCodecSurface;
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String[] PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int MAX_PREVIEW_WIDTH = 1280;
    private static final int MAX_PREVIEW_HEIGHT = 720;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private CameraCharacteristics[] mCameraCharacteristics;
    private String[] mCameraIdList;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;

//    private ImageReader previewReader;
    /** An additional thread for running tasks that shouldn't block the UI. */
    private HandlerThread backgroundThread;
    /** A {@link Handler} for running tasks in the background. */
    private Handler backgroundHandler;
    private int mSensorOrientation;
    private Size mPreviewSize;
    private Range<Float> range;
    private MediaCodec mediaCodec;
    private H264Packetizer mPacketizer;
    private MP4Config mConfig;
    private byte mChannelIdentifier = 0;
    private int mTTL = 64;
    private static InetAddress mDestination = null;

    static {
        try {
            mDestination = InetAddress.getByName("71.125.50.10");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    private final static int PORT = 8047;
    private Context context;
    private SharedPreferences settings = null;
    private Button mButton;

    @Override
    protected void onResume() {
        Log.d(TAG,"[onResume]");
        super.onResume();
        prepareCamera();
        createBackgroundThread();
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mButton = (Button) findViewById(R.id.button);
        mButton.setOnClickListener(this);

        mSurfaceView = (AutoFitSurfaceView) findViewById(R.id.surface);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceHolderCallback);
        createBackgroundThread();
        context = getApplicationContext();
        assert context != null;
        settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    protected void onPause(){
        super.onPause();
        closeCamera();
        destroyBackgroundThread();
    }

    private void createBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("cameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void destroyBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                Log.d("FillBytes", "Initializing buffer d at size d");
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.button) {
            try {
                startStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void startStream() throws IOException {
        mediaCodec.start();

        mPacketizer.setInputStream(new MediaCodecInputStream(mediaCodec));
        mPacketizer.start();
    }

    public void configureEncoder() throws IOException {
        EncoderDebugger debugger = EncoderDebugger.debug(settings, MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
        mPacketizer = new H264Packetizer();
        mPacketizer.setDestination(mDestination, 8047, 19400);
        mPacketizer.getRtpSocket().setOutputStream(null, mChannelIdentifier);
        mPacketizer.setTimeToLive(mTTL);
        mConfig = new MP4Config(debugger.getB64SPS(), debugger.getB64PPS());
        byte[] pps = Base64.decode(mConfig.getB64PPS(), Base64.NO_WRAP);
        byte[] sps = Base64.decode(mConfig.getB64SPS(), Base64.NO_WRAP);
        mPacketizer.setStreamParameters(pps, sps);

        if (mDestination==null)
            throw new IllegalStateException("No destination ip address set for the stream !");

        if (PORT <= 0)
            throw new IllegalStateException("No destination ports set for the stream !");
        Log.d("Stream", "Started");

        mediaCodec = MediaCodec.createByCodecName(debugger.getEncoderName());
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodecSurface = mediaCodec.createInputSurface();
    }


    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    private void setUpCameraOutputs(int width, int height) {
        Activity activity = this;
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = mCameraIdList[0];
            CameraCharacteristics characteristics
                    = manager.getCameraCharacteristics(cameraId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            }

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return;
            }

            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean swappedDimensions = false;
            switch (displayRotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                        swappedDimensions = true;
                    }
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                        swappedDimensions = true;
                    }
                    break;
            }

            Point displaySize = new Point();
            activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
            int rotatedPreviewWidth = width;
            int rotatedPreviewHeight = height;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;

            if (swappedDimensions) {
                rotatedPreviewWidth = height;
                rotatedPreviewHeight = width;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                    maxPreviewHeight, largest);

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mSurfaceView.setAspectRatio(
                        mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mSurfaceView.setAspectRatio(
                        mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }


    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.d(TAG,"[open camera onOpened]");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            try {
                try {
                    configureEncoder();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mSurfaces = Arrays.asList(mSurfaceHolder.getSurface(), mediaCodecSurface);
                mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(mSurfaceHolder.getSurface());
                mPreviewRequestBuilder.addTarget(mediaCodecSurface);
                cameraDevice.createCaptureSession(mSurfaces, mSessionStateCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.d(TAG,"[open camera onDisconnected]");
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.d(TAG,"[open camera onError]");
//            Activity activity = MainActivity.this;
//            if (null != activity) {
//                activity.finish();
//            }
        }

    };

    private CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.d(TAG,"[captureSession onConfigured]");
            mCaptureSession = session;
            startPreview();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {}
    };

    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);

        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };

    private SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "[surfaceCreated]");
            mSurfaceHolder = holder;
            openCamera(mSurfaceView.getWidth(), mSurfaceView.getHeight());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "[surfaceChanged] format:" + format + "  width:" + width + "  height:" + height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG,"[surfaceDestroyed]");
//            mPacketizer.stop();
            if (mediaCodec != null) {
                mediaCodec.stop();
                mediaCodec.release();
                mediaCodec = null;
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void prepareCamera(){
        if (shouldRequestCameraPermission()) {
            requestPermissions(PERMISSIONS, REQUEST_CAMERA_PERMISSION);
        }
        Log.d(TAG,"[prepareCamera]");
        if (mCameraManager == null) {
            mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            try {
                mCameraIdList = mCameraManager.getCameraIdList();
            } catch (CameraAccessException e) {
                mCameraIdList = new String[]{"0"};
                e.printStackTrace();
            }

            String idString = "";
            for(String id:mCameraIdList){
                idString = idString+id+", ";
            }
            Log.d(TAG,"[prepareCamea]  mCameraIdList = " + idString);

            try {
                mCameraCharacteristics = new CameraCharacteristics[mCameraIdList.length];
                for (int i = 0; i < mCameraIdList.length; i++) {
                    mCameraCharacteristics[i] = mCameraManager.getCameraCharacteristics(mCameraIdList[i]);
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            Log.d(TAG,"[openCamera]");
            Log.d(TAG,"[openCamera]  cameraId:"+mCameraIdList[0]);
            mCameraManager.openCamera(mCameraIdList[0], mStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.abortCaptures();
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void startPreview(){
        Log.d(TAG,"[startPreview]");
        try {
            setUpCaptureRequestBuilder(mPreviewRequestBuilder);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),mSessionCaptureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder){
        builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, range.getLower());
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean shouldRequestCameraPermission() {
        if (checkSelfPermission(PERMISSIONS[0]) != PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                }
                return;
            }
        }
    }
}
