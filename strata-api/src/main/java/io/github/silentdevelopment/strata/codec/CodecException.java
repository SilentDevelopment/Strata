package io.github.silentdevelopment.strata.codec;


/**
 * Runtime exception thrown by codec implementations for encoding or decoding errors.
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }

}
