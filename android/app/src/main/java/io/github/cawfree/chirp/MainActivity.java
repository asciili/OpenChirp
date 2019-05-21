package io.github.cawfree.chirp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.EditText;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    protected static final String TAG = "MainActivity";
    public static final int REQUEST_CODE = 1;
    /** TODO: How to know the transmission medium is free? */
    private EditText mTxtReceive;
    private Chirp mChirp = new Chirp();
    private Chirp.onReceiveListener mListener;

    @Override
    public final void onCreate(final Bundle pSavedInstanceState) {
        // Implement the Parent.
        super.onCreate(pSavedInstanceState);
        Log.i(TAG, "onCreate()");
        // Define the ContentView.
        this.setContentView(R.layout.activity_main);
        mTxtReceive = findViewById(R.id.txtRX);
        this.findViewById(R.id.btnClear).setOnClickListener(
                v -> mTxtReceive.setText("")
        );
        // Register an OnTouchListener.
        this.findViewById(R.id.btnSend).setOnClickListener(pView -> {
            // Are we not already chirping?
            if (!mChirp.isChirping()) {
                // Declare the Message.
                final String lMessage = ((EditText) findViewById(R.id.txtTX)).getText().toString();// = "datadatada";//"datadatada";//"parrotbill"; // hj05142014
                // ChirpFactory the message.
                try {
                    mChirp.chirp(lMessage);
                } catch (UnsupportedOperationException e) {
                    e.printStackTrace();
                    new AlertDialog.Builder(MainActivity.this).setMessage(e.getMessage()).show();
                }
            } else {
                // Inform the developer.
                Log.e(TAG, "Can't chirp whilst chirpin'!");
            }
        });
        if(Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String [] {Manifest.permission.RECORD_AUDIO} , REQUEST_CODE);
        } else {
            Log.i(TAG, "permission granted");
        }
        mChirp.setOnReceiveListener(message -> runOnUiThread(() -> mTxtReceive.setText(message)));
    }

    /** Handle a permissions result. */
    @Override public final void onRequestPermissionsResult(final int pRequestCode, final @NonNull String[] pPermissions, final @NonNull int[] pGrantResults) {
        super.onRequestPermissionsResult(pRequestCode, pPermissions, pGrantResults);
        Log.i(TAG, "onRequestPermissionsResult()");
        if(pRequestCode == REQUEST_CODE &&  Arrays.asList(pGrantResults).contains(Manifest.permission.RECORD_AUDIO)){
            Log.i(TAG, "Permission granted");
        } else {
            if(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
                Log.i(TAG, "shouldShowRequestPermissionRationale=1");
                new AlertDialog.Builder(this).setMessage("without record audio permission, receive function will missing")
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setTitle("permission required")
                        .setNeutralButton("continue", null)
                        .setNegativeButton("quit", (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            }

        }
    }

    /** Handle resumption of the Activity. */
    @Override protected final void onResume() {
        // Implement the Parent.
        super.onResume();
        Log.i(TAG, "onResume()");
        mChirp.start();
    }

    @Override
    protected final void onPause() {
        // Implement the Parent.
        super.onPause();
        Log.i(TAG, "onPause()");
        mChirp.stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop()");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
    }
}