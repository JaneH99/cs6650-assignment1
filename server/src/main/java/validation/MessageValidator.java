package validation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import model.ClientMessage;
import model.MessageType;

/**
 * This class is a message validator, it validates the message that client send. It returns an instance of ValidationResult containing validation result, including if the message is valid or not, if not, which validation failed
 */
public class MessageValidator {
  public static ValidationResult validate(ClientMessage req) {
//    UserId
    if (req.getUserId() == null || req.getMessage().isEmpty()) {
      return new ValidationResult(false, "User Id is required");
    }
    int userId;
    try {
      userId = Integer.parseInt(req.getUserId());
    } catch (Exception e) {
      return new ValidationResult(false,"User Id must be a number");
    }
    if (userId < 1 || userId > 100000) {
      return new ValidationResult(false,"User Id must between 1 to 100000");
    }

//    UserName
    if (req.getUsername() == null || req.getUsername().isEmpty()) {
      return new ValidationResult(false,"User name is required");
    }
    String userName;
    userName = req.getUsername();
    if (!userName.matches("^[a-zA-Z0-9]{3,20}$")) {
      return new ValidationResult(false,"User name must contain 3 to 20 characters");
    }

//    Message
    if (req.getMessage() == null || req.getMessage().isEmpty()) {
      return new ValidationResult(false,"Message is required");
    }
    String message;
    message = req.getMessage();
    if (message.length() > 500) {
      return new ValidationResult(false,"Message must contain up to 500 characters");
    }

//    Message Type
    if (req.getMessageType() == null) {
      return new ValidationResult(false,"Message type is required");
    }
    try {
      MessageType.valueOf(String.valueOf(req.getMessageType()));
    } catch (IllegalArgumentException e) {
      return new ValidationResult(false,"Invalid message type: " + req.getMessageType());
    }

//    Timestamp
    try {
      Instant.parse(req.getTimestamp());
    } catch (DateTimeParseException e) {
      return new ValidationResult(false,"Timestamp must be ISO-8601");
    }

    return new ValidationResult(true, "Valid");
  }

  /**
   * Encapsulate validation result in this class
   */
  public static class ValidationResult {
    private final boolean result;
    private final String errorMessage;
    public ValidationResult(boolean result, String errorMessage) {
      this.result = result;
      this.errorMessage = errorMessage;
    }
    public boolean getResult() {
      return result;
    }
    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
