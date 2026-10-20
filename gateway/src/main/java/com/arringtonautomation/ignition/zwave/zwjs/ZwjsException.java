package com.arringtonautomation.ignition.zwave.zwjs;

/** A Z-Wave JS server request that failed, with the server's error code when it gave one. */
public class ZwjsException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String errorCode;

  public ZwjsException(String message) {
    super(message);
    this.errorCode = null;
  }

  public ZwjsException(String message, String errorCode) {
    super(message);
    this.errorCode = errorCode;
  }

  public ZwjsException(String message, Throwable cause) {
    super(message, cause);
    this.errorCode = null;
  }

  /** e.g. {@code unknown_command}, {@code node_not_found}, {@code zwave_error}; may be null. */
  public String getErrorCode() {
    return errorCode;
  }
}
