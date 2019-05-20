package io.github.cawfree.chirp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder;
import com.google.zxing.common.reedsolomon.ReedSolomonException;

import java.util.Arrays;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;

public class MainActivity extends AppCompatActivity {
    /* Logging. */
    private static final String TAG = "chirp.io";

    /** TODO: How to function based on the symbol period? */
    private static final ChirpFactory FACTORY_CHIRP = new ChirpFactory.Builder().setSymbolPeriodMs(85).build();

    /* Sampling Declarations. */
    private static final int WRITE_AUDIO_RATE_SAMPLE_HZ = 44100; // (Guaranteed for all devices!)
    private static final int WRITE_NUMBER_OF_SAMPLES    = (int)(MainActivity.FACTORY_CHIRP.getEncodedLength() * (MainActivity.FACTORY_CHIRP.getSymbolPeriodMs() / 1000.0f) * MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ);
    private static final int READ_NUMBER_OF_SAMPLES     = ((int)((MainActivity.FACTORY_CHIRP.getSymbolPeriodMs() / 1000.0f) * MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ));
    private static final int READ_SUBSAMPLING_FACTOR    = 9;
    public static final int REQUEST_CODE = 1;
    private EditText mTxtReceive;

    /** Creates a ChirpFactory from a ChirpBuffer. */
    public static final String getChirp(final int[] pChirpBuffer, final int pChirpLength) {
        // Declare the ChirpFactory.
        String lChirp = ""; /** TODO: To StringBuilder. */
        // Buffer the initial characters.
        for(int i = 0; i < pChirpLength; i++) {
            // Update the ChirpFactory.
            lChirp += FACTORY_CHIRP.getRange().getCharacters().charAt(pChirpBuffer[i]);
        }
        // Iterate the ChirpBuffer. (Skip over the zero-padded region.)
        for(int i = (pChirpBuffer.length) - MainActivity.FACTORY_CHIRP.getErrorLength(); i < pChirpBuffer.length; i++) {
            // Update the ChirpFactory.
            lChirp += FACTORY_CHIRP.getRange().getCharacters().charAt(pChirpBuffer[i]);
        }
        // Return the ChirpFactory.
        return lChirp;
    }

    /** Inserts an element at the end of the array, pushing all other elements down towards lesser indices. Returns the popped element. */
    private static final double push(final double[] pBuffer, final double pT) {
        // Fetch the first element.
        final double lPopped = pBuffer[0];
        // Iterate the Buffer.
        for(int i = 0; i < pBuffer.length - 1; i++) {
            // Offset the entries.
            pBuffer[i] = pBuffer[i + 1];
        }
        // Append the new sample.
        pBuffer[pBuffer.length - 1] = pT;
        // Return the Popped sample.
        return lPopped;
    }

    /* Member Variables. */
    private AudioTrack         mAudioTrack;
    private ReedSolomonEncoder mReedSolomonEncoder;
    private ReedSolomonDecoder mReedSolomonDecoder;
    private AudioDispatcher    mAudioDispatcher;
    private Thread             mAudioThread;
    private boolean            mChirping;
    private boolean            mSampleSelf;
    private double[]           mSampleBuffer;
    private double[]           mConfidenceBuffer;

    /** TODO: How to know the transmission medium is free? */

    @Override
    public final void onCreate(final Bundle pSavedInstanceState) {
        // Implement the Parent.
        super.onCreate(pSavedInstanceState);
        // Define the ContentView.
        this.setContentView(R.layout.activity_main);

        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
                Log.i(TAG, "shouldShowRequestPermissionRationale=1");
                new AlertDialog.Builder(this).setMessage("record audio permission is needed").show();
            }
            requestPermissions(new String [] {Manifest.permission.RECORD_AUDIO} , REQUEST_CODE);
        }
        // Allocate the AudioTrack; this is how we'll be generating continuous audio.
        this.mAudioTrack  = new AudioTrack(AudioManager.STREAM_MUSIC, MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, MainActivity.WRITE_NUMBER_OF_SAMPLES, AudioTrack.MODE_STREAM);
        this.mAudioThread = null;
        // Declare the Galois Field. (5-bit, using root polynomial a^5 + a^2 + 1.)
        final GenericGF          lGenericGF          = new GenericGF(FACTORY_CHIRP.getRange().getGaloisPolynomial(), MainActivity.FACTORY_CHIRP.getRange().getFrameLength() + 1, 1);
        // Allocate the ReedSolomonEncoder and ReedSolomonDecoder.
        this.mReedSolomonEncoder = new ReedSolomonEncoder(lGenericGF);
        this.mReedSolomonDecoder = new ReedSolomonDecoder(lGenericGF);
        // By default, we won't be chirping.
        this.mChirping           = false;
        // Define whether we should listen to our own chirps.
        this.mSampleSelf         = true;
        // Declare the SampleBuffer; capable of storing an entire chirp, with each symbol sampled at the sub-sampling rate.
        this.mSampleBuffer       = new double[MainActivity.READ_SUBSAMPLING_FACTOR * MainActivity.FACTORY_CHIRP.getEncodedLength()];
        // Allocate the ConfidenceBuffer; declares the corresponding confidence for each sample.
        this.mConfidenceBuffer   = this.getSampleBuffer().clone();
        // Allocate the AudioDispatcher. (Note; requires dangerous permissions!)
        this.mAudioDispatcher    = AudioDispatcherFactory.fromDefaultMicrophone(MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ, MainActivity.READ_NUMBER_OF_SAMPLES, 0); /** TODO: Abstract constants. */
        // Define a Custom AudioProcessor.
        this.getAudioDispatcher().addAudioProcessor(new AudioProcessor() {
            /* Member Variables. */
            private final PitchProcessor mPitchProcessor = new PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.FFT_YIN, MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ, (MainActivity.READ_NUMBER_OF_SAMPLES / MainActivity.READ_SUBSAMPLING_FACTOR), new PitchDetectionHandler() { @Override public final void handlePitch(final PitchDetectionResult pPitchDetectionResult, final AudioEvent pAudioEvent) {
                // Are we currently chirping?
                if(MainActivity.this.isChirping()) {
                    // Are we not allowed to sample ourself?
                    if(!MainActivity.this.isSampleSelf()) {
                        // Don't log the transactions.
                        return;
                    }
                }
                // Buffer the Pitch and the corresponding Confidence.
                MainActivity.push(MainActivity.this.getSampleBuffer(),     pPitchDetectionResult.getPitch());
                MainActivity.push(MainActivity.this.getConfidenceBuffer(), pPitchDetectionResult.getProbability());
                // Process the signal.
                MainActivity.onGaydecki(MainActivity.this.getReedSolomonDecoder(), MainActivity.this.getSampleBuffer(), MainActivity.this.getConfidenceBuffer(), MainActivity.READ_SUBSAMPLING_FACTOR, new ChirpFactory.IListener() { @Override public final void onChirp(final String pMessage) {
                    // Print the chirp.
                    Log.d(TAG, "Rx(" + pMessage + ")");
                    runOnUiThread(new Runnable() {
                                      @Override
                                      public void run() {
                                          mTxtReceive.setText(pMessage);
                                      }
                                  }
                    );

                    // Clear the buffer; prevent multiple chirps coming through.
                    Arrays.fill(MainActivity.this.getSampleBuffer(),    -1.0);
                    Arrays.fill(MainActivity.this.getConfidenceBuffer(), 0.0);
                } });
            } });
            /** When processing audio... */
            @Override public final boolean process(final AudioEvent pAudioEvent) {
                // Declare the TarsosDSPFormat; essentially make a safe copy of the existing setup, that recognizes the buffer has been split up.
                final TarsosDSPAudioFormat lTarsosDSPAudioFormat = new TarsosDSPAudioFormat(getAudioDispatcher().getFormat().getEncoding(), getAudioDispatcher().getFormat().getSampleRate(), getAudioDispatcher().getFormat().getSampleSizeInBits() / MainActivity.READ_SUBSAMPLING_FACTOR, getAudioDispatcher().getFormat().getChannels(), getAudioDispatcher().getFormat().getFrameSize() / MainActivity.READ_SUBSAMPLING_FACTOR, getAudioDispatcher().getFormat().getFrameRate(), getAudioDispatcher().getFormat().isBigEndian(), getAudioDispatcher().getFormat().properties());
                // Fetch the Floats.
                final float[]    lFloats     = pAudioEvent.getFloatBuffer();
                // Calculate the FrameSize.
                final int        lFrameSize  = (lFloats.length / MainActivity.READ_SUBSAMPLING_FACTOR);
                // Iterate across the Floats.
                for(int i = 0; i < (lFloats.length - lFrameSize); i += lFrameSize) {
                    // Segment the buffer.
                    final float[] lSegment = Arrays.copyOfRange(lFloats, i, i + lFrameSize);
                    // Allocate an AudioEvent.
                    final AudioEvent lAudioEvent = new AudioEvent(lTarsosDSPAudioFormat);
                    // Assign the Segment.
                    lAudioEvent.setFloatBuffer(lSegment);
                    // Export the AudioEvent to the PitchProessor.
                    this.getPitchProcessor().process(lAudioEvent);
                }
                // Assert that the event was handled.
                return true;
            }
            /** Once Processing is Finished... */
            @Override public final void processingFinished() {
                // Export the event to the PitchProcessor.
            }
            /* Getters. */
            private final PitchProcessor getPitchProcessor() {
                return this.mPitchProcessor;
            }
        });
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
            if(!MainActivity.this.isChirping()) {
                // Declare the Message.
                final String lMessage = ((EditText)findViewById(R.id.txtTX)).getText().toString();// = "datadatada";//"datadatada";//"parrotbill"; // hj05142014
                if(lMessage.length() != FACTORY_CHIRP.getPayloadLength()) {
                    new AlertDialog.Builder(MainActivity.this).setMessage("length must be " + FACTORY_CHIRP.getPayloadLength()).show();
                    return;
                }
                // ChirpFactory the message.
                MainActivity.this.chirp(lMessage);
            }
            else {
                // Inform the developer.
                Log.e(TAG, "Can't chirp whilst chirpin'!");
            }
        } });
    }

    /** Called when the samples have been updated. */
    private static final void onGaydecki(final ReedSolomonDecoder pReedSolomonDecoder, final double[] pSamples, final double[] pConfidences, final int pSubsamples, final ChirpFactory.IListener pChirpListener) {
        // Calculate the Number of Symbols.
        final int    lSymbols      = (pSamples.length / pSubsamples);
        // Declare the String.
              String lAccumulation = "";
        // Iterate the Samples whilst we're building up the string.
        for(int i = 0; i < lSymbols && (lAccumulation.length() != MainActivity.FACTORY_CHIRP.getEncodedLength()); i++) {
            // Fetch the Offset for the next Symbol.
            final int lOffset = (i * pSubsamples);
            // Detect the Chirp.
            final ChirpFactory.Result lResult = ChirpFactory.DETECTOR_CHIRP_MEAN.getSymbol(MainActivity.FACTORY_CHIRP, pSamples, pConfidences, lOffset, pSubsamples);
            // Is the Result valid?
            if(lResult.isValid()) {
                // Buffer the Result's data into the Accumulation.
                lAccumulation += lResult.getCharacter();
            }
        }
        // Is the accumulated data long enough?
        if(lAccumulation.length() == MainActivity.FACTORY_CHIRP.getEncodedLength()) {
            // Declare the Packetized Representation.
            final int[] lPacketized = new int[MainActivity.FACTORY_CHIRP.getRange().getFrameLength()];
            // Buffer the Header/Payload.
            for(int i = 0; i < MainActivity.FACTORY_CHIRP.getIdentifier().length() + MainActivity.FACTORY_CHIRP.getPayloadLength(); i++) {
                // Update the Packetized with the corresponding index value.
                lPacketized[i] = MainActivity.FACTORY_CHIRP.getRange().getCharacters().indexOf(lAccumulation.charAt(i));
            }
            // Iterate the Error Symbols.
            for(int i = 0; i < MainActivity.FACTORY_CHIRP.getErrorLength(); i++) {
                // Update the Packetized with the corresponding index value.
                lPacketized[MainActivity.FACTORY_CHIRP.getRange().getFrameLength() - MainActivity.FACTORY_CHIRP.getErrorLength() + i] = MainActivity.FACTORY_CHIRP.getRange().getCharacters().indexOf(lAccumulation.charAt(MainActivity.FACTORY_CHIRP.getIdentifier().length() + MainActivity.FACTORY_CHIRP.getPayloadLength() + i));
            }
            // Attempt to Reed/Solomon Decode.
            try {
                // Decode the Sample.
                pReedSolomonDecoder.decode(lPacketized, MainActivity.FACTORY_CHIRP.getErrorLength());
                // Declare the search metric.
                boolean lIsValid = true;
                // Iterate the Identifier characters.
                for(int i = 0; i < MainActivity.FACTORY_CHIRP.getIdentifier().length(); i++) {
                    // Update the search metric.
                    lIsValid &= MainActivity.FACTORY_CHIRP.getIdentifier().charAt(i) == (MainActivity.FACTORY_CHIRP.getRange().getCharacters().charAt(lPacketized[i]));
                }
                // Is the message directed to us?
                if(lIsValid) {
                    // Fetch the Message data.
                    String lMessage = "";
                    // Iterate the Packet.
                    for(int i = MainActivity.FACTORY_CHIRP.getIdentifier().length(); i < MainActivity.FACTORY_CHIRP.getIdentifier().length() + MainActivity.FACTORY_CHIRP.getPayloadLength(); i++) {
                        // Accumulate the Message.
                        lMessage += MainActivity.FACTORY_CHIRP.getRange().getCharacters().charAt(lPacketized[i]);
                    }
                    // Call the callback.
                    pChirpListener.onChirp(lMessage);
                }
            }
            catch(final ReedSolomonException pReedSolomonException) { /* Do nothing; we're transmitting across a very lossy channel! */ }
        }
    }

    /** Prints the equivalent information representation of a data string. */
    @SuppressWarnings("unused") public static final void indices(final String pData, final int[] pBuffer, final int pOffset) {
        // Iterate the Data.
        for(int i = 0; i < pData.length(); i++) {
            // Update the contents of the Array.
            pBuffer[pOffset + i] = FACTORY_CHIRP.getRange().getCharacters().indexOf(Character.valueOf(pData.charAt(i)));
        }
    }

    /** Handle a permissions result. */
    @Override public final void onRequestPermissionsResult(final int pRequestCode, final @NonNull String[] pPermissions, final @NonNull int[] pGrantResults) {
        if(pRequestCode == REQUEST_CODE &&  Arrays.asList(pGrantResults).contains(Manifest.permission.RECORD_AUDIO)){
            Log.i(TAG, "Permission granted");
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        }
        super.onRequestPermissionsResult(pRequestCode, pPermissions, pGrantResults);
    }

    /** Handle resumption of the Activity. */
    @Override protected final void onResume() {
        // Implement the Parent.
        super.onResume();
        // Allocate the AudioThread.
        this.setAudioThread(new Thread(this.getAudioDispatcher()));
        // Start the AudioThread.
        this.getAudioThread().start();
    }

    @Override
    protected final void onPause() {
        // Implement the Parent.
        super.onPause();
        // Stop the AudioDispatcher; implicitly stops the owning Thread.
        this.getAudioDispatcher().stop();
    }

    /** Encodes and generates a chirp Message. */
    public final void chirp(String pMessage) throws UnsupportedOperationException {
        // Is the message the correct length?
        if(pMessage.length() != FACTORY_CHIRP.getPayloadLength()) {
            // Assert that we can't generate the chirp; they need to match the Payload.
            throw new UnsupportedOperationException("Invalid message size (" + pMessage.length() + ")! Expected " + FACTORY_CHIRP.getPayloadLength() + " symbols.");
        } {
            // Declare the search metric.
            boolean lIsSupported = true;
            // Iterate through the Message.
            for(final char c : pMessage.toCharArray()) {
                // Update the search metric.
                lIsSupported &= FACTORY_CHIRP.getRange().getCharacters().indexOf(c) != -1;
            }
            // Is the message not supported?
            if(!lIsSupported) {
                // Inform the user.
                throw new UnsupportedOperationException("Message \"" + pMessage + "\" contains illegal characters.");
            }
        }
        // Assert that we're transmitting the Message. (Don't show error checksum codewords or the identifer.)
        Log.d(TAG, "Tx(" + pMessage + ")");
        // Append the Header.
        pMessage = FACTORY_CHIRP.getIdentifier().concat(pMessage);
        // Declare the ChirpBuffer.
        final int[] lChirpBuffer = new int[MainActivity.FACTORY_CHIRP.getRange().getFrameLength()];
        // Fetch the indices of the Message.
        MainActivity.indices(pMessage, lChirpBuffer, 0);
        // Encode the Bytes.
        MainActivity.this.getReedSolomonEncoder().encode(lChirpBuffer, MainActivity.FACTORY_CHIRP.getErrorLength());
        // Return the ChirpFactory.
        final String lChirp = MainActivity.getChirp(lChirpBuffer, pMessage.length()); // "hj050422014jikhif"; (This will work with ChirpFactory Share!)
        // ChirpFactory-y. (Period is in milliseconds.)
        MainActivity.this.chirp(lChirp, MainActivity.FACTORY_CHIRP.getSymbolPeriodMs());
    }

    /** Produces a chirp. */
    private final void chirp(final String pEncodedChirp, final int pPeriod) {
        // Declare an AsyncTask which we'll use for generating audio.
        final AsyncTask lAsyncTask = new AsyncTask<Void, Void, Void>() {
            /** Initialize the play. */
            @Override protected final void onPreExecute() {
                // Assert that we're chirping.
                MainActivity.this.setChirping(true);
                // Play the AudioTrack.
                MainActivity.this.getAudioTrack().play();
            }
            /** Threaded audio generation. */
            @Override protected Void doInBackground(final Void[] pIsUnused) {
                // Re-buffer the new tone.
                final byte[] lChirp = MainActivity.this.onGenerateChirp(pEncodedChirp, pPeriod);
                // Write the ChirpFactory to the Audio buffer.
                MainActivity.this.getAudioTrack().write(lChirp, 0, lChirp.length);
                // Satisfy the parent.
                return null;
            }
            /** Cyclic. */
            @Override protected final void onPostExecute(Void pIsUnused) {
                // Stop the AudioTrack.
                MainActivity.this.getAudioTrack().stop();
                // Assert that we're no longer chirping.
                MainActivity.this.setChirping(false);
            }
        };
        // Execute the AsyncTask on the pre-prepared ThreadPool.
        lAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null);
    }

    /** Generates a tone for the audio stream. */
    private final byte[] onGenerateChirp(final String pData, final int pPeriod) {
        // Calculate the Number of Samples per chirp.
        final int      lNumberOfSamples = (int)(MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ * (pPeriod / 1000.0f));
        // Declare the SampleArray.
              double[] lSampleArray     = new double[pData.length() * lNumberOfSamples];
        // Declare the Generation.
        final byte[]   lGeneration      = new byte[lSampleArray.length * 2];
        // Declare the Offset.
        int      lOffset          = 0;
        // Iterate the Transmission.
        for(int i = 0; i < pData.length(); i++) {
            // Fetch the Data.
            final Character lData      = Character.valueOf(pData.charAt(i));
            // Fetch the Frequency.
            final double    lFrequency = FACTORY_CHIRP.getMapCharFreq().get(lData);
            // Iterate the NumberOfSamples. (Per chirp data.)
            for(int j = 0; j < lNumberOfSamples; j++) {
                // Update the SampleArray.
                lSampleArray[lOffset] = Math.sin(2 * Math.PI * j / (MainActivity.WRITE_AUDIO_RATE_SAMPLE_HZ / lFrequency));
                // Increase the Offset.
                lOffset++;
            }
        }
        // Reset the Offset.
        lOffset = 0;
        // Iterate between each sample.
        for(int i = 0; i < pData.length(); i++) {
            // Fetch the Start and End Indexes of the Sample.
            final int lIo =   i * lNumberOfSamples;
            final int lIa = lIo + lNumberOfSamples;
            // Declare the RampWidth. We'll change it between iterations for more tuneful sound.)
            final int lRw = (int)(lNumberOfSamples * 0.3);
            // Iterate the Ramp.
            for(int j = 0; j < lRw; j++) {
                // Calculate the progression of the Ramp.
                final double lP = j / (double)lRw;
                // Scale the corresponding samples.
                lSampleArray[lIo + j + 0] *= lP;
                lSampleArray[lIa - j - 1] *= lP;
            }
        }

        // Declare the filtering constant.
        final double lAlpha    = 0.3;
              double lPrevious = 0;

        // Iterate the SampleArray.
        for(int i = 0; i < lSampleArray.length; i++) {
            // Fetch the Value.
            final double lValue    = lSampleArray[i];
            // Filter the Value.
            final double lFiltered = (lAlpha < 1.0) ? ((lValue - lPrevious) * lAlpha) : lValue;
            // Assume normalized, so scale to the maximum amplitude.
            final short lPCM = (short) ((lFiltered * 32767));
            // Supply the Generation with 16-bit PCM. (The first byte is the low-order byte.)
            lGeneration[lOffset++] = (byte) (lPCM & 0x00FF);
            lGeneration[lOffset++] = (byte)((lPCM & 0xFF00) >>> 8);
            // Overwrite the Previous with the Filtered value.
            lPrevious = lFiltered;
        }
        // Return the Generation.
        return lGeneration;
    }

    /** Converts a String to a corresponding alphabetized index array implementation. */
    public static final String array(final String pString) {
        // Convert the String to an equivalent Array.
        final int[] lArray = new int[pString.length()];
        // Iterate the Data.
        for(int i = 0; i < pString.length(); i++) {
            // Update the Array.
            lArray[i] = MainActivity.FACTORY_CHIRP.getRange().getCharacters().indexOf(pString.charAt(i));
        }
        // Return the Array.
        return MainActivity.array(lArray);
    }

    public static final String array(final int[] pArray) {
        // Define the Index equivalent.
        String lIndices = "[";
        // Iterate the Data.
        for(final int i : pArray) {
            // Update the Indices.
            lIndices += " " + i;
        }
        // Close the Array.
        lIndices += " ]";
        // Return the Array.
        return lIndices;
    }

    /* Getters. */
    private final AudioTrack getAudioTrack() {
        return this.mAudioTrack;
    }

    private final ReedSolomonDecoder getReedSolomonDecoder() {
        return this.mReedSolomonDecoder;
    }

    private final ReedSolomonEncoder getReedSolomonEncoder() {
        return this.mReedSolomonEncoder;
    }

    private AudioDispatcher getAudioDispatcher() {
        return this.mAudioDispatcher;
    }

    private final void setAudioThread(final Thread pThread) {
        this.mAudioThread = pThread;
    }

    private final Thread getAudioThread() {
        return this.mAudioThread;
    }

    private final void setChirping(final boolean pIsChirping) {
        this.mChirping = pIsChirping;
    }

    private final boolean isChirping() {
        return this.mChirping;
    }

    @SuppressWarnings("unused")
    public final void setSampleSelf(final boolean pIsSampleSelf) {
        this.mSampleSelf = pIsSampleSelf;
    }

    public final boolean isSampleSelf() {
        return this.mSampleSelf;
    }

    private final double[] getSampleBuffer() {
        return this.mSampleBuffer;
    }

    private final double[] getConfidenceBuffer() {
        return this.mConfidenceBuffer;
    }

}