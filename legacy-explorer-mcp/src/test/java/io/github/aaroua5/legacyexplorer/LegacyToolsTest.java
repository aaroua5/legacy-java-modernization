package io.github.aaroua5.legacyexplorer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every tool against the real legacy application in ../app and checks facts verified by hand
 * from its XML, Java and mapping files.
 */
class LegacyToolsTest {

    private static LegacyTools tools;

    @BeforeAll
    static void indexLegacyApp() throws Exception {
        tools = new LegacyTools(CodeIndex.load(Path.of(System.getProperty("legacy.root"))));
    }

    @Test
    void entryPointsCoverEveryWayIn() {
        String out = tools.entryPoints();
        System.out.println(out);
        assertThat(out)
                .contains("queue CargoHandledQueue -> bean 'cargoHandledConsumer'")
                .contains("queue HandlingEventRegistrationAttemptQueue -> bean 'handlingEventRegistrationAttemptConsumer'")
                .contains("address /RegisterEvent")
                .contains("URL /public/track -> bean '/track'")
                .contains("URL /admin/* -> bean '/*'")
                .contains("rmi name 'BookingService'")
                .contains("rmi name 'PathFinder'")
                .contains("every 5000 ms")
                .contains("se.citerus.dddsample.application.util.SampleDataGenerator");
    }

    @Test
    void springWiringShowsInjectionAndPathsFromEntryPoints() {
        String out = tools.springWiring("cargoRepository");
        System.out.println(out);
        assertThat(out)
                .contains("Bean 'cargoRepository' (se.citerus.dddsample.infrastructure.persistence.hibernate.CargoRepositoryHibernate)")
                .contains("'bookingService'")
                .contains("'cargoInspectionService'")
                .contains("'/track'")
                // JMS -> consumer -> service -> repository, wired only in XML
                .contains("JMS listener queue CargoHandledQueue: cargoHandledConsumer -> cargoInspectionService -> cargoRepository")
                // The admin web app reaches it through RMI
                .contains("remoteBookingService (RMI)")
                .contains("NOTE: no Java code creates this class with 'new'");
    }

    @Test
    void springWiringAcceptsAClassName() {
        assertThat(tools.springWiring("CargoInspectionServiceImpl")).contains("Bean 'cargoInspectionService'");
        assertThat(tools.springWiring("Cargo")).contains("not created by Spring XML");
    }

    @Test
    void findUsagesResolvesCallsThroughTheInterface() {
        String out = tools.findUsages("CargoRepositoryHibernate#find");
        System.out.println(out);
        assertThat(out)
                .containsPattern("Also counts calls made through its supertypes: .*se\\.citerus\\.dddsample\\.domain\\.model\\.cargo\\.CargoRepository")
                .contains("CargoInspectionServiceImpl.java")
                .contains("(via CargoRepository)");
    }

    @Test
    void findUsagesOfAClassListsJavaAndConfiguration() {
        String out = tools.findUsages("se.citerus.dddsample.domain.model.cargo.Cargo");
        System.out.println(out);
        assertThat(out)
                .contains("Java references in main code")
                .contains("Java references in tests")
                .contains("Cargo.hbm.xml");
    }

    @Test
    void entityMappingShowsTableColumnsAndHql() {
        String out = tools.entityMapping("Cargo");
        System.out.println(out);
        assertThat(out)
                .contains("-> table Cargo")
                .contains("trackingId.id -> column tracking_id")
                .contains("delivery.misdirected -> column is_misdirected")
                .contains("HQL or SQL query strings naming this entity")
                .contains("CargoRepositoryHibernate.java");
        assertThat(tools.entityMapping("no_such_table")).contains("Mapped entities:");
    }

    @Test
    void impactOfChangeCombinesCodeTestsWiringAndSchema() {
        String out = tools.impactOfChange("CargoRepository");
        System.out.println(out);
        assertThat(out)
                .contains("Implementations / subclasses")
                .contains("CargoRepositoryHibernate")
                .contains("Tests that reference it directly")
                .contains("'cargoRepository'")
                .contains("reached from JMS listener queue CargoHandledQueue");
    }

    @Test
    void unknownOrAmbiguousNamesGiveHelpfulErrors() {
        assertThat(tools.findUsages("NoSuchClass")).contains("No class named 'NoSuchClass'");
        assertThat(tools.findUsages("DomainEvent")).contains("ambiguous");
    }
}
