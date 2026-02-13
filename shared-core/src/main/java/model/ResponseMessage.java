package model;

/**
 * Represents a generic response message sent from the server to the client.
 *
 * Contains the unique message ID, status of the response, and a timestamp
 */
public class ResponseMessage {

  private String messageId;
  private String status;
  private String timestamp;

  public ResponseMessage() {}

  public ResponseMessage(String messageId, String status, String timestamp) {
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
