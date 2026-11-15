package se.citerus.dddsample.characterization;

import com.aggregator.HandlingReport;
import com.aggregator.HandlingReportErrors_Exception;
import com.aggregator.HandlingReportService;
import org.hibernate.SessionFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import se.citerus.dddsample.application.util.SampleDataGenerator;
import se.citerus.dddsample.domain.model.cargo.Cargo;
import se.citerus.dddsample.domain.model.cargo.CargoRepository;
import se.citerus.dddsample.domain.model.cargo.Delivery;
import se.citerus.dddsample.domain.model.cargo.HandlingActivity;
import se.citerus.dddsample.domain.model.cargo.TrackingId;
import se.citerus.dddsample.domain.model.handling.HandlingEventFactory;
import se.citerus.dddsample.domain.model.handling.HandlingEventRepository;
import se.citerus.dddsample.interfaces.booking.facade.BookingServiceFacade;
import se.citerus.dddsample.interfaces.booking.facade.dto.CargoRoutingDTO;
import se.citerus.dddsample.interfaces.booking.facade.dto.LegDTO;
import se.citerus.dddsample.interfaces.booking.facade.dto.LocationDTO;
import se.citerus.dddsample.interfaces.booking.facade.dto.RouteCandidateDTO;
import se.citerus.dddsample.interfaces.tracking.CargoTrackingViewAdapter;

import javax.xml.datatype.DatatypeFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.fail;

/**
 * Characterization ("golden master") test: records what the legacy application does TODAY, through
 * its real entry points and its real XML wiring, so that every modernization step can prove it
 * changed nothing it didn't mean to change.
 *
 * <p>Entry points exercised: the booking facade (exposed over RMI in production), the SOAP handling
 * report service, the JMS pipeline (registration -> inspection -> notifications), and the public
 * tracking page through Spring's DispatcherServlet (up to the JSP forward; JSP rendering itself
 * needs a servlet container and is not covered here).
 *
 * <p>The recorded output lives in {@code src/test/resources/characterization/lifecycle.approved.txt}.
 * On a mismatch the actual output is written to {@code target/characterization/lifecycle.received.txt}.
 * Known bugs are recorded as-is on purpose (see the "KNOWN BUG" notes in the approved file).
 * Run with {@code -Dcharacterization.approve=true} to accept a deliberate behaviour change.
 */
public class LegacyLifecycleCharacterizationTest {

  // The app formats dates with the JVM default time zone and locale, so the snapshot pins both.
  // They are restored afterwards: other tests in the same JVM depend on the machine's settings.
  private static TimeZone originalTimeZone;
  private static Locale originalLocale;

  private static final File APPROVED = new File("src/test/resources/characterization/lifecycle.approved.txt");
  private static final File RECEIVED = new File("target/characterization/lifecycle.received.txt");
  private static final long AWAIT_MILLIS = 15000;

  private static MockServletContext servletContext;
  private static XmlWebApplicationContext root;
  private static DispatcherServlet trackingServlet;
  private static BookingServiceFacade booking;
  private static HandlingReportService handlingReports;
  private static CargoRepository cargoRepository;
  private static HandlingEventRepository handlingEventRepository;
  private static TransactionTemplate tx;

  private final StringBuilder out = new StringBuilder();
  /** The cargo the current scenario steps act on. */
  private String newCargo;
  /** Booked tracking ids are random: replaced by stable placeholders in the snapshot. */
  private final java.util.Map<String, String> placeholders = new java.util.LinkedHashMap<String, String>();

  @BeforeClass
  public static void startApplicationLikeWebXmlDoes() throws Exception {
    originalTimeZone = TimeZone.getDefault();
    originalLocale = Locale.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    Locale.setDefault(Locale.US);

    servletContext = new MockServletContext("src/main/webapp", new FileSystemResourceLoader());
    root = new XmlWebApplicationContext();
    root.setServletContext(servletContext);
    root.setConfigLocations(new String[]{
      // Same list and order as web.xml, plus test-only overrides last
      "classpath:/com/pathfinder/internal/applicationContext.xml",
      "classpath:context-infrastructure.xml",
      "classpath:context-application.xml",
      "classpath:context-domain.xml",
      "classpath:context-interfaces.xml",
      "classpath:characterization/characterization-context.xml"
    });
    root.refresh();
    servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, root);

    tx = new TransactionTemplate((PlatformTransactionManager) root.getBean("transactionManager"));
    booking = (BookingServiceFacade) root.getBean("bookingServiceFacade");
    handlingReports = (HandlingReportService) root.getBean("handlingReportService");
    cargoRepository = (CargoRepository) root.getBean("cargoRepository");
    handlingEventRepository = (HandlingEventRepository) root.getBean("handlingEventRepository");

    // What SampleDataGenerator does as a ServletContextListener at startup
    SampleDataGenerator.loadHibernateData(tx, (SessionFactory) root.getBean("sessionFactory"),
      (HandlingEventFactory) root.getBean("handlingEventFactory"), handlingEventRepository);

    trackingServlet = new DispatcherServlet();
    trackingServlet.init(new MockServletConfig(servletContext, "tracking"));
    RecordingJmsConsumer.drain();
  }

  @AfterClass
  public static void stopApplication() {
    if (trackingServlet != null) trackingServlet.destroy();
    if (root != null) root.close();
    TimeZone.setDefault(originalTimeZone);
    Locale.setDefault(originalLocale);
  }

  @Test
  public void fullCargoLifecycleMatchesApprovedBehaviour() throws Exception {
    line("# Legacy behaviour snapshot (time zone UTC, locale en_US, JDK 8 locale data)");
    line("KNOWN BUG (recorded as-is): the tracking page formats times with 'hh', a 12-hour clock without AM/PM,");
    line("so midnight shows as 12:00 and 14:30 as 02:30. Fixing it must be a deliberate, approved change.");

    step("Shipping locations offered by the booking facade");
    for (LocationDTO l : booking.listShippingLocations()) {
      line(l.getUnLocode() + "  " + l.getName());
    }

    step("Sample cargos loaded at startup");
    for (CargoRoutingDTO c : booking.listAllCargos()) {
      describeRouting(c.getTrackingId());
    }
    trackingPage("ABC123");
    trackingPage("JKL567");

    step("Book a new cargo: Hongkong -> Helsinki, arrival deadline 2009-04-01");
    newCargo = booking.bookNewCargo("CNHKG", "FIHEL", date("2009-04-01T00:00:00Z"));
    placeholders.put(newCargo, "<CARGO-A>");
    describeAll();

    step("Route candidates for a 2009 deadline");
    line("NOTE: the routing service generates candidates starting from TODAY's date, so with a deadline in");
    line("the past every candidate misses it and is filtered out. Time-dependent behaviour, recorded as-is.");
    for (int attempt = 1; attempt <= 3; attempt++) {
      line("attempt " + attempt + ": " + booking.requestPossibleRoutesForCargo(newCargo).size() + " candidates");
    }

    step("Assign route 0100S CNHKG-USNYC, 0200T USNYC-USDAL, 0300A USDAL-FIHEL");
    booking.assignCargoToRoute(newCargo, route(
      leg("0100S", "CNHKG", "USNYC", "2009-03-02T09:00:00Z", "2009-03-05T14:30:00Z"),
      leg("0200T", "USNYC", "USDAL", "2009-03-06T10:00:00Z", "2009-03-08T16:00:00Z"),
      leg("0300A", "USDAL", "FIHEL", "2009-03-09T07:00:00Z", "2009-03-12T11:00:00Z")));
    describeAll();

    handlingReport("RECEIVE", "CNHKG", null, "2009-03-01T08:00:00Z", 1);
    handlingReport("LOAD", "CNHKG", "0100S", "2009-03-02T09:00:00Z", 2);
    handlingReport("UNLOAD", "USNYC", "0100S", "2009-03-05T14:30:00Z", 3);
    handlingReport("LOAD", "USNYC", "0200T", "2009-03-06T10:00:00Z", 4);
    handlingReport("UNLOAD", "USDAL", "0200T", "2009-03-08T16:00:00Z", 5);
    handlingReport("LOAD", "USDAL", "0300A", "2009-03-09T07:00:00Z", 6);

    step("NOTE: next event unloads in Stockholm, which is not on the itinerary (expect misdirected)");
    handlingReport("UNLOAD", "SESTO", "0300A", "2009-03-11T13:15:00Z", 7);

    step("SOAP report for an unknown voyage passes validation but cannot be registered");
    submit("LOAD", "SESTO", "9999X", "2009-03-11T15:00:00Z");
    settle();
    line("handling events stored: " + countEvents(newCargo));
    line("NOTE: the failure is only logged. RejectedRegistrationAttemptsQueue is wired but nothing ever sends to it.");
    describeMessages();

    step("Change destination to Stockholm (expect misrouted)");
    booking.changeDestination(newCargo, "SESTO");
    describeAll();

    step("Assign new route ending in Stockholm");
    booking.assignCargoToRoute(newCargo, route(
      leg("0100S", "CNHKG", "USNYC", "2009-03-02T09:00:00Z", "2009-03-05T14:30:00Z"),
      leg("0200T", "USNYC", "USDAL", "2009-03-06T10:00:00Z", "2009-03-08T16:00:00Z"),
      leg("0300A", "USDAL", "SESTO", "2009-03-09T07:00:00Z", "2009-03-11T13:15:00Z")));
    describeAll();

    handlingReport("CLAIM", "SESTO", null, "2009-03-12T09:00:00Z", 8);
    line("NOTE: no DeliveredCargoQueue message was ever sent for this cargo: it was unloaded in Stockholm");
    line("before Stockholm became its destination, and 'delivered' is only signalled on unload.");

    step("Second cargo, happy path: Hongkong -> New York, deadline 2100-01-01");
    newCargo = booking.bookNewCargo("CNHKG", "USNYC", date("2100-01-01T00:00:00Z"));
    placeholders.put(newCargo, "<CARGO-B>");
    describeAll();

    step("Route candidates for a far-future deadline (random by design: only the contract is recorded)");
    for (int attempt = 1; attempt <= 5; attempt++) {
      line("attempt " + attempt + ": " + checkRouteContract(booking.requestPossibleRoutesForCargo(newCargo), "CNHKG", "USNYC"));
    }

    step("Assign route 0100S CNHKG-USNYC");
    booking.assignCargoToRoute(newCargo, route(
      leg("0100S", "CNHKG", "USNYC", "2009-03-02T09:00:00Z", "2009-03-05T14:30:00Z")));
    describeAll();

    handlingReport("RECEIVE", "CNHKG", null, "2009-03-01T08:00:00Z", 1);
    handlingReport("LOAD", "CNHKG", "0100S", "2009-03-02T09:00:00Z", 2);
    handlingReport("UNLOAD", "USNYC", "0100S", "2009-03-05T14:30:00Z", 3);

    step("Tracking page for an unknown tracking id");
    trackingPage("NOSUCH1");

    step("SOAP report with invalid data is rejected synchronously");
    try {
      HandlingReport bad = new HandlingReport();
      bad.getTrackingIds().add(newCargo);
      bad.setType("JUMP");
      bad.setUnLocode("XX");
      handlingReports.submitReport(bad);
      line("accepted (unexpected)");
    } catch (HandlingReportErrors_Exception e) {
      line("rejected: " + e.getMessage());
    }

    String snapshot = out.toString();
    for (java.util.Map.Entry<String, String> p : placeholders.entrySet()) {
      snapshot = snapshot.replace(p.getKey(), p.getValue());
    }
    verifyAgainstApproved(snapshot);
  }

  // ---------------------------------------------------------------- scenario helpers

  private void handlingReport(String type, String unLocode, String voyage, String completion, int expectedEvents) throws Exception {
    step("SOAP handling report: " + type + " at " + unLocode + (voyage == null ? "" : " voyage " + voyage) + ", completed " + completion);
    final Date before = new Date();
    submit(type, unLocode, voyage, completion);
    awaitInspected(expectedEvents, before);
    settle();
    describeAll();
  }

  private void submit(String type, String unLocode, String voyage, String completion) throws Exception {
    HandlingReport report = new HandlingReport();
    report.setCompletionTime(DatatypeFactory.newInstance().newXMLGregorianCalendar(completion));
    report.getTrackingIds().add(newCargo);
    report.setType(type);
    report.setUnLocode(unLocode);
    report.setVoyageNumber(voyage);
    handlingReports.submitReport(report);
  }

  /** The pipeline is asynchronous (JMS): wait until the event is stored AND the cargo was re-inspected. */
  private void awaitInspected(final int expectedEvents, final Date notBefore) throws Exception {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      boolean done = (Boolean) tx.execute(new TransactionCallback() {
        public Object doInTransaction(TransactionStatus status) {
          Cargo cargo = cargoRepository.find(new TrackingId(newCargo));
          return countEvents(newCargo) == expectedEvents && !cargo.delivery().calculatedAt().before(notBefore);
        }
      });
      if (done) return;
      Thread.sleep(50);
    }
    fail("Timed out waiting for " + expectedEvents + " handling events and re-inspection of " + newCargo);
  }

  /** Gives asynchronous notification messages time to arrive. */
  private void settle() throws InterruptedException {
    Thread.sleep(1000);
  }

  private void describeAll() throws Exception {
    describeRouting(newCargo);
    describeDelivery(newCargo);
    describeMessages();
    trackingPage(newCargo);
  }

  private void describeRouting(String trackingId) throws Exception {
    CargoRoutingDTO dto = booking.loadCargoForRouting(trackingId);
    line("[routing] " + dto.getTrackingId() + ": " + dto.getOrigin() + " -> " + dto.getFinalDestination()
      + ", deadline " + fmt(dto.getArrivalDeadline()) + ", routed=" + dto.isRouted() + ", misrouted=" + dto.isMisrouted());
    for (LegDTO l : dto.getLegs()) {
      line("  leg " + l.getVoyageNumber() + " " + l.getFrom() + " -> " + l.getTo()
        + ", load " + fmt(l.getLoadTime()) + ", unload " + fmt(l.getUnloadTime()));
    }
  }

  private void describeDelivery(final String trackingId) {
    line((String) tx.execute(new TransactionCallback() {
      public Object doInTransaction(TransactionStatus status) {
        Delivery d = cargoRepository.find(new TrackingId(trackingId)).delivery();
        return "[delivery] transport=" + d.transportStatus() + ", routing=" + d.routingStatus()
          + ", lastKnownLocation=" + d.lastKnownLocation().unLocode().idString()
          + ", currentVoyage=" + d.currentVoyage().voyageNumber().idString()
          + ", misdirected=" + d.isMisdirected() + ", unloadedAtDestination=" + d.isUnloadedAtDestination()
          + ", eta=" + fmt(d.estimatedTimeOfArrival()) + ", next=" + activity(d.nextExpectedActivity());
      }
    }));
  }

  private void describeMessages() {
    for (String m : RecordingJmsConsumer.drain()) {
      line("[jms] " + m);
    }
  }

  /** GET then POST /public/track through the real DispatcherServlet, as a browser would. */
  private void trackingPage(final String trackingId) throws Exception {
    // A surrounding transaction keeps the Hibernate session open while we read the view model,
    // like OpenSessionInView keeps it open while the JSP renders.
    tx.execute(new TransactionCallback() {
      public Object doInTransaction(TransactionStatus status) {
        try {
          MockHttpSession session = new MockHttpSession(servletContext);
          trackingServlet.service(trackRequest("GET", session), new MockHttpServletResponse());
          MockHttpServletRequest post = trackRequest("POST", session);
          post.addParameter("trackingId", trackingId);
          MockHttpServletResponse response = new MockHttpServletResponse();
          trackingServlet.service(post, response);

          line("[tracking page " + trackingId + "] forwards to " + response.getForwardedUrl());
          BindingResult errors = (BindingResult) post.getAttribute(BindingResult.MODEL_KEY_PREFIX + "trackCommand");
          if (errors != null) {
            for (Object e : errors.getAllErrors()) {
              line("  error: " + ((ObjectError) e).getCode() + " (" + ((ObjectError) e).getDefaultMessage() + ")");
            }
          }
          CargoTrackingViewAdapter view = (CargoTrackingViewAdapter) post.getAttribute("cargo");
          if (view != null) {
            line("  status: " + view.getStatusText());
            line("  " + view.getOrigin() + " -> " + view.getDestination() + ", eta " + view.getEta()
              + ", misdirected=" + view.isMisdirected());
            line("  next expected: " + view.getNextExpectedActivity());
            for (CargoTrackingViewAdapter.HandlingEventViewAdapter e : view.getEvents()) {
              line("  event: " + e.getTime() + " | " + e.getType() + " | " + e.getLocation() + " | voyage "
                + e.getVoyageNumber() + " | expected=" + e.isExpected() + " | " + e.getDescription());
            }
          }
          return null;
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
    });
  }

  private static MockHttpServletRequest trackRequest(String method, MockHttpSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method, "/public/track");
    request.setServletPath("/public");
    request.setPathInfo("/track");
    request.setSession(session);
    return request;
  }

  /** The routing service is random; record whether candidates obey the contract, not their content. */
  private static String checkRouteContract(List<RouteCandidateDTO> candidates, String origin, String destination) {
    List<String> problems = new ArrayList<String>();
    if (candidates.size() < 3 || candidates.size() > 5) problems.add("count " + candidates.size() + " not in 3..5");
    for (RouteCandidateDTO c : candidates) {
      List<LegDTO> legs = c.getLegs();
      if (!legs.get(0).getFrom().equals(origin)) problems.add("starts at " + legs.get(0).getFrom());
      if (!legs.get(legs.size() - 1).getTo().equals(destination)) problems.add("ends at " + legs.get(legs.size() - 1).getTo());
      for (int i = 0; i < legs.size(); i++) {
        if (!legs.get(i).getLoadTime().before(legs.get(i).getUnloadTime())) problems.add("leg unloads before it loads");
        if (i > 0 && !legs.get(i - 1).getTo().equals(legs.get(i).getFrom())) problems.add("legs not connected");
        if (i > 0 && legs.get(i - 1).getUnloadTime().after(legs.get(i).getLoadTime())) problems.add("next leg loads before previous unloads");
      }
    }
    return problems.isEmpty() ? "3-5 candidates, all connected origin->destination with increasing times" : problems.toString();
  }

  /** Joins the caller's transaction if there is one, otherwise opens its own. */
  private int countEvents(final String trackingId) {
    return (Integer) tx.execute(new TransactionCallback() {
      public Object doInTransaction(TransactionStatus status) {
        return handlingEventRepository.lookupHandlingHistoryOfCargo(new TrackingId(trackingId))
          .distinctEventsByCompletionTime().size();
      }
    });
  }

  private static String activity(HandlingActivity a) {
    if (a == null) return "none";
    return a.type() + " at " + a.location().unLocode().idString()
      + (a.voyage() == null ? "" : " voyage " + a.voyage().voyageNumber().idString());
  }

  private static RouteCandidateDTO route(LegDTO... legs) {
    return new RouteCandidateDTO(Arrays.asList(legs));
  }

  private static LegDTO leg(String voyage, String from, String to, String load, String unload) throws Exception {
    return new LegDTO(voyage, from, to, date(load), date(unload));
  }

  private static Date date(String iso) throws Exception {
    return DatatypeFactory.newInstance().newXMLGregorianCalendar(iso).toGregorianCalendar().getTime();
  }

  private static String fmt(Date date) {
    if (date == null) return "null";
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(date);
  }

  private void step(String title) {
    out.append('\n').append("## ").append(title).append('\n');
  }

  private void line(String text) {
    out.append(text).append('\n');
  }

  // ---------------------------------------------------------------- approval

  private static void verifyAgainstApproved(String actual) throws Exception {
    if (Boolean.getBoolean("characterization.approve")) {
      write(APPROVED, actual);
      return;
    }
    if (!APPROVED.exists()) {
      write(RECEIVED, actual);
      fail("No approved snapshot yet. Review " + RECEIVED + " and, if it is right, run with -Dcharacterization.approve=true");
    }
    String approved = read(APPROVED);
    if (!approved.equals(actual)) {
      write(RECEIVED, actual);
      String[] a = approved.split("\n", -1);
      String[] b = actual.split("\n", -1);
      for (int i = 0; i < Math.max(a.length, b.length); i++) {
        String x = i < a.length ? a[i] : "<missing>";
        String y = i < b.length ? b[i] : "<missing>";
        if (!x.equals(y)) {
          fail("Behaviour changed at line " + (i + 1) + "\n  approved: " + x + "\n  actual:   " + y
            + "\nFull output: " + RECEIVED);
        }
      }
    }
  }

  private static String read(File file) throws Exception {
    Reader r = new InputStreamReader(new FileInputStream(file), "UTF-8");
    try {
      StringBuilder sb = new StringBuilder();
      char[] buf = new char[8192];
      int n;
      while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
      return sb.toString();
    } finally {
      r.close();
    }
  }

  private static void write(File file, String content) throws Exception {
    file.getParentFile().mkdirs();
    Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
    try {
      w.write(content);
    } finally {
      w.close();
    }
  }
}
