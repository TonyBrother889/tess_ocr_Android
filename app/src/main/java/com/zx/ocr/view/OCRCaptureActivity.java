/*
 * Copyright (C) 2008 ZXing authors
 * Copyright 2011 Robert Theis
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

package com.zx.ocr.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;


import com.googlecode.tesseract.android.TessBaseAPI;
import com.zx.ocr.R;
import com.zx.ocr.view.base.BaseActivity;
import com.zx.ocr.view.camera.CameraManager;
import com.zx.ocr.view.camera.ShutterButton;
import com.zx.ocr.view.decode.DecodeHandler;
import com.zx.ocr.view.ocr.OcrResult;
import com.zx.ocr.view.language.LanguageCodeHelper;
import com.zx.ocr.view.ocr.OcrResultFailure;
import com.zx.ocr.view.ocr.OcrResultText;
import com.zx.ocr.view.tess.TessDataManager;

import java.io.File;
import java.io.IOException;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the text correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 * <p>
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
public final class OCRCaptureActivity extends BaseActivity implements SurfaceHolder.Callback,
        ShutterButton.OnShutterButtonListener {

    private static final String TAG = OCRCaptureActivity.class.getSimpleName();

    /**
     * ISO 639-3 language code indicating the default recognition language.
     */
    public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "eng";

    /**
     * Flag to display the real-time recognition results at the top of the scanning screen.
     */
    private static final boolean CONTINUOUS_DISPLAY_RECOGNIZED_TEXT = true;

    /**
     * Flag to display recognition-related statistics on the scanning screen.
     */
    private static final boolean CONTINUOUS_DISPLAY_METADATA = true;

    /**
     * Flag to enable display of the on-screen shutter button.
     */
    private static final boolean DISPLAY_SHUTTER_BUTTON = true;

    //是否开启连续模式
    private static final boolean isContinuousModeActive = false;

    // Options menu, for copy to clipboard
    private static final int OPTIONS_COPY_RECOGNIZED_TEXT_ID = Menu.FIRST;

    private CameraManager cameraManager;
    private OCRCaptureActivityHandler handler;
    private ViewfinderView viewfinderView;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private TextView statusViewBottom;
    private TextView statusViewTop;
    private TextView ocrResultView;
    private View cameraButtonView;
    private View resultView;
    private OcrResult lastResult;
    private Bitmap lastBitmap;
    private boolean hasSurface;
    private BeepManager beepManager;
    private TessBaseAPI baseApi; // Java interface for the Tesseract OCR engine
    private String sourceLanguageCodeOcr; // ISO 639-3 language code
    private String sourceLanguageReadable; // Language name, for example, "English"
    private String targetLanguageReadable; // Language name, for example, "English"
    private int pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
    private int ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
    private ShutterButton shutterButton;
    private OnSharedPreferenceChangeListener listener;
    private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine
    private boolean isEngineReady;
    private boolean isPaused;
    private static boolean isFirstLaunch; // True if this is the first time the app is being run

    public Handler getHandler() {
        return handler;
    }

    public TessBaseAPI getBaseApi() {
        return baseApi;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.capture);
        viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
        cameraButtonView = findViewById(R.id.camera_button_view);
        resultView = findViewById(R.id.result_view);

        statusViewBottom = (TextView) findViewById(R.id.status_view_bottom);
        registerForContextMenu(statusViewBottom);
        statusViewTop = (TextView) findViewById(R.id.status_view_top);
        registerForContextMenu(statusViewTop);

        handler = null;
        lastResult = null;
        hasSurface = false;
        beepManager = new BeepManager(this);

        // Camera shutter button
        if (DISPLAY_SHUTTER_BUTTON) {
            shutterButton = (ShutterButton) findViewById(R.id.shutter_button);
            shutterButton.setOnShutterButtonListener(this);
        }

        ocrResultView = (TextView) findViewById(R.id.ocr_result_text_view);
        registerForContextMenu(ocrResultView);

        cameraManager = new CameraManager(getApplication());
        viewfinderView.setCameraManager(cameraManager);

        // Set listener to change the size of the viewfinder rectangle.
        viewfinderView.setOnTouchListener(new View.OnTouchListener() {
            int lastX = -1;
            int lastY = -1;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = -1;
                        lastY = -1;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int currentX = (int) event.getX();
                        int currentY = (int) event.getY();

                        try {
                            Rect rect = cameraManager.getFramingRect();

                            final int BUFFER = 50;
                            final int BIG_BUFFER = 60;
                            if (lastX >= 0) {
                                // Adjust the size of the viewfinder rectangle. Check if the touch event occurs in the corner areas first, because the regions overlap.
                                if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                                        && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                                    // Top left corner: adjust both top and left sides
                                    cameraManager.adjustFramingRect(2 * (lastX - currentX), 2 * (lastY - currentY));
                                    viewfinderView.removeResultText();
                                } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER))
                                        && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                                    // Top right corner: adjust both top and right sides
                                    cameraManager.adjustFramingRect(2 * (currentX - lastX), 2 * (lastY - currentY));
                                    viewfinderView.removeResultText();
                                } else if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                                        && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                                    // Bottom left corner: adjust both bottom and left sides
                                    cameraManager.adjustFramingRect(2 * (lastX - currentX), 2 * (currentY - lastY));
                                    viewfinderView.removeResultText();
                                } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER))
                                        && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                                    // Bottom right corner: adjust both bottom and right sides
                                    cameraManager.adjustFramingRect(2 * (currentX - lastX), 2 * (currentY - lastY));
                                    viewfinderView.removeResultText();
                                } else if (((currentX >= rect.left - BUFFER && currentX <= rect.left + BUFFER) || (lastX >= rect.left - BUFFER && lastX <= rect.left + BUFFER))
                                        && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                                    // Adjusting left side: event falls within BUFFER pixels of left side, and between top and bottom side limits
                                    cameraManager.adjustFramingRect(2 * (lastX - currentX), 0);
                                    viewfinderView.removeResultText();
                                } else if (((currentX >= rect.right - BUFFER && currentX <= rect.right + BUFFER) || (lastX >= rect.right - BUFFER && lastX <= rect.right + BUFFER))
                                        && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                                    // Adjusting right side: event falls within BUFFER pixels of right side, and between top and bottom side limits
                                    cameraManager.adjustFramingRect(2 * (currentX - lastX), 0);
                                    viewfinderView.removeResultText();
                                } else if (((currentY <= rect.top + BUFFER && currentY >= rect.top - BUFFER) || (lastY <= rect.top + BUFFER && lastY >= rect.top - BUFFER))
                                        && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                                    // Adjusting top side: event falls within BUFFER pixels of top side, and between left and right side limits
                                    cameraManager.adjustFramingRect(0, 2 * (lastY - currentY));
                                    viewfinderView.removeResultText();
                                } else if (((currentY <= rect.bottom + BUFFER && currentY >= rect.bottom - BUFFER) || (lastY <= rect.bottom + BUFFER && lastY >= rect.bottom - BUFFER))
                                        && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                                    // Adjusting bottom side: event falls within BUFFER pixels of bottom side, and between left and right side limits
                                    cameraManager.adjustFramingRect(0, 2 * (currentY - lastY));
                                    viewfinderView.removeResultText();
                                }
                            }
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Framing rect not available", e);
                        }
                        v.invalidate();
                        lastX = currentX;
                        lastY = currentY;
                        return true;
                    case MotionEvent.ACTION_UP:
                        lastX = -1;
                        lastY = -1;
                        return true;
                }
                return false;
            }
        });

        isEngineReady = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetStatusView();

        String previousSourceLanguageCodeOcr = sourceLanguageCodeOcr;
        int previousOcrEngineMode = ocrEngineMode;

        // Set up the camera preview surface.
        surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        surfaceHolder = surfaceView.getHolder();
        if (!hasSurface) {
            surfaceHolder.addCallback(this);
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        // Comment out the following block to test non-OCR functions without an SD card

        // Do OCR engine initialization, if necessary
        boolean doNewInit = (baseApi == null) || !sourceLanguageCodeOcr.equals(previousSourceLanguageCodeOcr) ||
                ocrEngineMode != previousOcrEngineMode;

        initOcrEngine(sourceLanguageCodeOcr, sourceLanguageReadable);
        resumeOCR();
    }

    /**
     * Method to start or restart recognition after the OCR engine has been initialized,
     * or after the app regains focus. Sets state related settings and OCR engine parameters,
     * and requests camera initialization.
     */
    public void resumeOCR() {

        Log.d(TAG, "resumeOCR()");

        // This method is called when Tesseract has already been successfully initialized, so set
        // isEngineReady = true here.
        isEngineReady = true;

        isPaused = false;

        if (handler != null) {
            handler.resetState();
        }
        if (baseApi != null) {
            baseApi.setPageSegMode(pageSegmentationMode);
            // 白名单
            baseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
            // 黑名单
            baseApi.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, "!@#$%^&*()_+=-[]}{;:'\"\\|~`,./<>?");
        }

        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);
        }
    }

    /**
     * Called when the shutter button is pressed in continuous mode.
     */
    void onShutterButtonPressContinuous() {
        isPaused = true;
        handler.stop();
        beepManager.playBeepSoundAndVibrate();
        if (lastResult != null) {
            handleOcrDecode(lastResult);
        } else {
            Toast toast = Toast.makeText(this, "OCR failed. Please try again.", Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.TOP, 0, 0);
            toast.show();
            resumeContinuousDecoding();
        }
    }

    /**
     * Called to resume recognition after translation in continuous mode.
     */
    @SuppressWarnings("unused")
    void resumeContinuousDecoding() {
        isPaused = false;
        resetStatusView();
        setStatusViewForContinuous();
        DecodeHandler.resetDecodeState();
        handler.resetState();
        if (shutterButton != null && DISPLAY_SHUTTER_BUTTON) {
            shutterButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated()");

        if (holder == null) {
            Log.e(TAG, "surfaceCreated gave us a null surface");
        }

        // Only initialize the camera if the OCR engine is ready to go.
        if (!hasSurface && isEngineReady) {
            Log.d(TAG, "surfaceCreated(): calling initCamera()...");
            initCamera(holder);
        }
        hasSurface = true;
    }

    /**
     * Initializes the camera and starts the handler to begin previewing.
     */
    private void initCamera(SurfaceHolder surfaceHolder) {
        Log.d(TAG, "initCamera()");
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        try {
            // Open and initialize the camera
            cameraManager.openDriver(surfaceHolder);

            // Creating the handler starts the preview, which can also throw a RuntimeException.
            handler = new OCRCaptureActivityHandler(this, cameraManager, isContinuousModeActive);

        } catch (IOException ioe) {
            showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
        } catch (RuntimeException e) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
        }
    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
        }

        // Stop using the camera, to avoid conflicting with other camera-based apps
        cameraManager.closeDriver();

        if (!hasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    public void stopHandler() {
        if (handler != null) {
            handler.stop();
        }
    }

    @Override
    protected void onDestroy() {
        if (baseApi != null) {
            baseApi.end();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {

            // First check if we're paused in continuous mode, and if so, just unpause.
            if (isPaused) {
                Log.d(TAG, "only resuming continuous recognition, not quitting...");
                resumeContinuousDecoding();
                return true;
            }

            // Exit the app if we're not viewing an OCR result.
            if (lastResult == null) {
                setResult(RESULT_CANCELED);
                finish();
                return true;
            } else {
                // Go back to previewing in regular OCR mode.
                resetStatusView();
                if (handler != null) {
                    handler.sendEmptyMessage(R.id.restart_preview);
                }
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (isContinuousModeActive) {
                onShutterButtonPressContinuous();
            } else {
                handler.hardwareShutterButtonClick();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_FOCUS) {
            // Only perform autofocus if user is not holding down the button.
            if (event.getRepeatCount() == 0) {
                cameraManager.requestAutoFocus(500L);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    /**
     * Finds the proper location on the SD card where we can save files.
     */
    private File getStorageDirectory() {
        //Log.d(TAG, "getStorageDirectory(): API level is " + Integer.valueOf(android.os.Build.VERSION.SDK_INT));

        String state = null;
        try {
            state = Environment.getExternalStorageState();
        } catch (RuntimeException e) {
            Log.e(TAG, "Is the SD card visible?", e);
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
        }

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {

            // We can read and write the media
            //    	if (Integer.valueOf(android.os.Build.VERSION.SDK_INT) > 7) {
            // For Android 2.2 and above

            try {
                return getExternalFilesDir(Environment.MEDIA_MOUNTED);
            } catch (NullPointerException e) {
                // We get an error here if the SD card is visible, but full
                Log.e(TAG, "External storage is unavailable");
                showErrorMessage("Error", "Required external storage (such as an SD card) is full or unavailable.");
            }

            //        } else {
            //          // For Android 2.1 and below, explicitly give the path as, for example,
            //          // "/mnt/sdcard/Android/data/edu.sfsu.cs.orange.ocr/files/"
            //          return new File(Environment.getExternalStorageDirectory().toString() + File.separator +
            //                  "Android" + File.separator + "data" + File.separator + getPackageName() +
            //                  File.separator + "files" + File.separator);
            //        }

        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // We can only read the media
            Log.e(TAG, "External storage is read-only");
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable for data storage.");
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            // to know is we can neither read nor write
            Log.e(TAG, "External storage is unavailable");
            showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable or corrupted.");
        }
        return null;
    }

    /**
     * Requests initialization of the OCR engine with the given parameters.
     *
     * @param storageRoot  Path to location of the tessdata directory to use
     * @param languageCode Three-letter ISO 639-3 language code for OCR
     * @param languageName Name of the language for OCR, for example, "English"
     */
    private void initOcrEngine(/*File storageRoot,*/ String languageCode, String languageName) {
        isEngineReady = false;

        // Set up the dialog box for the thermometer-style download progress indicator
    /*    if (dialog != null) {
            dialog.dismiss();
        }
        dialog = new ProgressDialog(this);

        // Display the name of the OCR engine we're initializing in the indeterminate progress dialog box
        indeterminateDialog = new ProgressDialog(this);
        indeterminateDialog.setTitle("Please wait");
        String ocrEngineModeName = getOcrEngineModeName();
        if (ocrEngineModeName.equals("Both")) {
            indeterminateDialog.setMessage("Initializing Cube and Tesseract OCR engines for " + languageName + "...");
        } else {
            indeterminateDialog.setMessage("Initializing " + ocrEngineModeName + " OCR engine for " + languageName + "...");
        }
        indeterminateDialog.setCancelable(false);
        indeterminateDialog.show();

        if (handler != null) {
            handler.quitSynchronously();
        }

        // Disable continuous mode if we're using Cube. This will prevent bad states for devices
        // with low memory that crash when running OCR with Cube, and prevent unwanted delays.
        if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY || ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
            Log.d(TAG, "Disabling continuous preview");
            isContinuousModeActive = false;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, false);
        }
*/
        // Start AsyncTask to install language data and init OCR
        baseApi = new TessBaseAPI();
        TessDataManager.initTessTrainedData(getApplicationContext());
        String path = TessDataManager.getTesseractFolder();

        //tess初始化
        baseApi.init(path, DEFAULT_SOURCE_LANGUAGE_CODE);

        //  indeterminateDialog.dismiss();

        // new OcrInitAsyncTask(this, baseApi, dialog, indeterminateDialog, languageCode, languageName, ocrEngineMode).execute(storageRoot.toString());
    }

    /**
     * 实时Ocr扫描成功方法
     * Displays information relating to the result of OCR, and requests a translation if necessary.
     *
     * @param ocrResult Object representing successful OCR results
     * @return True if a non-null result was received for OCR
     */
    boolean handleOcrDecode(OcrResult ocrResult) {

        Log.e("OCR_TEXT-------", ocrResult.getText());

        lastResult = ocrResult;

        // Test whether the result is null
        if (ocrResult.getText() == null || ocrResult.getText().equals("")) {
            Toast toast = Toast.makeText(this, "OCR failed. Please try again.", Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.TOP, 0, 0);
            toast.show();
            return false;
        }

        // Turn off capture-related UI elements
        shutterButton.setVisibility(View.GONE);
        statusViewBottom.setVisibility(View.GONE);
        statusViewTop.setVisibility(View.GONE);
        cameraButtonView.setVisibility(View.GONE);
        viewfinderView.setVisibility(View.GONE);
        resultView.setVisibility(View.VISIBLE);

        ImageView bitmapImageView = (ImageView) findViewById(R.id.image_view);
        lastBitmap = ocrResult.getBitmap();
        if (lastBitmap == null) {
            bitmapImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
                    R.mipmap.ic_launcher));
        } else {
            bitmapImageView.setImageBitmap(lastBitmap);
        }

        // Display the recognized text
        TextView sourceLanguageTextView = (TextView) findViewById(R.id.source_language_text_view);
        sourceLanguageTextView.setText(sourceLanguageReadable);
        TextView ocrResultTextView = (TextView) findViewById(R.id.ocr_result_text_view);
        ocrResultTextView.setText(ocrResult.getText());
        // Crudely scale betweeen 22 and 32 -- bigger font for shorter text
        int scaledSize = Math.max(22, 32 - ocrResult.getText().length() / 4);
        ocrResultTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

        TextView translationLanguageLabelTextView = (TextView) findViewById(R.id.translation_language_label_text_view);
        TextView translationLanguageTextView = (TextView) findViewById(R.id.translation_language_text_view);
        TextView translationTextView = (TextView) findViewById(R.id.translation_text_view);
        return true;
    }

    /**
     * * 实时OCR扫描成功的方法
     * Displays information relating to the results of a successful real-time OCR request.
     *
     * @param ocrResult Object representing successful OCR results
     */
    void handleOcrContinuousDecode(OcrResult ocrResult) {

        lastResult = ocrResult;

        // Send an OcrResultText object to the ViewfinderView for text rendering
        viewfinderView.addResultText(new OcrResultText(ocrResult.getText(),
                ocrResult.getWordConfidences(),
                ocrResult.getMeanConfidence(),
                ocrResult.getBitmapDimensions(),
                ocrResult.getRegionBoundingBoxes(),
                ocrResult.getTextlineBoundingBoxes(),
                ocrResult.getStripBoundingBoxes(),
                ocrResult.getWordBoundingBoxes(),
                ocrResult.getCharacterBoundingBoxes()));

        Integer meanConfidence = ocrResult.getMeanConfidence();

        if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT) {
            // Display the recognized text on the screen
            statusViewTop.setText(ocrResult.getText());
            int scaledSize = Math.max(22, 32 - ocrResult.getText().length() / 4);
            statusViewTop.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);
            statusViewTop.setTextColor(Color.BLACK);
            statusViewTop.setBackgroundResource(R.color.status_top_text_background);

            statusViewTop.getBackground().setAlpha(meanConfidence * (255 / 100));
        }

        if (CONTINUOUS_DISPLAY_METADATA) {
            // Display recognition-related metadata at the bottom of the screen
            long recognitionTimeRequired = ocrResult.getRecognitionTimeRequired();
            statusViewBottom.setTextSize(14);
            statusViewBottom.setText("OCR: " + sourceLanguageReadable + " - Mean confidence: " +
                    meanConfidence.toString() + " - Time required: " + recognitionTimeRequired + " ms");
        }
    }

    /**
     * 实时OCR扫描失败的方法
     * Version of handleOcrContinuousDecode for failed OCR requests. Displays a failure message.
     *
     * @param obj Metadata for the failed OCR request.
     */
    void handleOcrContinuousDecode(OcrResultFailure obj) {
        lastResult = null;
        viewfinderView.removeResultText();

        // Reset the text in the recognized text box.
        statusViewTop.setText("");

        if (CONTINUOUS_DISPLAY_METADATA) {
            // Color text delimited by '-' as red.
            statusViewBottom.setTextSize(14);
            CharSequence cs = setSpanBetweenTokens("OCR: " + sourceLanguageReadable + " - OCR failed - Time required: "
                    + obj.getTimeRequired() + " ms", "-", new ForegroundColorSpan(0xFFFF0000));
            statusViewBottom.setText(cs);
        }
    }

    /**
     * Given either a Spannable String or a regular String and a token, apply
     * the given CharacterStyle to the span between the tokens.
     * <p>
     * NOTE: This method was adapted from:
     * http://www.androidengineer.com/2010/08/easy-method-for-formatting-android.html
     *
     * <p>
     * For example, {@code setSpanBetweenTokens("Hello ##world##!", "##", new
     * ForegroundColorSpan(0xFFFF0000));} will return a CharSequence {@code
     * "Hello world!"} with {@code world} in red.
     */
    private CharSequence setSpanBetweenTokens(CharSequence text, String token,
                                              CharacterStyle... cs) {
        // Start and end refer to the points where the span will apply
        int tokenLen = token.length();
        int start = text.toString().indexOf(token) + tokenLen;
        int end = text.toString().indexOf(token, start);

        if (start > -1 && end > -1) {
            // Copy the spannable string to a mutable spannable string
            SpannableStringBuilder ssb = new SpannableStringBuilder(text);
            for (CharacterStyle c : cs)
                ssb.setSpan(c, start, end, 0);
            text = ssb;
        }
        return text;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
                                    ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v.equals(ocrResultView)) {
            menu.add(Menu.NONE, OPTIONS_COPY_RECOGNIZED_TEXT_ID, Menu.NONE, "Copy recognized text");
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (item.getItemId() == OPTIONS_COPY_RECOGNIZED_TEXT_ID) {
            //复制方法
            clipboardManager.setText(ocrResultView.getText());
            if (clipboardManager.hasText()) {
                Toast toast = Toast.makeText(this, "Text copied.", Toast.LENGTH_LONG);
                toast.setGravity(Gravity.BOTTOM, 0, 0);
                toast.show();
            }
            return true;
        }

        return false;
    }

    /**
     * Resets view elements.
     */
    private void resetStatusView() {
        resultView.setVisibility(View.GONE);
        if (CONTINUOUS_DISPLAY_METADATA) {
            statusViewBottom.setText("");
            statusViewBottom.setTextSize(14);
            statusViewBottom.setTextColor(getResources().getColor(R.color.status_text));
            statusViewBottom.setVisibility(View.VISIBLE);
        }
        if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT) {
            statusViewTop.setText("");
            statusViewTop.setTextSize(14);
            statusViewTop.setVisibility(View.VISIBLE);
        }
        viewfinderView.setVisibility(View.VISIBLE);
        cameraButtonView.setVisibility(View.VISIBLE);
        if (DISPLAY_SHUTTER_BUTTON) {
            shutterButton.setVisibility(View.VISIBLE);
        }
        lastResult = null;
        viewfinderView.removeResultText();
    }

    /**
     * Displays a pop-up message showing the name of the current OCR source language.
     */
    public void showLanguageName() {
        Toast toast = Toast.makeText(this, "OCR: " + sourceLanguageReadable, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
    }

    /**
     * Displays an initial message to the user while waiting for the first OCR request to be
     * completed after starting realtime OCR.
     */
    public void setStatusViewForContinuous() {
        viewfinderView.removeResultText();
        if (CONTINUOUS_DISPLAY_METADATA) {
            statusViewBottom.setText("OCR: " + sourceLanguageReadable + " - waiting for OCR...");
        }
    }

    @SuppressWarnings("unused")
    public void setButtonVisibility(boolean visible) {
        if (shutterButton != null && visible == true && DISPLAY_SHUTTER_BUTTON) {
            shutterButton.setVisibility(View.VISIBLE);
        } else if (shutterButton != null) {
            shutterButton.setVisibility(View.GONE);
        }
    }

    /**
     * Enables/disables the shutter button to prevent double-clicks on the button.
     *
     * @param clickable True if the button should accept a click
     */
    void setShutterButtonClickable(boolean clickable) {
        shutterButton.setClickable(clickable);
    }

    /**
     * Request the viewfinder to be invalidated.
     */
    void drawViewfinder() {
        viewfinderView.drawViewfinder();
    }

    @Override
    public void onShutterButtonClick(ShutterButton b) {
        if (isContinuousModeActive) {
            onShutterButtonPressContinuous();
        } else {
            if (handler != null) {
                handler.shutterButtonClick();
            }
        }
    }

    @Override
    public void onShutterButtonFocus(ShutterButton b, boolean pressed) {
        requestDelayedAutoFocus();
    }

    /**
     * Requests autofocus after a 350 ms delay. This delay prevents requesting focus when the user
     * just wants to click the shutter button without focusing. Quick button press/release will
     * trigger onShutterButtonClick() before the focus kicks in.
     */
    private void requestDelayedAutoFocus() {
        // Wait 350 ms before focusing to avoid interfering with quick button presses when
        // the user just wants to take a picture without focusing.
        cameraManager.requestAutoFocus(350L);
    }

    static boolean getFirstLaunch() {
        return isFirstLaunch;
    }


    /**
     * Returns a string that represents which OCR engine(s) are currently set to be run.
     *
     * @return OCR engine mode
     */
    String getOcrEngineModeName() {
        String ocrEngineModeName = "";
        String[] ocrEngineModes = getResources().getStringArray(R.array.ocrenginemodes);
        if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_ONLY) {
            ocrEngineModeName = ocrEngineModes[0];
        } else if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY) {
            ocrEngineModeName = ocrEngineModes[1];
        } else if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
            ocrEngineModeName = ocrEngineModes[2];
        }
        return ocrEngineModeName;
    }

    public void displayProgressDialog() {
        // Set up the indeterminate progress dialog box
        indeterminateDialog = new ProgressDialog(this);
        indeterminateDialog.setTitle("Please wait");
        String ocrEngineModeName = getOcrEngineModeName();
        if (ocrEngineModeName.equals("Both")) {
            indeterminateDialog.setMessage("Performing OCR using Cube and Tesseract...");
        } else {
            indeterminateDialog.setMessage("Performing OCR using " + ocrEngineModeName + "...");
        }
        indeterminateDialog.setCancelable(false);
        indeterminateDialog.show();
    }

    ProgressDialog getProgressDialog() {
        return indeterminateDialog;
    }

    /**
     * Displays an error message dialog box to the user on the UI thread.
     *
     * @param title   The title for the dialog box
     * @param message The error message to be displayed
     */
    public void showErrorMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setOnCancelListener(new FinishListener(this))
                .setPositiveButton("Done", new FinishListener(this))
                .show();
    }
}
