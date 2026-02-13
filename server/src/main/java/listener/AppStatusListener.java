package listener;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * AppStatusListener manages the global lifecycle of the web application.
 * By implementing ServletContextListener, it can execute code exactly when
 * the application is deployed or undeployed by Tomcat.
 */
@WebListener
public class AppStatusListener implements ServletContextListener {

  /**
   * This method is called by Tomcat the moment the WAR file is successfully
   * deployed and the application context is created.
   * It is used here to initialize the "webSocketServerStatus" global attribute
   * to "UP", which is used for health check
   */
  @Override
  public void contextInitialized(ServletContextEvent sce) {
    // Initialize the global application state
    sce.getServletContext().setAttribute("webSocketServerStatus", "UP");
    System.out.println("=== Chat Application Initialized: Status set to UP ===");
  }

  /**
   * This method is called by Tomcat just before the application is shut down
   * or undeployed. It marks the application as "DOWN" for health check.
   */
  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    // Clean up global state before shutdown
    sce.getServletContext().setAttribute("webSocketServerStatus", "DOWN");
    System.out.println("=== Chat Application Shutting Down: Status set to DOWN ===");
  }
}
