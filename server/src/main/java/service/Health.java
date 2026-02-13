package service;

import java.time.Instant;
import java.util.*;
import javax.servlet.annotation.WebServlet;

import com.google.gson.Gson;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * REST Endpoint: `/health`
 * It GET the health status of server status
 */

@WebServlet("/health")
public class Health extends HttpServlet {
  private final Gson gson = new Gson();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    String webSocketStatus = checkWebSocketHealth();
    boolean isWsOpen = webSocketStatus.equals("UP");
    Map<String, Object> response = new HashMap<>();
    response.put("webSocketStatus", isWsOpen ? "UP" : "DOWN");
    response.put("timestamp", Instant.now().toString());
    res.getWriter().write(gson.toJson(response));
  }

//  Get attribute saved by AppStatus Listener
  private String checkWebSocketHealth() {
    return (String) getServletContext().getAttribute("webSocketServerStatus");
  }

}
