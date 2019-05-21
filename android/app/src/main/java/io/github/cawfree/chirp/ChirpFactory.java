package io.github.cawfree.chirp;

/**
 * Created by cawfree on 05/10/17.
 */

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Represents an encoded ChirpFactory, capable of transmission. */
public class ChirpFactory {

    /* Static Declarations. */
    private static final double            SEMITONE               = 1.05946311;
    private static final int               MINIMUM_PERIOD_MS      = 120; /** TODO: Find performance improvements that allow us to beat Chirp. (<100ms). */

    /* Default Declarations. */
    public static final int                 DEFAULT_FREQUENCY_BASE = 1760;
    public static final ChirpFactory.Range DEFAULT_RANGE = new Range(".0123456789abcdefghijklmnopqrst ", 0b00100101, 31);
    /**
     * TODO: i.e. 2^5 - 1.
     */
    public static final String              DEFAULT_IDENTIFIER     = "hj";
    public static final int                 DEFAULT_LENGTH_PAYLOAD = 10;
    public static final int                 DEFAULT_LENGTH_CRC     = 8;
    public static final int                 DEFAULT_PERIOD_MS      = ChirpFactory.MINIMUM_PERIOD_MS;
    public static final ChirpFactory.Result RESULT_UNKNOWN         = new Result(null, false);

    /** Declares a ChirpResult. */
    public static class Result {
        /* Member Variables. */
        private final Character mCharacter;
        private final boolean   mValid;
        /** Constructor. */
        public Result(final Character pCharacter, final boolean pIsValid) {
            // Initialize Member Variables.
            this.mCharacter = pCharacter;
            this.mValid     = pIsValid;
        }
        /* Getters. */
        public final Character getCharacter() { return this.mCharacter; }
        public final boolean   isValid()      { return this.mValid;     }
    }

    /** A base interface for a concrete class capable of interpreting chirp data. */
    public interface IDetector {
        /** Detects a Symbol within an array of Samples and Confidences. Callers must define the segment they wish to analyze within the provided buffers. */
        ChirpFactory.Result getSymbol(final ChirpFactory pChirpFactory, final double[] pSamples, final double[] pConfidences, final int pOffset, final int pLength);
    }

    /** Called when a Chirp has beend detected. */
    public interface IListener {
        /** Called when a Chirp has been detected. Returns the response data. */
        void onChirp(final String pMessage);
    }

    /** A default ChirpDetector, which uses an average to interpret symbols. */
    public static final IDetector DETECTOR_CHIRP_MEAN = new IDetector() { @Override public final Result getSymbol(final ChirpFactory pChirpFactory, final double[] pSamples, final double[] pConfidences, final int pOffset, final int pLength) {
        // Ignore the First/Last 18% of the Samples. (Protected against slew rate.)
        final int    lIgnore = (int)Math.ceil(pLength * 0.3);
        // Declare buffers to accumulate the sampled frequencies.
              double lFacc   = 0.0;
              int    lCount  = 0;
        // Iterate the Samples.
        for(int i = pOffset + lIgnore; i < pOffset + pLength - lIgnore; i++) { /** TODO: fn */
            // Are we confident in this sample?
            if(pConfidences[i] > 0.75) {
                // Fetch the Sample.
                final double lSample = pSamples[i];
                // Is the Sample valid?
                if(lSample != -1) {
                    // Accumulate the Sample.
                    lFacc += lSample;
                    // Update the accumulated count.
                    lCount++;
                }
            }
        }
        // Result valid?
        if(lCount != 0) {
            // Calculate the Mean.
            final double lMean = lFacc / lCount;
            /** TODO: Frequency tolerance? */
            // Return the Result.
            return new ChirpFactory.Result(pChirpFactory.getCharacterFor(lMean), true);
        }
        else {
            // Return the invalid result.
            return ChirpFactory.RESULT_UNKNOWN;
        }
    } };

    /** Returns the Character corresponding to a Frequency. */
    public final Character getCharacterFor(final double pPitch) {
        // Declare search metrics.
        double lDistance = Double.POSITIVE_INFINITY;
        int    lIndex    = -1;
        // Iterate the Frequencies.
        for(int i = 0; i < this.getFrequencies().length; i++) {
            // Fetch the Frequency.
            final Double lFrequency = this.getFrequencies()[i];
            // Calculate the Delta.
            final double lDelta     = Math.abs(pPitch - lFrequency);
            // Is the Delta smaller than the current distance?
            if(lDelta < lDistance) {
                // Overwrite the Distance.
                lDistance = lDelta;
                // Track the Index.
                lIndex    = i;
            }
        }
        // Fetch the corresponding character.
        final Character lCharacter = this.getMapFreqChar().get(Double.valueOf(this.getFrequencies()[lIndex]));
        // Return the Character.
        return lCharacter;
    }


    /** Defines the set of allowable characters for a given protocol. */
    public static class Range {
        /* Member Variables. */
        private final String mCharacters;
        private final int    mGaloisPolynomial;
        private final int    mFrameLength;
        /** Constructor. */
        public Range(final String pCharacters, final int pGaloisPolynomial, final int pFrameLength) {
            // Initialize Member Variables.
            this.mCharacters       = pCharacters;
            this.mGaloisPolynomial = pGaloisPolynomial;
            this.mFrameLength      = pFrameLength;
        }
        /* Getters. */
        public final String getCharacters()       { return this.mCharacters;       }
        public final int    getGaloisPolynomial() { return this.mGaloisPolynomial; }
        public final int    getFrameLength()      { return this.mFrameLength;      }
    }

    /** Factory Pattern. */
    public static final class Builder {
        /* Default Declarations. */
        private double             mBaseFrequency  = ChirpFactory.DEFAULT_FREQUENCY_BASE; // Declares the initial frequency which the range of Chirps is built upon.
        private ChirpFactory.Range mRange          = ChirpFactory.DEFAULT_RANGE;
        private String             mIdentifier     = ChirpFactory.DEFAULT_IDENTIFIER;
        private int                mPayloadLength  = ChirpFactory.DEFAULT_LENGTH_PAYLOAD;
        private int                mErrorLength    = ChirpFactory.DEFAULT_LENGTH_CRC;
        private int                mSymbolPeriodMs = ChirpFactory.DEFAULT_PERIOD_MS;
        /** Builds the ChirpFactory Object. */
        public final ChirpFactory build() throws IllegalStateException {

            /** TODO: Check lengths etc */

            // Allocate and return the ChirpFactory.
            return new ChirpFactory(this.getBaseFrequency(), this.getIdentifier(), this.getRange(), this.getPayloadLength(), this.getErrorLength(), this.getSymbolPeriodMs());
        }
        /* Setters. */
        public final ChirpFactory.Builder setSymbolPeriodMs(final int pSymbolPeriodMs) { this.mSymbolPeriodMs = pSymbolPeriodMs; return this; }
        /* Getters. */
        private final double             getBaseFrequency()  { return this.mBaseFrequency;  }
        private final ChirpFactory.Range getRange()          { return this.mRange;          }
        private final String             getIdentifier()     { return this.mIdentifier;     }
        public final int                 getPayloadLength()  { return this.mPayloadLength;  }
        public final int                 getErrorLength()    { return this.mErrorLength;    }
        public final int                 getSymbolPeriodMs() { return this.mSymbolPeriodMs; }
    }

    /* Member Variables. */
    private final double                 mBaseFrequency;
    private final String                 mIdentifier;
    private final ChirpFactory.Range     mRange;
    private final int                    mPayloadLength;
    private final int                    mErrorLength;
    private final int                    mSymbolPeriodMs;
    private final double[]               mFrequencies; /** TODO: to "tones" */
    private final Map<Character, Double> mMapCharFreq;
    private final Map<Double, Character> mMapFreqChar;

    /** Private construction; force the Builder pattern. */
    private ChirpFactory(final double pBaseFrequency, final String pIdentifier, final ChirpFactory.Range pRange, final int pPayloadLength, final int pErrorLength, final int pSymbolPeriodMs) {
        // Allocate the Frequencies.
        final double[] lFrequencies = new double[pRange.getCharacters().length()];
        // Declare the Mappings. (It's useful to index via either the Character or the Frequency.)
        final Map<Character, Double> lMapCharFreq = new HashMap<>(); /** TODO: Use only a single mapping? */
        final Map<Double, Character> lMapFreqChar = new HashMap<>();
        // Generate the frequencies that correspond to each valid symbol.
        for(int i = 0; i < pRange.getCharacters().length(); i++) {
            // Fetch the Character.
            final char   c               = pRange.getCharacters().charAt(i);
            // Calculate the Frequency.
            final double lFrequency      = pBaseFrequency * Math.pow(ChirpFactory.SEMITONE, i);
            // Buffer the Frequency.
                         lFrequencies[i] = lFrequency;
            // Buffer the Frequency.
            lMapCharFreq.put(Character.valueOf(c), Double.valueOf(lFrequency));
            lMapFreqChar.put(Double.valueOf(lFrequency), Character.valueOf(c));
        }
        // Initialize Member Variables.
        this.mBaseFrequency  = pBaseFrequency;
        this.mIdentifier     = pIdentifier;
        this.mRange          = pRange;
        this.mPayloadLength  = pPayloadLength;
        this.mErrorLength    = pErrorLength;
        this.mSymbolPeriodMs = pSymbolPeriodMs;
        // Assign the Frequencies.
        this.mFrequencies    = lFrequencies; /** TODO: Move to a fn of the MapFreqChar. */
        // Prepare the Mappings. (Make them unmodifiable after initialization.)
        this.mMapCharFreq    = Collections.unmodifiableMap(lMapCharFreq);
        this.mMapFreqChar    = Collections.unmodifiableMap(lMapFreqChar);
    }

    /** Returns the total length of an encoded message. */
    public final int getEncodedLength() {
        // The total packet is comprised as follows: [ID_SYMBOLS + PAYLOAD_SYMBOLS + ERROR_SYMBOLS].
        return this.getIdentifier().length() + this.getPayloadLength() + this.getErrorLength();
    }

    /* Getters. */
    public final double getBaseFrequency() {
        return this.mBaseFrequency;
    }

    public final String getIdentifier() {
        return this.mIdentifier;
    }

    public final double[] getFrequencies() {
        return this.mFrequencies;
    }

    public final Map<Character, Double> getMapCharFreq() {
        return this.mMapCharFreq;
    }

    public final Map<Double, Character> getMapFreqChar() {
        return this.mMapFreqChar;
    }

    public final ChirpFactory.Range getRange() {
        return this.mRange;
    }

    public final int getPayloadLength() {
        return this.mPayloadLength;
    }

    public final int getErrorLength() {
        return this.mErrorLength;
    }

    public final int getSymbolPeriodMs() {
        return this.mSymbolPeriodMs;
    }

}
