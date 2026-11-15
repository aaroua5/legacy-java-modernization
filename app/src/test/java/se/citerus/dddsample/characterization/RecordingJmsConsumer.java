package se.citerus.dddsample.characterization;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Test replacement for SimpleLoggingConsumer: records "QueueName: payload" for every outgoing
 * notification (misdirected, delivered, rejected) so the characterization test can assert on them.
 */
public class RecordingJmsConsumer implements MessageListener {

  private static final List<String> RECEIVED = new ArrayList<String>();

  public void onMessage(Message message) {
    try {
      String queue = ((Queue) message.getJMSDestination()).getQueueName();
      String payload = message instanceof TextMessage ? ((TextMessage) message).getText() : message.getClass().getSimpleName();
      synchronized (RECEIVED) {
        RECEIVED.add(queue + ": " + payload);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns and clears everything received so far. */
  public static List<String> drain() {
    synchronized (RECEIVED) {
      List<String> copy = new ArrayList<String>(RECEIVED);
      RECEIVED.clear();
      return copy;
    }
  }
}
