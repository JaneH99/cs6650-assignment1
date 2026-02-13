package model;

/**
 * This class encapsulates structured error information that can be sent back to the client. It is used when validation fails or an invalid request is received.
 */
public class ErrorMsg {
  String messageId;
  String errorType;
  String errorMessage;

  public ErrorMsg() {}
  public ErrorMsg(String messageId, String errorType, String errorMessage) {
    this.errorType = errorType;
    this.errorMessage = errorMessage;
    this.messageId = messageId;
  }


  public String getErrorType() {
    return errorType;
  }

  public void setErrorType(String errorType) {
    this.errorType = errorType;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }
}
