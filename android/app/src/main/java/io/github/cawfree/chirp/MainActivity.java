package io.github.cawfree.chirp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

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
        // Define the ContentView.
        this.setContentView(R.layout.activity_main);
        mTxtReceive = ((EditText) findViewById(R.id.txtRX));
        this.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mTxtReceive.setText("");
            }
        });
        // Register an OnTouchListener.
        this.findViewById(R.id.btnSend).setOnClickListener(new View.OnClickListener() { @Override public final void onClick(final View pView) {
            // Are we not already chirping?
            if(!mChirp.isChirping()) {
                // Declare the Message.
                final String lMessage = ((EditText)findViewById(R.id.txtTX)).getText().toString();// = "datadatada";//"datadatada";//"parrotbill"; // hj05142014
                // ChirpFactory the message.
                try {
                    mChirp.chirp(lMessage);
                } catch (UnsupportedOperationException e) {
                    e.printStackTrace();
                    new AlertDialog.Builder(MainActivity.this).setMessage(e.getMessage()).show();
                }
            }
            else {
                // Inform the developer.
                Log.e(TAG, "Can't chirp whilst chirpin'!");
            }
        } });
        mListener = new Chirp.onReceiveListener() {
            @Override
            public void OnReceive(final String message) {
                runOnUiThread(new Runnable() {
                                  @Override
                                  public void run() {
                                      mTxtReceive.setText(message);
                                  }
                              }
                );
            }
        };
        if(Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String [] {Manifest.permission.RECORD_AUDIO} , REQUEST_CODE);
        } else {
            mChirp.init(mListener);
        }
    }

    /** Handle a permissions result. */
    @Override public final void onRequestPermissionsResult(final int pRequestCode, final @NonNull String[] pPermissions, final @NonNull int[] pGrantResults) {
        super.onRequestPermissionsResult(pRequestCode, pPermissions, pGrantResults);
        if(pRequestCode == REQUEST_CODE &&  Arrays.asList(pGrantResults).contains(Manifest.permission.RECORD_AUDIO)){
            Log.i(TAG, "Permission granted");
            mChirp.init(mListener);
        } else {
            if(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
                Log.i(TAG, "shouldShowRequestPermissionRationale=1");
                new AlertDialog.Builder(this).setMessage("record audio permission is needed").show();
            }
            finish();
        }
    }

    /** Handle resumption of the Activity. */
    @Override protected final void onResume() {
        // Implement the Parent.
        super.onResume();
        // Allocate the AudioThread.
        mChirp.setAudioThread(new Thread(mChirp.getAudioDispatcher()));
        // Start the AudioThread.
        mChirp.getAudioThread().start();
    }

    @Override
    protected final void onPause() {
        // Implement the Parent.
        super.onPause();
        // Stop the AudioDispatcher; implicitly stops the owning Thread.
        mChirp.getAudioDispatcher().stop();
    }

}