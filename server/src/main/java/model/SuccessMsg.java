package model;

/**
 * This class encapsulates structured success information that can be sent back to the client.
 */
public class SuccessMsg {
  String messageId;
  String status;
  String timestamp;


  public SuccessMsg() {}

  public SuccessMsg(String messageId, String status, String timestamp) {
    this.messageId = messageId;
    this.status = status;
    this.timestamp = timestamp;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }
}
