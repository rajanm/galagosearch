package org.galagosearch.core.tools;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mortbay.jetty.handler.ContextHandler;

/**
 *
 * @author irmarc
 */
public class StreamContextHandler extends ContextHandler {

  Search search;

  public StreamContextHandler(Search search, String contextPath) {
    super(contextPath);
    this.search = search;
  }

  @Override
  public void handle(String target, HttpServletRequest request,
          HttpServletResponse response, int dispatch) throws IOException, ServletException {

    try {
      // Recover method
      ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
      String methodName = ois.readUTF();

      // Get arguments
      int numArgs = (int) ois.readShort();
      Class argTypes[] = new Class[numArgs];

      for (int i = 0; i < numArgs; i++) {
        argTypes[i] = (Class) ois.readObject();
      }

      Object[] arguments = new Object[numArgs];
      for (int i = 0; i < numArgs; i++) {
        arguments[i] = ois.readObject();
      }

      // NOW we can get the method itself and invoke it on our retrieval object
      // with the extracted arguments
      Method m = search.retrieval.getClass().getDeclaredMethod(methodName, argTypes);
      Object result = m.invoke(search.retrieval, arguments);

      // Finally send back our result
      ObjectOutputStream oos = new ObjectOutputStream(response.getOutputStream());
      oos.writeObject(result);
      response.flushBuffer();

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}