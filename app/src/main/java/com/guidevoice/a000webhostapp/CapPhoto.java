package com.guidevoice.a000webhostapp;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.IBinder;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionRequest;
import com.google.api.services.vision.v1.VisionRequestInitializer;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.EntityAnnotation;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Created by nikasha on 13/01/18.
 */

public class CapPhoto extends Service implements TextToSpeech.OnInitListener
{
    private SurfaceHolder sHolder;
    protected static Camera mCamera;
    private Camera.Parameters parameters;
    private Uri photoUri;
    private TextToSpeech mTts;
    protected static final Locale ESP = new Locale ("spa", "ESP");
    protected static String languagePair = "en-es"; //English to French ("<source_language>-<target_language>")
    //Set context
    private Context context=this;


    @Override
    public void onCreate()
    {
        super.onCreate();
        Log.d("CAM", "start");

        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy =
                    new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);}

        mTts = new TextToSpeech(this,
                this  // OnInitListener
        );
        mTts.setSpeechRate(0.5f);
        Log.v(MainActivity.TAG, "oncreate_streetModeservice");
    }
    @Override
    public void onStart(Intent intent, int startId) {

        super.onStart(intent, startId);

        if (Camera.getNumberOfCameras() >= 2) {

            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT); }

        if (Camera.getNumberOfCameras() < 2) {

            mCamera = Camera.open(); }
        SurfaceView sv = new SurfaceView(getApplicationContext());


        try {
            mCamera.setPreviewDisplay(sv.getHolder());
            parameters = mCamera.getParameters();
            mCamera.setParameters(parameters);
            mCamera.startPreview();

            mCamera.takePicture(null, null, mCall);
        } catch (IOException e) { e.printStackTrace(); }

        sHolder = sv.getHolder();
        sHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    Camera.PictureCallback mCall = new Camera.PictureCallback()
    {

        public void onPictureTaken(final byte[] data, Camera camera)
        {

            FileOutputStream outStream = null;
            try{

                File sd = new File(Environment.getExternalStorageDirectory()+"/A");
                if(!sd.exists()) {
                    sd.mkdirs();
                    Log.i("FO", "folder" + Environment.getExternalStorageDirectory());
                }

                Calendar cal = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                String tar = (sdf.format(cal.getTime()));
                String fullName = sd+"/"+tar+".jpg";
                outStream = new FileOutputStream(fullName);
                outStream.write(data);  outStream.close();

                Log.i("CAM", data.length + " byte written to:"+fullName);
                photoUri = Uri.fromFile(new File(fullName));
                uploadImage();
                camkapa(sHolder);
            } catch (FileNotFoundException e){
                Log.d("CAM", e.getMessage());
            } catch (IOException e){
                Log.d("CAM", e.getMessage());
            }}
    };


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void camkapa(SurfaceHolder sHolder) {

        if (null == mCamera)
            return;
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
        Log.i("CAM", " closed");
    }

    //Google Cloud Calls
    public void uploadImage() {
        if (photoUri != null) {
            try {
                // scale the image to save on bandwidth
                Bitmap bitmap =
                        scaleBitmapDown(
                                MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri),
                                1200);
                if(bitmap != null  && !bitmap.isRecycled())
                    callCloudVisionLabels(bitmap);

            } catch (IOException e) {
                Log.d(MainActivity.TAG, "Image picking failed because " + e.getMessage());
                Toast.makeText(this, R.string.image_picker_error, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.d(MainActivity.TAG, "Image picker gave us a null image.");
            Toast.makeText(this, R.string.image_picker_error, Toast.LENGTH_LONG).show();
        }
    }

    private void callCloudVisionLabels(final Bitmap bitmap) throws IOException {
        // Switch text to loading
        //mImageDetails.setText(R.string.loading_message);

        // Do the real work in an async task, because we need to use the network anyway
        new AsyncTask<Object, Void, String>() {
            @Override
            protected String doInBackground(Object... params) {
                try {
                    HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
                    JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

                    VisionRequestInitializer requestInitializer =
                            new VisionRequestInitializer(MainActivity.CLOUD_VISION_API_KEY) {
                                /**
                                 * We override this so we can inject important identifying fields into the HTTP
                                 * headers. This enables use of a restricted cloud platform API key.
                                 */
                                @Override
                                protected void initializeVisionRequest(VisionRequest<?> visionRequest)
                                        throws IOException {
                                    super.initializeVisionRequest(visionRequest);

                                    String packageName = getPackageName();
                                    visionRequest.getRequestHeaders().set(MainActivity.ANDROID_PACKAGE_HEADER, packageName);

                                    String sig = PackageManagerUtils.getSignature(getPackageManager(), packageName);

                                    visionRequest.getRequestHeaders().set(MainActivity.ANDROID_CERT_HEADER, sig);
                                }
                            };

                    Vision.Builder builder = new Vision.Builder(httpTransport, jsonFactory, null);
                    builder.setVisionRequestInitializer(requestInitializer);

                    Vision vision = builder.build();

                    BatchAnnotateImagesRequest batchAnnotateImagesRequest =
                            new BatchAnnotateImagesRequest();
                    batchAnnotateImagesRequest.setRequests(new ArrayList<AnnotateImageRequest>() {{
                        AnnotateImageRequest annotateImageRequest = new AnnotateImageRequest();

                        // Add the image
                        Image base64EncodedImage = new Image();
                        // Convert the bitmap to a JPEG
                        // Just in case it's a format that Android understands but Cloud Vision
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
                        byte[] imageBytes = byteArrayOutputStream.toByteArray();

                        // Base64 encode the JPEG
                        base64EncodedImage.encodeContent(imageBytes);
                        annotateImageRequest.setImage(base64EncodedImage);

                        // add the features we want
                        annotateImageRequest.setFeatures(new ArrayList<Feature>() {{
                            Feature labelDetection = new Feature();
                            labelDetection.setType("LABEL_DETECTION");
                            labelDetection.setMaxResults(10);
                            add(labelDetection);
                        }});

                        // Add the list of one thing to the request
                        add(annotateImageRequest);
                    }});

                    Vision.Images.Annotate annotateRequest =
                            vision.images().annotate(batchAnnotateImagesRequest);
                    // Due to a bug: requests to Vision API containing large images fail when GZipped.
                    annotateRequest.setDisableGZipContent(true);
                    Log.d(MainActivity.TAG, "created Cloud Vision request object, sending request");

                    BatchAnnotateImagesResponse response = annotateRequest.execute();
                    return convertResponseToString(response);

                } catch (GoogleJsonResponseException e) {
                    Log.d(MainActivity.TAG, "failed to make API request because " + e.getContent());
                } catch (IOException e) {
                    Log.d(MainActivity.TAG, "failed to make API request because of other IOException " +
                            e.getMessage());
                }
                return "Cloud Vision API request failed. Check logs for details.";
            }
            @Override
            protected void onPostExecute(String result){
                try{
                    //Aquí va textToSpeech
                    speak(Translate(result,languagePair));
                }catch (Exception e){
                    new Exception(e.getMessage());
                }
            }
        }.execute();
    }

    private void speak(String result) {
        mTts.speak(result,
                TextToSpeech.QUEUE_FLUSH,
                null);
    }

    public Bitmap scaleBitmapDown(Bitmap bitmap, int maxDimension) {

        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int resizedWidth = maxDimension;
        int resizedHeight = maxDimension;

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = (int) (resizedHeight * (float) originalWidth / (float) originalHeight);
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension;
            resizedHeight = (int) (resizedWidth * (float) originalHeight / (float) originalWidth);
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = maxDimension;
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false);
    }

    private String convertResponseToString(BatchAnnotateImagesResponse response) {
        String message = "";


        List<EntityAnnotation> labels = response.getResponses().get(0).getLabelAnnotations();
        if (labels != null) {
            for (EntityAnnotation label : labels) {
                message += String.format(ESP, "%s", label.getDescription());
                if(labels.indexOf(label) != labels.size() -1){
                    message += " ,";
                }else{
                    message += ".";
                }
            }
        } else {
            message += "Nothing";
        }

        return message;
    }

    //To TTs

    @Override
    public void onInit(int status) {
        Log.v(MainActivity.TAG, "oninit");
        if (status == TextToSpeech.SUCCESS) {
            int result = mTts.setLanguage(ESP);
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.v(MainActivity.TAG, "Language is not available.");
            }
        } else {
            Log.v(MainActivity.TAG, "Could not initialize TextToSpeech.");
        }
    }

    //Function for calling executing the Translator Background Task
    private String Translate(String textToBeTranslated,String languagePair) throws  InterruptedException, ExecutionException, JSONException{
        String translationResult;
        try{
            TranslatorBackgroundTask translatorBackgroundTask= new TranslatorBackgroundTask(context);
            translationResult = translatorBackgroundTask.execute(textToBeTranslated,languagePair).get(); // Returns the translated text as a String
            Log.d("Translation Result",translationResult); // Logs the result in Android Monitor
        }catch (InterruptedException e){
            throw new InterruptedException(e.getMessage());
        }catch (ExecutionException e){
            throw new ExecutionException(e);
        }
        String transText;
        try{
            JSONObject jsonobject = new JSONObject(translationResult);
            transText = jsonobject.getString("text").trim();
        }catch(JSONException e){
            throw new JSONException(e.getMessage());
        }

        return transText;
    }
}