package websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import model.ClientMessage;
import model.ErrorMsg;
import model.SuccessMsg;
import validation.MessageValidator;
import validation.MessageValidator.ValidationResult;

/**
 * ChatWebSocket is a WebSocket server that manages chat rooms and client connections.
 * Each WebSocket connection is associated with exactly one chat room, identified by the URI path (/chat/{roomId}). The server tracks:
 *   room → set of WebSocket connections
 *   WebSocket → room mapping
 * The server validates incoming messages, returns success responses for valid messages, and sends structured error responses for invalid input or protocol errors.
 */

/**
 * WebSocket Endpoint for Chat Rooms.
 * Full URL: ws://localhost:8080/chat/{roomId}
 * * Note: 'chat' comes from the WAR filename,
 * '{roomId}' is captured from the path below.
 */
@ServerEndpoint("/{roomId}")
public class ChatWebSocket {
  private final Gson gson = new Gson();
//  Maps roomId to all websocket connections in that room
  private static final Map<String, Set<Session>> chatRooms = new ConcurrentHashMap<>();
//  Maps each websocket connection to its associated roomId
  private static final Map<Session, String> webSocketToRoom = new ConcurrentHashMap<>();


  /**
   * Called when a new WebSocket connection is opened.
   * Extracts the roomId from the URI and registers the connection in that room.
   */
  @OnOpen
  public void onOpen(Session session, @PathParam("roomId") String roomId) {
    if (roomId == null || roomId.trim().isEmpty()) {
      sendErrorMessage(session, "INVALID_ROOM", "Invalid room", null);
      try {
        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid room"));
      } catch (IOException e) {
        e.printStackTrace();
      }
      return;
    }

    chatRooms.computeIfAbsent(roomId, k -> Collections.synchronizedSet(new HashSet<>())).add(session);
    webSocketToRoom.put(session, roomId);

    System.out.println("Connection opened: " + session.getId() + " in room " + roomId);
  }

  /**
   * Called when a WebSocket connection is closed.
   * Removes the connection from its room and cleans up mappings.
   */
  @OnClose
  public void onClose(Session session) {
    String roomId = webSocketToRoom.remove(session);
    if (roomId != null) {
      Set<Session> sessions = chatRooms.get(roomId);
      if (sessions != null) {
        sessions.remove(session);
        if (sessions.isEmpty()) {
          chatRooms.remove(roomId);
        }
      }
    }
    System.out.println("Connection closed: " + session.getId());
  }

  /**
   * Handles incoming messages from a client.
   * Validates the message and responds with either a success or error message.
   */
  @OnMessage
  public void onMessage(Session session, String message) {
    try {
      ClientMessage req = gson.fromJson(message, ClientMessage.class);
      ValidationResult validationResult = MessageValidator.validate(req);

      if (!validationResult.getResult()) {
        sendErrorMessage(session, "VALIDATION_ERROR", validationResult.getErrorMessage(), req.getMessageId());
      } else {
        String roomId = webSocketToRoom.get(session);
        req.setRoomId(roomId);
        req.setStatus("SUCCESS");
        req.setTimestamp(Instant.now().toString());

        SuccessMsg successMsg = new SuccessMsg(req.getMessageId(), req.getStatus(), req.getTimestamp());
//        prevent multiple threads from overdriving the same socket
        synchronized (session) {
          try {
            session.getBasicRemote().sendText(gson.toJson(successMsg));
          } catch (IOException e) {
            System.err.println(e.getMessage());
          }
        }
      }

    } catch (JsonSyntaxException e) {
      sendErrorMessage(session, "INVALID_JSON", "Invalid JSON format: " + e.getMessage(), null);
    }
  }

  /**
   * Handles unexpected WebSocket errors by sending an error response to the client.
   */
  @OnError
  public void onError(Session session, Throwable throwable) {
    if (session != null && session.isOpen()) {
      sendErrorMessage(session, "WEBSOCKET_ERROR", throwable.getMessage(), null);
    }
  }


  /**
   * Sends a structured error message to the client if the connection is open.
   */
  private void sendErrorMessage(Session session, String errorType, String errorMessage, String messageId) {
    if (session.isOpen()) {
      ErrorMsg errorMsg = new ErrorMsg(messageId, errorType, errorMessage);
      session.getAsyncRemote().sendText(gson.toJson(errorMsg));
    }
  }
}
